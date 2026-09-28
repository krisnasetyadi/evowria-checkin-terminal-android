package com.evowria.printerconnector

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Hosts the web terminal and wires it to the native printer bridge. */
class MainActivity : ComponentActivity() {
    private lateinit var terminalWebView: WebView
    private lateinit var terminalUrlStore: TerminalUrlStore
    private lateinit var webPrinterBridge: WebPrinterBridge

    private val bluetoothPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionGranted ->
        if (permissionGranted) webPrinterBridge.continueConnectionAfterPermissionGranted()
        else webPrinterBridge.failPendingConnectionForMissingPermission()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Evowria Check-in Terminal"

        terminalUrlStore = TerminalUrlStore(this)
        terminalWebView = WebView(this)
        webPrinterBridge = WebPrinterBridge(
            activity = this,
            webView = terminalWebView,
            printer = BluetoothThermalPrinter(),
            requestBluetoothPermission = ::requestBluetoothPermission,
        )

        setContentView(terminalWebView)
        configureWebView()
        openSavedTerminal()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(MENU_TERMINAL_URL).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(MENU_PRINTER).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.title) {
        MENU_TERMINAL_URL -> {
            showTerminalUrlDialog()
            true
        }
        MENU_PRINTER -> {
            webPrinterBridge.showPrinterPicker()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        webPrinterBridge.close()
        terminalWebView.destroy()
        super.onDestroy()
    }

    private fun configureWebView() {
        terminalWebView.settings.javaScriptEnabled = true
        terminalWebView.settings.domStorageEnabled = true
        terminalWebView.webChromeClient = WebChromeClient()
        terminalWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                installWebPrinterApi()
            }
        }
        terminalWebView.addJavascriptInterface(webPrinterBridge, NATIVE_BRIDGE_NAME)
    }

    private fun openSavedTerminal() {
        terminalUrlStore.getUrl()?.let { url -> terminalWebView.loadUrl(url) }
            ?: showTerminalUrlDialog()
    }

    private fun showTerminalUrlDialog() {
        val urlInput = android.widget.EditText(this).apply {
            hint = "https://your-domain.com/scan/event-slug"
            setText(terminalUrlStore.getUrl().orEmpty())
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }

        AlertDialog.Builder(this)
            .setTitle("Terminal URL")
            .setMessage("Masukkan URL HTTPS halaman check-in untuk event ini.")
            .setView(urlInput)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Buka") { _, _ ->
                val terminalUrl = urlInput.text.toString().trim()
                if (terminalUrl.startsWith("https://")) {
                    terminalUrlStore.saveUrl(terminalUrl)
                    terminalWebView.loadUrl(terminalUrl)
                } else showTerminalUrlDialog()
            }
            .setCancelable(terminalUrlStore.getUrl() != null)
            .show()
    }

    private fun requestBluetoothPermission() {
        val bluetoothPermission = Manifest.permission.BLUETOOTH_CONNECT
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            checkSelfPermission(bluetoothPermission) == PackageManager.PERMISSION_GRANTED
        ) {
            webPrinterBridge.continueConnectionAfterPermissionGranted()
            return
        }
        bluetoothPermissionRequest.launch(bluetoothPermission)
    }

    private fun installWebPrinterApi() {
        terminalWebView.evaluateJavascript(WEB_PRINTER_API_SCRIPT, null)
    }

    private companion object {
        const val MENU_TERMINAL_URL = "Terminal URL"
        const val MENU_PRINTER = "Printer settings"
        const val NATIVE_BRIDGE_NAME = "AndroidPrinterConnector"

        val WEB_PRINTER_API_SCRIPT = """
            (() => {
              if (window.EvowriaPrinter) return;
              const callNativePrinter = (method, payload) => new Promise((resolve, reject) => {
                const requestId = `${'$'}{Date.now()}-${'$'}{Math.random()}`;
                window.__evowriaPrinterCallbacks = window.__evowriaPrinterCallbacks || {};
                window.__evowriaPrinterCallbacks[requestId] = { resolve, reject };
                AndroidPrinterConnector.call(method, JSON.stringify(payload || {}), requestId);
              });
              window.__evowriaNativeResolve = (requestId, rawResult) => {
                const callback = window.__evowriaPrinterCallbacks?.[requestId];
                if (!callback) return;
                delete window.__evowriaPrinterCallbacks[requestId];
                const result = JSON.parse(rawResult);
                if (result.error && !result.status) callback.reject(new Error(result.error));
                else callback.resolve(result);
              };
              window.EvowriaPrinter = {
                getPrinterStatus: () => callNativePrinter('getPrinterStatus'),
                connect: () => callNativePrinter('connect'),
                testPrint: () => callNativePrinter('testPrint'),
                printGuestLabel: (label) => callNativePrinter('printGuestLabel', label),
              };
            })();
        """.trimIndent()
    }
}
