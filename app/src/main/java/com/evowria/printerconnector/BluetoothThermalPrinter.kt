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
    private var bluetoothSocket: BluetoothSocket? = null
    private var connectedPrinter: BluetoothDevice? = null

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> =
        bluetoothAdapter?.bondedDevices?.sortedBy { device -> device.name ?: device.address }
            ?: emptyList()

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): PrinterStatus {
        disconnect()
        if (bluetoothAdapter == null) return disconnectedStatus("Bluetooth tidak tersedia di perangkat ini")
        if (!bluetoothAdapter.isEnabled) return disconnectedStatus("Bluetooth belum aktif")

        return try {
            val socket = device.createRfcommSocketToServiceRecord(SERIAL_PORT_UUID)
            bluetoothAdapter.cancelDiscovery()
            socket.connect()
            bluetoothSocket = socket
            connectedPrinter = device
            getStatus()
        } catch (error: IOException) {
            disconnect()
            disconnectedStatus(error.message ?: "Tidak dapat terhubung ke printer")
        }
    }

    @SuppressLint("MissingPermission")
    fun getStatus(): PrinterStatus {
        val isConnected = bluetoothSocket?.isConnected == true
        return PrinterStatus(
            available = bluetoothAdapter != null,
            connected = isConnected,
            printerName = connectedPrinter?.name ?: connectedPrinter?.address,
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
        output.write(EscPosCommands.centerAlign)
        output.write("ID TAMU: ${label.guestCode}\n".toByteArray(PRINTER_CHARSET))
        output.write(EscPosCommands.boldOn)
        output.write("${label.guestName}\n".toByteArray(PRINTER_CHARSET))
        output.write(EscPosCommands.boldOff)
        output.write(labelDetails(label).toByteArray(PRINTER_CHARSET))
        output.write(EscPosCommands.feedAndPartialCut)
        output.flush()
        PrintResult(PrintJobStatus.PRINTED)
    } catch (error: Exception) {
        PrintResult(PrintJobStatus.FAILED, error.message ?: "Cetak label gagal")
    }

    fun disconnect() {
        try {
            bluetoothSocket?.close()
        } catch (_: IOException) {
            // The printer may already have closed its connection.
        }
        bluetoothSocket = null
        connectedPrinter = null
    }

    private fun requireOutputStream() = bluetoothSocket
        ?.takeIf { socket -> socket.isConnected }
        ?.outputStream
        ?: throw IOException("Printer belum terhubung")

    private fun labelDetails(label: GuestLabel): String {
        val details = listOfNotNull(label.side, label.category, "${label.pax} pax")
        return details.joinToString(" | ") + "\n\n"
    }

    private fun disconnectedStatus(message: String) = PrinterStatus(
        available = bluetoothAdapter != null,
        connected = false,
        detail = message,
    )

    private object EscPosCommands {
        val initialize = byteArrayOf(0x1B, 0x40)
        val centerAlign = byteArrayOf(0x1B, 0x61, 0x01)
        val boldOn = byteArrayOf(0x1B, 0x45, 0x01)
        val boldOff = byteArrayOf(0x1B, 0x45, 0x00)
        val feedAndPartialCut = byteArrayOf(0x1D, 0x56, 0x01)
    }

    private companion object {
        val SERIAL_PORT_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val PRINTER_CHARSET: Charset = Charsets.UTF_8
    }
}
