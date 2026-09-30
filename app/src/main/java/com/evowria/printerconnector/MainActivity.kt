package com.evowria.printerconnector

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebChromeClient
import android.webkit.PermissionRequest
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Hosts the web terminal and wires it to the native printer bridge. */
class MainActivity : ComponentActivity() {
    private lateinit var terminalWebView: WebView
    private lateinit var terminalUrlStore: TerminalUrlStore
    private lateinit var webPrinterBridge: WebPrinterBridge
    private var pendingCameraRequest: PermissionRequest? = null

    private val bluetoothPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionGranted ->
        if (permissionGranted) webPrinterBridge.continueConnectionAfterPermissionGranted()
        else webPrinterBridge.failPendingConnectionForMissingPermission()
    }

    private val cameraPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionGranted ->
        val request = pendingCameraRequest
        pendingCameraRequest = null
        if (permissionGranted && request != null) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        } else {
            request?.deny()
        }
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
        terminalWebView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // QR scanning only needs camera video. Do not expose other
                // WebView capabilities such as microphone capture.
                val requestsCamera = request.resources.contains(
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE,
                )
                val isSecureOrigin = request.origin.scheme == "https"
                if (!requestsCamera || !isSecureOrigin) {
                    request.deny()
                    return
                }

                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                    return
                }

                pendingCameraRequest?.deny()
                pendingCameraRequest = request
                cameraPermissionRequest.launch(Manifest.permission.CAMERA)
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingCameraRequest === request) pendingCameraRequest = null
                super.onPermissionRequestCanceled(request)
            }
        }
        terminalWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url
                val isWhatsAppSupportLink = url.host == "wa.me" ||
                    url.host == "api.whatsapp.com" ||
                    url.host?.endsWith(".whatsapp.com") == true
                if (!isWhatsAppSupportLink) return false

                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true
                } catch (_: ActivityNotFoundException) {
                    // If the device has no app/browser that can open the link,
                    // keep the terminal page intact rather than crashing.
                    false
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                installWebPrinterApi()
            }
        }
        terminalWebView.addJavascriptInterface(webPrinterBridge, NATIVE_BRIDGE_NAME)
    }

    private fun openSavedTerminal() {
        terminalUrlStore.getUrl()?.let { url ->
            terminalWebView.loadUrl(url)
            // Terminal ini dipakai untuk check-in berkecepatan tinggi. Minta
            // operator memilih printer sebelum tamu pertama datang; mereka
            // tetap dapat membatalkan bila belum siap menyiapkan printer.
            terminalWebView.postDelayed({ webPrinterBridge.showPrinterPicker() }, 500)
        }
            ?: showTerminalUrlDialog()
    }

    private fun showTerminalUrlDialog() {
        val urlInput = android.widget.EditText(this).apply {
            hint = "https://evowria.com/scan/event-slug"
            setText(terminalUrlStore.getUrl().orEmpty())
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setTextColor(Color.parseColor("#2B211D"))
            setHintTextColor(Color.parseColor("#9A918B"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F5EEE9"))
                cornerRadius = dp(14).toFloat()
            }
        }

        EvowriaDialog.showForm(
            context = this,
            eyebrow = "Evowria terminal",
            title = "Buka meja check-in",
            message = "Masukkan URL HTTPS halaman check-in untuk acara ini.",
            content = urlInput,
            primaryLabel = "Buka terminal",
            onPrimary = {
                _ ->
                val terminalUrl = urlInput.text.toString().trim()
                if (terminalUrl.startsWith("https://")) {
                    terminalUrlStore.saveUrl(terminalUrl)
                    terminalWebView.loadUrl(terminalUrl)
                    terminalWebView.postDelayed({ webPrinterBridge.showPrinterPicker() }, 500)
                    true
                } else {
                    urlInput.error = "Gunakan URL yang diawali https://"
                    false
                }
            },
            cancelable = terminalUrlStore.getUrl() != null,
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

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
