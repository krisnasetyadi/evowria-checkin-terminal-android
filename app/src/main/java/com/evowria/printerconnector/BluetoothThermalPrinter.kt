package com.evowria.printerconnector

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.nio.charset.Charset
import java.util.UUID

/**
 * Talks to a paired Bluetooth Classic printer using the common ESC/POS serial protocol.
 *
 * This class owns the socket. It does not show Android UI and does not know about WebView,
 * which keeps printer behaviour easy to test and replace for another printer protocol.
 */
class BluetoothThermalPrinter {
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val connectionLock = Any()

    @Volatile
    private var bluetoothSocket: BluetoothSocket? = null

    @Volatile
    private var connectedPrinter: BluetoothDevice? = null
    private var connectionGeneration = 0

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> =
        bluetoothAdapter?.bondedDevices?.sortedBy { device -> device.name ?: device.address }
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
            printerName = device?.name ?: device?.address,
            detail = if (isConnected) null else "Printer belum terhubung",
        )
    }

    fun printTestPage(): PrintResult = try {
        val output = requireOutputStream()
        output.write(EscPosCommands.initialize)
        output.write("Evowria test print\n\n\n".toByteArray(PRINTER_CHARSET))
        output.flush()
        PrintResult(PrintJobStatus.PRINTED)
    } catch (error: Exception) {
        PrintResult(PrintJobStatus.FAILED, error.message ?: "Test print gagal")
    }

    fun printGuestLabel(label: GuestLabel): PrintResult = try {
        val output = requireOutputStream()
        output.write(EscPosCommands.initialize)
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
        // A short grip area after each label on a common 58 mm thermal printer.
        // This gives the usher enough paper to grip before tearing the label.
        output.write(EscPosCommands.tearOffFeed)
        output.write(EscPosCommands.feedAndPartialCut)
        output.flush()
        PrintResult(PrintJobStatus.PRINTED)
    } catch (error: Exception) {
        PrintResult(PrintJobStatus.FAILED, error.message ?: "Cetak label gagal")
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
        val boldOn = byteArrayOf(0x1B, 0x45, 0x01)
        val boldOff = byteArrayOf(0x1B, 0x45, 0x00)

        // GS ! n: character size. High nibble = extra width, low = extra height.
        val sizeNormal = byteArrayOf(0x1D, 0x21, 0x00)
        val sizeTall = byteArrayOf(0x1D, 0x21, 0x01)
        val sizeLarge = byteArrayOf(0x1D, 0x21, 0x11)
        val tearOffFeed = "\n".repeat(4).toByteArray(PRINTER_CHARSET)
        val feedAndPartialCut = byteArrayOf(0x1D, 0x56, 0x01)
    }

    private companion object {
        val SERIAL_PORT_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val PRINTER_CHARSET: Charset = Charsets.UTF_8

        // A 58 mm printer fits 32 characters a line; double width halves that.
        const val NORMAL_LINE_CHARS = 32
        const val LARGE_LINE_CHARS = 16
    }
}
