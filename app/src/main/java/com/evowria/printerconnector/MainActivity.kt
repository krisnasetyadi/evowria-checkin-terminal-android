package com.evowria.printerconnector

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebChromeClient
import android.webkit.PermissionRequest
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Hosts the web terminal and wires it to the native printer bridge. */
class MainActivity : ComponentActivity() {
    private lateinit var terminalWebView: WebView
    private lateinit var terminalUrlStore: TerminalUrlStore
    private lateinit var recentEventStore: RecentEventStore
    private lateinit var webPrinterBridge: WebPrinterBridge
    private var pendingCameraRequest: PermissionRequest? = null
    private val terminalQrScanner by lazy {
        GmsBarcodeScanning.getClient(
            this,
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build(),
        )
    }

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
        title = getString(R.string.app_name)
        // Dev builds can be inspected from chrome://inspect on the laptop.
        if (BuildConfig.LOCAL_SERVER_ALLOWED) WebView.setWebContentsDebuggingEnabled(true)

        terminalUrlStore = TerminalUrlStore(this)
        recentEventStore = RecentEventStore(this)
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
            showEventPicker()
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
                // Dev builds also allow http://localhost (via `adb reverse`),
                // which the WebView itself treats as a secure origin.
                val isSecureOrigin = request.origin.scheme == "https" ||
                    (BuildConfig.LOCAL_SERVER_ALLOWED && request.origin.host in LOCAL_HOSTS)
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
            openTerminal(url)
        }
            ?: showEventPicker()
    }

    private fun showEventPicker() {
        val serverInput = if (BuildConfig.LOCAL_SERVER_ALLOWED) {
            pickerInput(
                hint = "Alamat server, mis. http://192.168.1.5:3021",
                initialText = terminalUrlStore.getServerUrl(),
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            )
        } else {
            null
        }
        val searchInput = pickerInput(
            hint = "Cari nama pasangan atau kode acara",
            initialText = "",
            inputType = InputType.TYPE_CLASS_TEXT,
        )
        val eventList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scanQrButton = Button(this).apply {
            text = "Scan QR acara"
            isAllCaps = false
            setCompoundDrawablesWithIntrinsicBounds(
                android.R.drawable.ic_menu_camera,
                0,
                0,
                0,
            )
            compoundDrawablePadding = dp(8)
            setTextColor(Color.parseColor("#2B211D"))
            setPadding(dp(16), dp(6), dp(16), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F5EEE9"))
                cornerRadius = dp(14).toFloat()
            }
        }
        val setupContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            serverInput?.let { addView(it, matchWidth(bottom = 10)) }
            addView(scanQrButton, matchWidth())
            addView(searchInput, matchWidth(top = 10))
            addView(eventList, matchWidth(top = 10))
        }

        lateinit var setupDialog: android.app.Dialog

        // Returns the server to use, or null after flagging the server field.
        fun selectedServer(): String? {
            if (serverInput == null) return BuildConfig.DEFAULT_SERVER_URL
            val server = serverInput.text.toString().trim().trimEnd('/')
            val uri = Uri.parse(server)
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                serverInput.error = "Contoh: http://192.168.1.5:3021"
                return null
            }
            terminalUrlStore.saveServerUrl(server)
            return server
        }

        fun lookUpTypedCode(): Boolean {
            val server = selectedServer() ?: return false
            val eventCode = searchInput.text.toString().trim().lowercase()
            if (!isEventCode(eventCode)) {
                searchInput.error = "Pilih acara dari daftar atau ketik kode acara lengkap, mis. dananglidya"
                return false
            }
            validateAndConfirmEvent("$server/scan/$eventCode")
            return true
        }

        fun renderEventList() {
            eventList.removeAllViews()
            // Dev: only events from the server typed above. Prod: every saved
            // event, whatever HTTPS host its setup QR pointed at.
            val devServer = serverInput?.text?.toString()?.trim()?.trimEnd('/')
            val query = searchInput.text.toString().trim().lowercase()
            val matches = recentEventStore.list()
                .filter { devServer == null || it.terminalUrl.startsWith("$devServer/scan/") }
                .filter { event ->
                    query.isEmpty() ||
                        event.coupleNames.lowercase().contains(query) ||
                        event.code.contains(query)
                }
                .take(MAX_LISTED_EVENTS)

            matches.forEach { event ->
                val description = listOf(event.code, event.dateText)
                    .filter { it.isNotBlank() }
                    .joinToString(" • ")
                val row = EvowriaDialog.choiceRow(
                    this,
                    EvowriaDialog.Choice(event.coupleNames, description),
                ).apply {
                    setOnClickListener {
                        setupDialog.dismiss()
                        openTerminal(event.terminalUrl)
                    }
                }
                eventList.addView(row, matchWidth(bottom = 8))
            }

            if (isEventCode(query) && matches.none { it.code == query }) {
                val row = EvowriaDialog.choiceRow(
                    this,
                    EvowriaDialog.Choice("Cari kode \u201C$query\u201D", "Cek acara ini ke server"),
                ).apply {
                    setOnClickListener { if (lookUpTypedCode()) setupDialog.dismiss() }
                }
                eventList.addView(row, matchWidth(bottom = 8))
            }

            if (eventList.childCount == 0) {
                eventList.addView(TextView(this).apply {
                    text = if (query.isEmpty()) {
                        "Belum ada acara yang pernah dibuka di perangkat ini. Scan QR atau ketik kode acara."
                    } else {
                        "Tidak ada acara tersimpan yang cocok. Ketik kode acara lengkap untuk mencarinya."
                    }
                    textSize = 13f
                    setTextColor(Color.parseColor("#756B65"))
                })
            }
        }

        searchInput.doAfterTextChanged {
            searchInput.error = null
            renderEventList()
        }
        serverInput?.let { input ->
            input.doAfterTextChanged {
                input.error = null
                renderEventList()
            }
        }
        renderEventList()

        scanQrButton.setOnClickListener {
            setupDialog.dismiss()
            scanTerminalQr()
        }
        setupDialog = EvowriaDialog.showForm(
            context = this,
            eyebrow = if (BuildConfig.LOCAL_SERVER_ALLOWED) "Evowria check-in \u00B7 DEV" else "Evowria check-in",
            title = "Pilih acara",
            message = "Scan QR setup dari PIC Evowria, pilih acara yang pernah dibuka di perangkat ini, atau ketik kode acaranya. Nama pasangan akan ditampilkan sebelum meja dibuka.",
            content = setupContent,
            primaryLabel = "Cari acara",
            onPrimary = { _ -> lookUpTypedCode() },
            cancelable = terminalUrlStore.getUrl() != null,
        )
    }

    private fun pickerInput(hint: String, initialText: String, inputType: Int) = EditText(this).apply {
        this.hint = hint
        setText(initialText)
        setSelection(text.length)
        this.inputType = inputType
        setSingleLine(true)
        setTextColor(Color.parseColor("#2B211D"))
        setHintTextColor(Color.parseColor("#9A918B"))
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#F5EEE9"))
            cornerRadius = dp(14).toFloat()
        }
    }

    private fun matchWidth(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    private fun scanTerminalQr() {
        terminalQrScanner.startScan()
            .addOnSuccessListener { barcode ->
                val terminalUrl = barcode.rawValue?.trim().orEmpty()
                if (isTerminalUrl(terminalUrl)) {
                    validateAndConfirmEvent(terminalUrl)
                } else {
                    EvowriaDialog.showNotice(
                        context = this,
                        eyebrow = "QR tidak sesuai",
                        title = "Gunakan QR acara",
                        message = if (BuildConfig.LOCAL_SERVER_ALLOWED) {
                            "Scan QR yang berisi link http(s)://.../scan/kode-acara."
                        } else {
                            "Scan QR yang berisi link https://.../scan/kode-acara."
                        },
                    ).setOnDismissListener { showEventPicker() }
                }
            }
            .addOnCanceledListener { showEventPicker() }
            .addOnFailureListener {
                EvowriaDialog.showNotice(
                    context = this,
                    eyebrow = "Scanner belum siap",
                    title = "QR belum dapat dipindai",
                    message = "Pastikan Google Play services dan koneksi internet tersedia, lalu coba lagi.",
                ).setOnDismissListener { showEventPicker() }
            }
    }

    private fun validateAndConfirmEvent(terminalUrl: String) {
        val eventCode = eventCodeFromUrl(terminalUrl)
        if (eventCode == null) {
            showEventLookupError("Kode acara tidak valid.")
            return
        }
        Thread {
            try {
                val terminalUri = Uri.parse(terminalUrl)
                val lookupUri = terminalUri.buildUpon()
                    .path("/api/checkin/event")
                    .clearQuery()
                    .appendQueryParameter("code", eventCode)
                    .build()
                val connection = URL(lookupUri.toString()).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                val responseCode: Int
                val responseBody: String
                try {
                    responseCode = connection.responseCode
                    responseBody = (if (responseCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    })?.bufferedReader()?.use { it.readText() }.orEmpty()
                } finally {
                    connection.disconnect()
                }
                if (responseCode !in 200..299) {
                    Log.w(TAG, "Event lookup $lookupUri returned $responseCode: $responseBody")
                    val message = when (responseCode) {
                        404 -> "Acara dengan kode \"$eventCode\" tidak ditemukan. Periksa QR dari admin."
                        400 -> "Kode acara tidak valid."
                        429 -> "Terlalu banyak kode acara salah. Tunggu beberapa menit lalu coba lagi."
                        else -> "Server belum dapat memeriksa acara (kode $responseCode). Hubungi admin."
                    }
                    runOnUiThread { showEventLookupError(message) }
                    return@Thread
                }

                val event = JSONObject(responseBody).getJSONObject("event")
                val coupleNames = event.getString("coupleNames")
                val dateText = event.optString("dateText")
                val venue = event.optString("venue")
                runOnUiThread {
                    showEventConfirmation(terminalUrl, coupleNames, dateText, venue)
                }
            } catch (error: IOException) {
                Log.w(TAG, "Event lookup for $terminalUrl failed", error)
                runOnUiThread {
                    showEventLookupError("Server tidak dapat dihubungi. Periksa koneksi internet, lalu coba lagi.")
                }
            } catch (error: Exception) {
                Log.e(TAG, "Event lookup for $terminalUrl returned an unexpected response", error)
                runOnUiThread {
                    showEventLookupError("Jawaban server tidak dapat dibaca. Hubungi admin.")
                }
            }
        }.start()
    }

    private fun showEventConfirmation(
        terminalUrl: String,
        coupleNames: String,
        dateText: String,
        venue: String,
    ) {
        val description = listOf(dateText, venue)
            .filter { it.isNotBlank() }
            .joinToString(" • ")
        EvowriaDialog.showChoices(
            context = this,
            eyebrow = "Acara ditemukan",
            title = "Gunakan acara ini?",
            message = "Pastikan acara sesuai sebelum petugas memasukkan PIN.",
            choices = listOf(EvowriaDialog.Choice(coupleNames, description)),
            onSelected = {
                eventCodeFromUrl(terminalUrl)?.let { eventCode ->
                    recentEventStore.remember(
                        RecentEventStore.RecentEvent(
                            terminalUrl = terminalUrl,
                            code = eventCode,
                            coupleNames = coupleNames,
                            dateText = dateText,
                            venue = venue,
                            lastUsedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                openTerminal(terminalUrl)
            },
            onCancelled = ::showEventPicker,
        )
    }

    private fun showEventLookupError(message: String) {
        EvowriaDialog.showNotice(
            context = this,
            eyebrow = "Acara belum ditemukan",
            title = "Periksa kode acara",
            message = message,
        ).setOnDismissListener { showEventPicker() }
    }

    private fun openTerminal(terminalUrl: String) {
        terminalUrlStore.saveUrl(terminalUrl)
        terminalWebView.loadUrl(terminalUrl)
        // Terminal ini dipakai untuk check-in berkecepatan tinggi. Minta
        // operator memilih printer sebelum tamu pertama datang; mereka
        // tetap dapat membatalkan bila belum siap menyiapkan printer.
        terminalWebView.postDelayed({ webPrinterBridge.showPrinterPicker() }, 500)
    }

    private fun isTerminalUrl(value: String): Boolean {
        val uri = Uri.parse(value)
        val isAllowedScheme = uri.scheme == "https" ||
            (BuildConfig.LOCAL_SERVER_ALLOWED && uri.scheme == "http")
        return isAllowedScheme &&
            uri.host != null &&
            uri.path?.startsWith("/scan/") == true &&
            uri.pathSegments.getOrNull(1)?.isNotBlank() == true
    }

    private fun eventCodeFromUrl(value: String): String? {
        val uri = Uri.parse(value)
        return uri.pathSegments.getOrNull(1)?.takeIf(::isEventCode)
    }

    private fun isEventCode(value: String): Boolean =
        value.matches(Regex("^[a-z0-9][a-z0-9-]{1,79}$"))

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
        const val TAG = "EvowriaCheckin"
        const val MENU_TERMINAL_URL = "Ganti acara"
        const val MENU_PRINTER = "Pilih printer"
        const val MAX_LISTED_EVENTS = 6
        val LOCAL_HOSTS = setOf("localhost", "127.0.0.1")
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
