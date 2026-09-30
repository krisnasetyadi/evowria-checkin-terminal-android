package com.evowria.printerconnector

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The boundary between the web terminal and native Android printing. */
class WebPrinterBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val printer: BluetoothThermalPrinter,
    private val requestBluetoothPermission: () -> Unit,
) {
    private val printerExecutor = Executors.newSingleThreadExecutor()
    // A Bluetooth write can block at driver level. It must not occupy the
    // bridge queue after the five-second guest-facing deadline expires.
    private val printerIoExecutor = Executors.newCachedThreadPool()
    private var pendingConnectionRequestId: String? = null
    private var shouldOpenPrinterPickerAfterPermission = false

    @JavascriptInterface
    fun call(methodName: String, rawPayload: String, requestId: String) {
        printerExecutor.execute {
            try {
                when (methodName) {
                    METHOD_GET_STATUS -> send(requestId, printer.getStatus().toJson())
                    METHOD_CONNECT -> requestConnection(requestId)
                    METHOD_TEST_PRINT -> send(requestId, printer.printTestPage().toJson())
                    METHOD_PRINT_LABEL -> printLabel(requestId, rawPayload)
                    else -> sendError(requestId, "Metode printer tidak dikenal")
                }
            } catch (error: Exception) {
                sendError(
                    requestId,
                    error.message ?: "Printer tidak dapat menyelesaikan permintaan",
                )
            }
        }
    }

    fun showPrinterPicker() = activity.runOnUiThread {
        if (hasBluetoothConnectionPermission()) {
            choosePairedPrinter(null)
        } else {
            shouldOpenPrinterPickerAfterPermission = true
            requestBluetoothPermission()
        }
    }

    fun continueConnectionAfterPermissionGranted() {
        val requestId = pendingConnectionRequestId
        if (requestId != null) {
            pendingConnectionRequestId = null
            activity.runOnUiThread { choosePairedPrinter(requestId) }
        } else if (shouldOpenPrinterPickerAfterPermission) {
            shouldOpenPrinterPickerAfterPermission = false
            activity.runOnUiThread { choosePairedPrinter(null) }
        }
    }

    fun failPendingConnectionForMissingPermission() {
        pendingConnectionRequestId?.let { requestId ->
            pendingConnectionRequestId = null
            sendError(requestId, "Izin Bluetooth diperlukan untuk menghubungkan printer")
        }
        shouldOpenPrinterPickerAfterPermission = false
    }

    fun close() {
        printerExecutor.shutdownNow()
        printerIoExecutor.shutdownNow()
        printer.disconnect()
    }

    private fun requestConnection(requestId: String) {
        pendingConnectionRequestId = requestId
        activity.runOnUiThread { choosePairedPrinter(requestId) }
    }

    private fun printLabel(requestId: String, rawPayload: String) {
        try {
            val label = GuestLabel.fromJson(JSONObject(rawPayload))
            val printFuture = printerIoExecutor.submit<PrintResult> {
                printer.printGuestLabel(label)
            }
            val result = try {
                printFuture.get(PRINT_DEADLINE_SECONDS, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                // Closing the socket interrupts a blocked RFCOMM write. A late
                // label is worse than a recoverable reconnect at the door.
                printer.disconnect()
                printFuture.cancel(true)
                PrintResult(
                    PrintJobStatus.UNKNOWN,
                    "Cetak melebihi 5 detik. Periksa label fisik lalu hubungkan ulang printer.",
                )
            }
            send(requestId, result.toJson())
        } catch (_: Exception) {
            sendError(requestId, "Data label tidak valid")
        }
    }

    @SuppressLint("MissingPermission")
    private fun choosePairedPrinter(requestId: String?) {
        if (!hasBluetoothConnectionPermission()) {
            if (requestId != null) pendingConnectionRequestId = requestId
            requestBluetoothPermission()
            return
        }
        val pairedDevices = printer.getPairedDevices()
        if (pairedDevices.isEmpty()) {
            if (requestId != null) {
                sendError(requestId, "Tidak ada printer Bluetooth yang sudah dipasangkan")
            } else {
                EvowriaDialog.showNotice(
                    context = activity,
                    eyebrow = "Printer Bluetooth",
                    title = "Printer belum tersedia",
                    message = "Pasangkan printer lebih dulu dari Pengaturan Bluetooth Android, lalu coba lagi.",
                )
            }
            return
        }
        EvowriaDialog.showChoices(
            context = activity,
            eyebrow = "Printer Bluetooth",
            title = "Pilih printer",
            message = "Pilih printer yang sudah dipasangkan untuk meja check-in ini.",
            choices = pairedDevices.map { device ->
                EvowriaDialog.Choice(
                    title = device.name ?: "Thermal printer",
                    description = device.address,
                )
            },
            onSelected = { selectedIndex ->
                val selectedDevice = pairedDevices[selectedIndex]
                if (requestId == null) {
                    printerExecutor.execute { printer.connect(selectedDevice) }
                } else {
                    pendingConnectionRequestId = null
                    printerExecutor.execute {
                        send(requestId, printer.connect(selectedDevice).toJson())
                    }
                }
            },
            onCancelled = {
                requestId?.let { sendError(it, "Koneksi printer dibatalkan") }
            },
        )
    }

    private fun hasBluetoothConnectionPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun sendError(requestId: String, message: String) =
        send(requestId, JSONObject().put("error", message))

    private fun send(requestId: String, result: JSONObject) {
        val quotedRequestId = JSONObject.quote(requestId)
        val quotedResult = JSONObject.quote(result.toString())
        webView.post {
            webView.evaluateJavascript(
                "window.__evowriaNativeResolve($quotedRequestId, $quotedResult)",
                null,
            )
        }
    }

    private companion object {
        const val METHOD_GET_STATUS = "getPrinterStatus"
        const val METHOD_CONNECT = "connect"
        const val METHOD_TEST_PRINT = "testPrint"
        const val METHOD_PRINT_LABEL = "printGuestLabel"
        const val PRINT_DEADLINE_SECONDS = 5L
    }
}
