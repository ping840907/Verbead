package com.ping.verbead.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.card.MaterialCardView
import com.ping.verbead.R
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.util.HapticUtil
import de.markusfisch.android.zxingcpp.ZxingCpp
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Controller for the Barcode/QR Scanner overlay in FloatingBubbleService.
 * Handles scanner view inflation, window positioning, pinch/slider zoom, flash toggle,
 * CameraX lifecycle binding, and Zxing barcode image analysis.
 */
class ScannerOverlayController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val windowManager: WindowManager,
    private val listener: Listener
) {

    interface Listener {
        fun onBarcodeDetected(code: String)
        fun onScannerClosed(hideX: Boolean)
        fun onShowXButton()
        fun onHideXButton()
        fun onRequestCameraPermission()
    }

    companion object {
        private const val TAG = "ScannerOverlayCtrl"
        private const val MODE_BARCODE = CapsuleMenuController.MODE_BARCODE
    }

    private var scannerView: View? = null
    private var scannerLayoutParams: WindowManager.LayoutParams? = null

    var isActive: Boolean = false
        private set

    var isEnlarged: Boolean = false
        private set

    private var isFlashOn: Boolean = false
    private var isProcessingBarcode: Boolean = false

    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    val previewView: PreviewView?
        get() = scannerView?.findViewById(R.id.scanner_view_finder)

        private val readerOptions = ZxingCpp.ReaderOptions(
        tryHarder = true,
        tryRotate = true,
        tryInvert = true,
        tryDownscale = true,
        maxNumberOfSymbols = 1,
        textMode = ZxingCpp.TextMode.PLAIN
    )

    fun start(
        anchorY: Int,
        isDockedOnRight: Boolean,
        screenWidth: Int,
        safeBottom: Int
    ) {
        if (isActive) return

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            listener.onRequestCameraPermission()
            return
        }

        if (scannerView == null) {
            scannerView = LayoutInflater.from(context).inflate(R.layout.layout_floating_scanner, null)
        }
        val scanner = scannerView ?: return

        val density = context.resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()
        val gap = (10 * density).toInt()

        isEnlarged = ModelConfig.isCameraEnlarged(context, MODE_BARCODE)
        val maxAvailableWidth = screenWidth - bubbleWidthPx - gap - (12 * density).toInt()
        val scannerWidth = if (isEnlarged) {
            (340 * density).toInt().coerceIn((200 * density).toInt(), maxAvailableWidth)
        } else {
            (250 * density).toInt().coerceAtMost(maxAvailableWidth)
        }
        val cardSize = scannerWidth - (16 * density).toInt()

        val estimatedHeight = cardSize + (120 * density).toInt()
        val micY = anchorY + (30 * density).toInt()
        val maxAllowedY = safeBottom - estimatedHeight - (8 * density).toInt()
        val targetY = (micY - (estimatedHeight / 2)).coerceIn(0, maxOf(0, maxAllowedY))

        scannerLayoutParams = WindowManager.LayoutParams(
            scannerWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (isDockedOnRight) Gravity.TOP or Gravity.RIGHT else Gravity.TOP or Gravity.LEFT
            x = bubbleWidthPx + gap
            y = targetY
        }

        val btnClose = scanner.findViewById<ImageButton>(R.id.btn_close_scanner)
        val btnScale = scanner.findViewById<ImageButton>(R.id.btn_scanner_scale)
        val btnFlash = scanner.findViewById<ImageButton>(R.id.btn_scanner_flash)
        val btnAutoEnter = scanner.findViewById<ImageButton>(R.id.btn_scanner_auto_enter)
        val sliderZoom = scanner.findViewById<SeekBar>(R.id.slider_scanner_zoom)
        val viewFinder = scanner.findViewById<PreviewView>(R.id.scanner_view_finder)
        val cardPreview = scanner.findViewById<MaterialCardView>(R.id.card_scanner_preview)

        btnScale?.setImageResource(if (isEnlarged) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
        cardPreview?.layoutParams = cardPreview?.layoutParams?.apply {
            width = cardSize
            height = cardSize
        }

        btnClose.setOnClickListener {
            HapticUtil.click(context)
            stop()
        }

        btnScale?.setOnClickListener {
            HapticUtil.click(context)
            setEnlarged(!isEnlarged, anchorY, isDockedOnRight, screenWidth, safeBottom)
        }

        btnFlash.setOnClickListener {
            HapticUtil.click(context)
            isFlashOn = !isFlashOn
            camera?.cameraControl?.enableTorch(isFlashOn)
            btnFlash.setImageResource(if (isFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        }

        val isAutoEnter = ModelConfig.isOcrAutoEnterEnabled(context)
        btnAutoEnter.setImageResource(if (isAutoEnter) R.drawable.ic_auto_enter_on else R.drawable.ic_auto_enter_off)
        btnAutoEnter.setOnClickListener {
            HapticUtil.click(context)
            val nextState = !ModelConfig.isOcrAutoEnterEnabled(context)
            ModelConfig.setOcrAutoEnterEnabled(context, nextState)
            btnAutoEnter.setImageResource(if (nextState) R.drawable.ic_auto_enter_on else R.drawable.ic_auto_enter_off)
            Toast.makeText(context, if (nextState) R.string.toast_auto_enter_enabled else R.string.toast_auto_enter_disabled, Toast.LENGTH_SHORT).show()
        }

        val savedZoom = ModelConfig.getCameraZoom(context, MODE_BARCODE)
        sliderZoom.progress = (savedZoom * 100f).roundToInt().coerceIn(0, 100)

        sliderZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val zoom = progress / 100f
                    camera?.cameraControl?.setLinearZoom(zoom)
                    ModelConfig.setCameraZoom(context, MODE_BARCODE, zoom)
                    HapticUtil.tick(context)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Float out smoothly from the side of the bubble
        if (isDockedOnRight) {
            scanner.pivotX = scannerWidth.toFloat()
            scanner.translationX = (40 * density)
        } else {
            scanner.pivotX = 0f
            scanner.translationX = -(40 * density)
        }
        scanner.pivotY = (140 * density)
        scanner.alpha = 0f
        scanner.scaleX = 0.7f
        scanner.scaleY = 0.7f

        try {
            windowManager.addView(scanner, scannerLayoutParams)
            isActive = true
            listener.onShowXButton()

            scanner.animate()
                .alpha(1f)
                .translationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(240)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .start()

            startCameraForScanner(viewFinder)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add scannerView", e)
        }
    }

    fun stop(
        hideX: Boolean = true,
        isDockedOnRight: Boolean = false
    ) {
        val scanner = scannerView ?: return
        if (!isActive) return
        isActive = false
        isFlashOn = false

        if (hideX) {
            listener.onHideXButton()
        }

        try {
            cameraProvider?.unbindAll()
            camera = null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unbind cameraProvider in stop", e)
        }

        val density = context.resources.displayMetrics.density
        val targetTranslationX = if (isDockedOnRight) (40 * density) else -(40 * density)
        scanner.animate()
            .alpha(0f)
            .translationX(targetTranslationX)
            .scaleX(0.7f)
            .scaleY(0.7f)
            .setDuration(180)
            .withEndAction {
                if (scanner.isAttachedToWindow) {
                    try {
                        windowManager.removeView(scanner)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to remove scannerView", e)
                    }
                }
                scannerView = null
                listener.onScannerClosed(hideX)
            }
            .start()
    }

    fun toggle(
        anchorY: Int,
        isDockedOnRight: Boolean,
        screenWidth: Int,
        safeBottom: Int
    ) {
        if (isActive) {
            stop(hideX = true, isDockedOnRight = isDockedOnRight)
        } else {
            start(anchorY, isDockedOnRight, screenWidth, safeBottom)
        }
    }

    fun setEnlarged(
        enlarged: Boolean,
        anchorY: Int,
        isDockedOnRight: Boolean,
        screenWidth: Int,
        safeBottom: Int
    ) {
        isEnlarged = enlarged
        ModelConfig.setCameraEnlarged(context, MODE_BARCODE, enlarged)
        val scanner = scannerView ?: return
        val lp = scannerLayoutParams ?: return
        val density = context.resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()
        val gap = (10 * density).toInt()

        val maxAvailableWidth = screenWidth - bubbleWidthPx - gap - (12 * density).toInt()
        val targetWidth = if (enlarged) {
            (340 * density).toInt().coerceIn((200 * density).toInt(), maxAvailableWidth)
        } else {
            (250 * density).toInt().coerceAtMost(maxAvailableWidth)
        }

        val cardSize = targetWidth - (16 * density).toInt()
        val cardPreview = scanner.findViewById<MaterialCardView>(R.id.card_scanner_preview)
        cardPreview?.layoutParams?.apply {
            width = cardSize
            height = cardSize
        }
        cardPreview?.requestLayout()

        lp.width = targetWidth
        val btnScale = scanner.findViewById<ImageButton>(R.id.btn_scanner_scale)
        btnScale?.setImageResource(if (enlarged) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)

        val estimatedHeight = cardSize + (120 * density).toInt()
        val micY = anchorY + (30 * density).toInt()
        val maxAllowedY = safeBottom - estimatedHeight - (8 * density).toInt()
        val targetY = (micY - (estimatedHeight / 2)).coerceIn(0, maxOf(0, maxAllowedY))
        lp.y = targetY
        lp.gravity = if (isDockedOnRight) (Gravity.TOP or Gravity.RIGHT) else (Gravity.TOP or Gravity.LEFT)
        lp.x = bubbleWidthPx + gap

        if (scanner.isAttachedToWindow) {
            try {
                windowManager.updateViewLayout(scanner, lp)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update scanner layout on setEnlarged", e)
            }
        }
    }

    fun updatePosition(
        anchorY: Int,
        isDockedOnRight: Boolean,
        screenWidth: Int,
        safeBottom: Int
    ) {
        val scanner = scannerView ?: return
        val lp = scannerLayoutParams ?: return
        if (!isActive || !scanner.isAttachedToWindow) return
        val density = context.resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()
        val gap = (10 * density).toInt()

        val maxAvailableWidth = screenWidth - bubbleWidthPx - gap - (12 * density).toInt()
        val targetWidth = if (isEnlarged) {
            (340 * density).toInt().coerceIn((200 * density).toInt(), maxAvailableWidth)
        } else {
            (250 * density).toInt().coerceAtMost(maxAvailableWidth)
        }
        if (lp.width != targetWidth) {
            lp.width = targetWidth
            val cardSize = targetWidth - (16 * density).toInt()
            val cardPreview = scanner.findViewById<MaterialCardView>(R.id.card_scanner_preview)
            cardPreview?.layoutParams?.apply {
                width = cardSize
                height = cardSize
            }
            cardPreview?.requestLayout()
        }

        val cardSize = lp.width - (16 * density).toInt()
        val estimatedHeight = cardSize + (120 * density).toInt()
        val micY = anchorY + (30 * density).toInt()
        val maxAllowedY = safeBottom - estimatedHeight - (8 * density).toInt()
        lp.y = (micY - (estimatedHeight / 2)).coerceIn(0, maxOf(0, maxAllowedY))
        lp.gravity = if (isDockedOnRight) (Gravity.TOP or Gravity.RIGHT) else (Gravity.TOP or Gravity.LEFT)
        lp.x = bubbleWidthPx + gap
        try {
            windowManager.updateViewLayout(scanner, lp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update scanner window layout", e)
        }
    }

    fun destroy() {
        try {
            cameraProvider?.unbindAll()
            camera = null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unbind camera in destroy", e)
        }
        cameraExecutor?.shutdown()
        cameraExecutor = null

        val scanner = scannerView
        if (scanner != null && scanner.isAttachedToWindow) {
            try {
                windowManager.removeView(scanner)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove scannerView in destroy", e)
            }
        }
        scannerView = null
        isActive = false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPinchToZoom(viewFinder: PreviewView, sliderZoom: SeekBar) {
        val scaleGestureDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val currentZoom = camera?.cameraInfo?.zoomState?.value?.linearZoom
                        ?: (sliderZoom.progress / 100f)
                    val delta = (detector.scaleFactor - 1.0f) * 1.5f
                    val newZoom = (currentZoom + delta).coerceIn(0f, 1f)
                    camera?.cameraControl?.setLinearZoom(newZoom)
                    sliderZoom.progress = (newZoom * 100f).roundToInt().coerceIn(0, 100)
                    ModelConfig.setCameraZoom(context, MODE_BARCODE, newZoom)
                    return true
                }
            }
        )
        viewFinder.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun startCameraForScanner(viewFinder: PreviewView) {
        if (cameraExecutor == null || cameraExecutor?.isShutdown == true) {
            cameraExecutor = Executors.newSingleThreadExecutor()
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor!!) { imageProxy ->
                    analyzeBarcodeImage(imageProxy)
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                val savedZoom = ModelConfig.getCameraZoom(context, MODE_BARCODE)
                camera?.cameraControl?.setLinearZoom(savedZoom)
                val sliderZoom = scannerView?.findViewById<SeekBar>(R.id.slider_scanner_zoom)
                if (sliderZoom != null) {
                    setupPinchToZoom(viewFinder, sliderZoom)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera initialization failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyzeBarcodeImage(imageProxy: ImageProxy) {
        if (isProcessingBarcode || !isActive) {
            imageProxy.close()
            return
        }

        try {
            val yPlane = imageProxy.planes[0]
            val yBuffer = yPlane.buffer
            val rowStride = yPlane.rowStride
            val rotation = imageProxy.imageInfo.rotationDegrees
            val cropRect = Rect(0, 0, imageProxy.width, imageProxy.height)

            val results = ZxingCpp.readYBuffer(yBuffer, rowStride, cropRect, rotation, readerOptions)
            val result = results?.firstOrNull()
            if (result != null && result.text.isNotBlank()) {
                isProcessingBarcode = true
                Handler(Looper.getMainLooper()).post {
                    listener.onBarcodeDetected(result.text)
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    isProcessingBarcode = false
                }, 800)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Barcode decode error: ${e.message}", e)
        } finally {
            imageProxy.close()
        }
    }
}
