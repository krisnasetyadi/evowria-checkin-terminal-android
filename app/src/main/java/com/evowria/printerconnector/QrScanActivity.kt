package com.evowria.printerconnector

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scans the event's setup QR with our own camera screen.
 *
 * Replaces Google Code Scanner, whose scanner UI and model are downloaded by
 * Google Play services on first use: on a slow venue connection the camera
 * sometimes never opened. This uses the bundled ML Kit barcode model (inside
 * the APK) and CameraX, so it works offline and on tablets without Play
 * services. Returns RESULT_OK with [EXTRA_VALUE] = the QR's text, or
 * RESULT_CANCELED (closed, or the staff chose to type the event code).
 */
class QrScanActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var torchButton: Button
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorMessage: TextView
    private lateinit var analysisExecutor: ExecutorService
    private var camera: Camera? = null
    private var torchOn = false
    private val delivered = AtomicBoolean(false)
    private val scanner: BarcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }

    private val cameraPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startCamera()
        else showCameraError("Izin kamera belum diberikan. Izinkan kamera untuk aplikasi ini di Setelan, atau ketik kode acaranya.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        analysisExecutor = Executors.newSingleThreadExecutor()
        setContentView(buildLayout())
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionRequest.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        scanner.close()
        super.onDestroy()
    }

    private fun startCamera() {
        errorPanel.visibility = View.GONE
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                bindCamera(providerFuture.get())
            } catch (_: Exception) {
                showCameraError("Kamera sedang dipakai aplikasi lain atau tidak tersedia. Tutup aplikasi kamera lain lalu coba lagi, atau ketik kode acaranya.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        // Back camera by default; a tablet with only a front camera uses that.
        val selector = when {
            provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> {
                showCameraError("Perangkat ini tidak punya kamera yang bisa dipakai. Ketik kode acaranya.")
                return
            }
        }
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, ::analyze) }

        provider.unbindAll()
        camera = provider.bindToLifecycle(this, selector, preview, analysis)
        val hasTorch = camera?.cameraInfo?.hasFlashUnit() == true
        torchButton.visibility = if (hasTorch) View.VISIBLE else View.GONE
    }

    @SuppressLint("UnsafeOptInUsageError")
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || delivered.get()) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val value = barcodes.firstNotNullOfOrNull { it.rawValue?.trim()?.takeIf(String::isNotEmpty) }
                if (value != null && delivered.compareAndSet(false, true)) {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_VALUE, value))
                    finish()
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun toggleTorch() {
        val activeCamera = camera ?: return
        torchOn = !torchOn
        activeCamera.cameraControl.enableTorch(torchOn)
        torchButton.text = if (torchOn) "Matikan lampu" else "Nyalakan lampu"
    }

    private fun showCameraError(message: String) {
        errorMessage.text = message
        errorPanel.visibility = View.VISIBLE
        torchButton.visibility = View.GONE
    }

    private fun typeCodeInstead() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun buildLayout(): View {
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        val hint = TextView(this).apply {
            text = "Arahkan kamera ke QR acara dari PIC Evowria"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded("#99000000")
        }
        torchButton = pillButton("Nyalakan lampu", "#F5EEE9", "#2B211D").apply {
            visibility = View.GONE
            setOnClickListener { toggleTorch() }
        }
        val typeButton = pillButton("Ketik kode acara", "#F5EEE9", "#2B211D").apply {
            setOnClickListener { typeCodeInstead() }
        }
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(torchButton, wrap(end = 12))
            addView(typeButton, wrap())
        }

        errorMessage = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#756B65"))
            gravity = Gravity.CENTER
        }
        errorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = rounded("#FFFFFF")
            addView(TextView(context).apply {
                text = "Kamera tidak bisa dibuka"
                textSize = 20f
                setTextColor(Color.parseColor("#2B211D"))
                gravity = Gravity.CENTER
            })
            addView(errorMessage, wrap(top = 8))
            addView(pillButton("Ketik kode acara", "#9A472F", "#FFFFFF").apply {
                setOnClickListener { typeCodeInstead() }
            }, wrap(top = 16))
            addView(pillButton("Coba lagi", "#F5EEE9", "#2B211D").apply {
                setOnClickListener { retryCamera() }
            }, wrap(top = 8))
        }

        return FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(previewView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            addView(hint, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = dp(32) })
            addView(bottomBar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = dp(32) })
            addView(errorPanel, FrameLayout.LayoutParams(
                dp(420).coerceAtMost(resources.displayMetrics.widthPixels - dp(32)),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
        }
    }

    private fun retryCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionRequest.launch(Manifest.permission.CAMERA)
        }
    }

    private fun pillButton(label: String, fill: String, textColor: String) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.parseColor(textColor))
        setPadding(dp(20), dp(8), dp(20), dp(8))
        background = rounded(fill)
    }

    private fun rounded(fill: String) = GradientDrawable().apply {
        setColor(Color.parseColor(fill))
        cornerRadius = dp(14).toFloat()
    }

    private fun wrap(top: Int = 0, end: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = dp(top)
        marginEnd = dp(end)
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_VALUE = "com.evowria.printerconnector.QR_VALUE"
    }
}
