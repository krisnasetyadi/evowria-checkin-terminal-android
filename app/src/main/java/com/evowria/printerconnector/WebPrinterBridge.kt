package com.evowria.printerconnector

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.util.concurrent.Executors

/** The boundary between the web terminal and native Android printing. */
class WebPrinterBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val printer: BluetoothThermalPrinter,
    private val requestBluetoothPermission: () -> Unit,
) {
    private val printerExecutor = Executors.newSingleThreadExecutor()
    private var pendingConnectionRequestId: String? = null
    private var shouldOpenPrinterPickerAfterPermission = false

    @JavascriptInterface
    fun call(methodName: String, rawPayload: String, requestId: String) {
        printerExecutor.execute {
            when (methodName) {
                METHOD_GET_STATUS -> send(requestId, printer.getStatus().toJson())
                METHOD_CONNECT -> requestConnection(requestId)
                METHOD_TEST_PRINT -> send(requestId, printer.printTestPage().toJson())
                METHOD_PRINT_LABEL -> printLabel(requestId, rawPayload)
                else -> sendError(requestId, "Metode printer tidak dikenal")
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
        printer.disconnect()
    }

    private fun requestConnection(requestId: String) {
        pendingConnectionRequestId = requestId
        activity.runOnUiThread { choosePairedPrinter(requestId) }
    }

    private fun printLabel(requestId: String, rawPayload: String) {
        try {
            val label = GuestLabel.fromJson(JSONObject(rawPayload))
            send(requestId, printer.printGuestLabel(label).toJson())
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
            requestId?.let { sendError(it, "Tidak ada printer Bluetooth yang sudah dipasangkan") }
            return
        }
        val deviceNames = pairedDevices.map { device ->
            "${device.name ?: "Thermal printer"}\n${device.address}"
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Pilih printer Bluetooth")
            .setItems(deviceNames) { _, selectedIndex ->
                val selectedDevice = pairedDevices[selectedIndex]
                if (requestId == null) {
                    printerExecutor.execute { printer.connect(selectedDevice) }
                } else {
                    pendingConnectionRequestId = null
                    printerExecutor.execute { send(requestId, printer.connect(selectedDevice).toJson()) }
                }
            }
            .setNegativeButton("Batal") { _, _ ->
                requestId?.let { sendError(it, "Koneksi printer dibatalkan") }
            }
            .show()
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
    }
}
