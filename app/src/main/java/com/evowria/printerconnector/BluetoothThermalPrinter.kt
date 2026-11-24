package com.evowria.printerconnector

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Build
import java.io.IOException
import java.nio.charset.Charset
import java.util.UUID

/**
 * The printer's name as the team sees it on this tablet: the name given in
 * Android's Bluetooth settings (alias, Android 11+), else the name the printer
 * broadcasts. Renaming "Printer Meja 1" in settings then shows in the app.
 */
@SuppressLint("MissingPermission")
fun BluetoothDevice.displayName(): String? {
    val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { alias }.getOrNull()
    } else {
        null
    }
    return alias?.takeIf { it.isNotBlank() } ?: name
}

/**
 * Talks to a paired Bluetooth Classic printer using the common ESC/POS serial protocol.
 *
 * This class owns the socket. It does not show Android UI and does not know about WebView,
 * which keeps printer behaviour easy to test and replace for another printer protocol.
 */
class BluetoothThermalPrinter(
    /** Makes the table card's logo as a ready GS v 0 command; null = no logo. */
    loadLogoRaster: () -> ByteArray? = { null },
) {
    // Built the first time a table card prints (on the printer thread, not
    // while the app opens); an image that can't be read only drops the logo.
    private val logoRaster: ByteArray? by lazy {
        runCatching(loadLogoRaster).getOrNull()
    }
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val connectionLock = Any()

    @Volatile
    private var bluetoothSocket: BluetoothSocket? = null

    @Volatile
    private var connectedPrinter: BluetoothDevice? = null
    private var connectionGeneration = 0

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> =
        bluetoothAdapter?.bondedDevices?.sortedBy { device -> device.displayName() ?: device.address }
            ?: emptyList()

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): PrinterStatus {
        disconnect()
        val expectedConnectionGeneration = synchronized(connectionLock) { connectionGeneration }
        if (bluetoothAdapter == null) return disconnectedStatus("Bluetooth tidak tersedia di perangkat ini")
        if (!bluetoothAdapter.isEnabled) return disconnectedStatus("Bluetooth belum aktif")

        return try {
            val socket = device.createRfcommSocketToServiceRecord(SERIAL_PORT_UUID)
            socket.connect()
            val connected = synchronized(connectionLock) {
                if (connectionGeneration != expectedConnectionGeneration) {
                    false
                } else {
                    bluetoothSocket = socket
                    connectedPrinter = device
                    true
                }
            }
            if (!connected) {
                socket.close()
                return disconnectedStatus("Koneksi printer dibatalkan")
            }
            getStatus()
        } catch (error: Exception) {
            disconnect()
            disconnectedStatus(error.message ?: "Tidak dapat terhubung ke printer")
        }
    }

    @SuppressLint("MissingPermission")
    fun getStatus(): PrinterStatus {
        val (socket, device) = synchronized(connectionLock) {
            bluetoothSocket to connectedPrinter
        }
        val isConnected = socket?.isConnected == true
        return PrinterStatus(
            available = bluetoothAdapter != null,
            connected = isConnected,
            printerName = device?.displayName() ?: device?.address,
            detail = if (isConnected) null else "Printer belum terhubung",
        )
    }

    /** On sticker paper: one framed test sticker, then on to the next one. */
    fun printTestPage(paper: PrinterPaper): PrintResult = try {
        val output = requireOutputStream()
        output.write(EscPosCommands.initialize)
        if (paper == PrinterPaper.STICKER_50X30) {
            output.write(StickerTestPage.rasterCommand())
            output.write(EscPosCommands.nextLabel)
        } else {
            output.write("Evowria test print\n\n\n".toByteArray(PRINTER_CHARSET))
        }
        output.flush()
        PrintResult(PrintJobStatus.PRINTED)
    } catch (error: Exception) {
        dropBrokenLink(error)
        PrintResult(PrintJobStatus.FAILED, error.message ?: "Test print gagal")
    }

    fun printGuestLabel(label: GuestLabel): PrintResult = if (
        label.paper == PrinterPaper.STICKER_50X30 && label.image == null
    ) {
        // A receipt's text and cut on a sticker roll would waste stickers
        // and shift every one after it: nothing is sent.
        PrintResult(PrintJobStatus.FAILED, "Gambar stiker tidak terbaca. Coba cetak lagi.")
    } else try {
        val output = requireOutputStream()
        output.write(EscPosCommands.initialize)
        if (label.image != null) {
            // A sticker: the server's image as is, then on to the next
            // sticker (no tear-off feed, no cut).
            writeSticker(output, label.image)
        } else {
            if (label.blocks.isNotEmpty()) {
                label.blocks.forEach { block -> output.write(blockBytes(block)) }
            } else {
                writeLegacyGiftLabel(output, label)
            }
            // A short grip area after each print on a common 58 mm thermal
            // printer, enough paper to grip before tearing it off.
            output.write(EscPosCommands.tearOffFeed)
            output.write(EscPosCommands.feedAndPartialCut)
        }
        output.flush()
        PrintResult(PrintJobStatus.PRINTED)
    } catch (error: Exception) {
        dropBrokenLink(error)
        PrintResult(PrintJobStatus.FAILED, error.message ?: "Cetak gagal")
    }

    private fun writeSticker(output: java.io.OutputStream, image: StickerImage) {
        output.write(EscPosRaster.command(image.bytesPerRow, image.height, image.dots))
        val feedDots = image.feedDots
        if (feedDots == null) {
            output.write(EscPosCommands.nextLabel)
        } else {
            // ESC J n feeds n dots, at most 255 a time.
            var rest = feedDots
            while (rest > 0) {
                val step = rest.coerceAtMost(255)
                output.write(byteArrayOf(0x1B, 0x4A, step.toByte()))
                rest -= step
            }
        }
    }

    /**
     * Draws one block laid out by the server. Sizes, bold and alignment are
     * set per block and reset after it, so a block never leaks its style.
     */
    private fun blockBytes(block: PrintBlock): ByteArray = when (block) {
        is PrintBlock.Text ->
            alignCommand(block.align) +
                styled(block.text, block.size, block.bold) +
                newline
        is PrintBlock.Row -> {
            val usedColumns = block.left.length * block.leftSize.width +
                block.right.length * block.rightSize.width
            val gap = (NORMAL_LINE_CHARS - usedColumns).coerceAtLeast(1)
            EscPosCommands.leftAlign +
                styled(block.left, block.leftSize, block.bold) +
                " ".repeat(gap).toByteArray(PRINTER_CHARSET) +
                styled(block.right, block.rightSize, block.bold) +
                newline
        }
        is PrintBlock.Divider ->
            EscPosCommands.leftAlign +
                (if (block.isSolid) "-".repeat(NORMAL_LINE_CHARS) else "- ".repeat(NORMAL_LINE_CHARS / 2))
                    .toByteArray(PRINTER_CHARSET) +
                newline
        is PrintBlock.Cut ->
            EscPosCommands.leftAlign + block.text.toByteArray(PRINTER_CHARSET) + newline
        is PrintBlock.Feed -> "\n".repeat(block.lines).toByteArray(PRINTER_CHARSET)
        PrintBlock.Logo ->
            logoRaster?.let { EscPosCommands.leftAlign + it } ?: ByteArray(0)
    }

    private fun styled(text: String, size: PrintTextSize, bold: Boolean): ByteArray =
        (if (bold) EscPosCommands.boldOn else EscPosCommands.boldOff) +
            sizeCommand(size) +
            text.toByteArray(PRINTER_CHARSET) +
            EscPosCommands.sizeNormal +
            EscPosCommands.boldOff

    // GS ! n: high nibble = extra width, low nibble = extra height.
    private fun sizeCommand(size: PrintTextSize) =
        byteArrayOf(0x1D, 0x21, (((size.width - 1) shl 4) or (size.height - 1)).toByte())

    private fun alignCommand(align: PrintAlign) = when (align) {
        PrintAlign.LEFT -> EscPosCommands.leftAlign
        PrintAlign.CENTER -> EscPosCommands.centerAlign
        PrintAlign.RIGHT -> EscPosCommands.rightAlign
    }

    /** The gift label as printed before the server laid prints out. */
    private fun writeLegacyGiftLabel(output: java.io.OutputStream, label: GuestLabel) {
        output.write(EscPosCommands.leftAlign)
        // One fact per caption + value, running down the paper. The caption
        // stays small; the value is printed large so the couple can read an
        // envelope without picking it up. What identifies the guest (code,
        // name, table) is double width and height, the rest double height.
        labelFields(label).forEach { field ->
            output.write("${field.caption}\n".toByteArray(PRINTER_CHARSET))
            output.write(EscPosCommands.boldOn)
            output.write(if (field.isLarge) EscPosCommands.sizeLarge else EscPosCommands.sizeTall)
            val lineWidth = if (field.isLarge) LARGE_LINE_CHARS else NORMAL_LINE_CHARS
            wrapWords(field.value, lineWidth).forEach { line ->
                output.write("$line\n".toByteArray(PRINTER_CHARSET))
            }
            output.write(EscPosCommands.sizeNormal)
            output.write(EscPosCommands.boldOff)
        }
    }

    /**
     * Android says the Bluetooth link to [address] is gone (the printer was
     * switched off, its battery died, or it went out of range). The socket
     * itself still says "connected" until something is written, so without
     * this the desk kept showing a green printer.
     */
    fun onLinkLost(address: String?) {
        val isOurPrinter = synchronized(connectionLock) {
            address != null && connectedPrinter?.address == address
        }
        if (isOurPrinter) disconnect()
    }

    /** Bluetooth was switched off on the tablet: no printer can be reached. */
    fun onBluetoothOff() = disconnect()

    // A write that fails with an I/O error means the link is broken: drop it
    // so the status turns red at once and "Hubungkan printer" shows.
    private fun dropBrokenLink(error: Exception) {
        if (error is IOException) disconnect()
    }

    fun disconnect() {
        val socket = synchronized(connectionLock) {
            connectionGeneration += 1
            val existingSocket = bluetoothSocket
            bluetoothSocket = null
            connectedPrinter = null
            existingSocket
        }
        try {
            socket?.close()
        } catch (_: IOException) {
            // The printer may already have closed its connection.
        }
    }

    private fun requireOutputStream() = synchronized(connectionLock) { bluetoothSocket }
        ?.takeIf { socket -> socket.isConnected }
        ?.outputStream
        ?: throw IOException("Printer belum terhubung")

    /** The label's lines, top to bottom; a fact the guest lacks is left out. */
    private fun labelFields(label: GuestLabel): List<LabelField> = listOfNotNull(
        LabelField("Kode tamu", label.guestCode, isLarge = true),
        LabelField("Nama tamu", label.guestName, isLarge = true),
        listOfNotNull(label.side, label.category)
            .joinToString(" - ")
            .takeIf { it.isNotBlank() }
            ?.let { LabelField("Keterangan", it) },
        LabelField("Jumlah", "${label.pax} orang"),
        label.table?.let { LabelField("Meja", it, isLarge = true) },
    )

    private data class LabelField(
        val caption: String,
        val value: String,
        val isLarge: Boolean = false,
    )

    /**
     * Breaks a value at spaces so it fits the paper. The printer would wrap by
     * itself, but in the middle of a word; a word longer than a line is cut.
     */
    private fun wrapWords(value: String, lineWidth: Int): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        value.trim().split(' ').filter { it.isNotEmpty() }.forEach { word ->
            var rest = word
            while (rest.length > lineWidth) {
                if (line.isNotEmpty()) {
                    lines += line
                    line = ""
                }
                lines += rest.take(lineWidth)
                rest = rest.drop(lineWidth)
            }
            line = when {
                line.isEmpty() -> rest
                line.length + 1 + rest.length <= lineWidth -> "$line $rest"
                else -> {
                    lines += line
                    rest
                }
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    private fun disconnectedStatus(message: String) = PrinterStatus(
        available = bluetoothAdapter != null,
        connected = false,
        detail = message,
    )

    private object EscPosCommands {
        val initialize = byteArrayOf(0x1B, 0x40)
        val leftAlign = byteArrayOf(0x1B, 0x61, 0x00)
        val centerAlign = byteArrayOf(0x1B, 0x61, 0x01)
        val rightAlign = byteArrayOf(0x1B, 0x61, 0x02)
        val boldOn = byteArrayOf(0x1B, 0x45, 0x01)
        val boldOff = byteArrayOf(0x1B, 0x45, 0x00)

        // GS ! n: character size. High nibble = extra width, low = extra height.
        val sizeNormal = byteArrayOf(0x1D, 0x21, 0x00)
        val sizeTall = byteArrayOf(0x1D, 0x21, 0x01)
        val sizeLarge = byteArrayOf(0x1D, 0x21, 0x11)
        val tearOffFeed = "\n".repeat(4).toByteArray(PRINTER_CHARSET)
        val feedAndPartialCut = byteArrayOf(0x1D, 0x56, 0x01)

        // GS FF: feed to the start of the next label (label/gap mode).
        val nextLabel = byteArrayOf(0x1D, 0x0C)
    }

    private companion object {
        val SERIAL_PORT_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val PRINTER_CHARSET: Charset = Charsets.UTF_8

        // A 58 mm printer fits 32 characters a line; double width halves that.
        const val NORMAL_LINE_CHARS = 32
        const val LARGE_LINE_CHARS = 16
        val newline = "\n".toByteArray(PRINTER_CHARSET)
    }
}
