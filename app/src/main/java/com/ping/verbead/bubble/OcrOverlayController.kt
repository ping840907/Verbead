package com.ping.verbead.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.card.MaterialCardView
import com.ping.verbead.ImeSettingsActivity
import com.ping.verbead.R
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.ocr.OcrBlock
import com.ping.verbead.ocr.OcrBoxesOverlayView
import com.ping.verbead.ocr.PpOcrEngine
import com.ping.verbead.ocr.TextStitcher
import com.ping.verbead.util.HapticUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Controller for the PP-OCR live viewfinder window and snapshot analysis overlay in FloatingBubbleService.
 * Handles OCR camera preview, pinch/slider zoom, flash toggle, snapshot freezing,
 * PP-OCR text block detection/recognition, multi-select box stitching, and text confirmation callbacks.
 */
class OcrOverlayController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val windowManager: WindowManager,
    private val scope: CoroutineScope,
    private val fallbackPreviewProvider: () -> PreviewView?,
    private val listener: Listener
) {

    interface Listener {
        fun onOcrTextConfirmed(text: String)
        fun onOcrClosed(hideX: Boolean)
        fun onSnapshotDismissed(hideX: Boolean)
        fun onShowXButton()
        fun onHideXButton()
        fun onRequestCameraPermission()
    }

    companion object {
        private const val TAG = "OcrOverlayController"
        private const val MODE_OCR = CapsuleMenuController.MODE_OCR
    }

    private val ppOcrEngine by lazy { PpOcrEngine(context) }

    private var ocrWindowView: View? = null
    private var ocrWindowLayoutParams: WindowManager.LayoutParams? = null

    private var ocrSnapshotView: View? = null
    private var ocrSnapshotLayoutParams: WindowManager.LayoutParams? = null
    private var currentSnapshotBitmap: Bitmap? = null

    var isModeActive: Boolean = false
        private set

    var isSnapshotActive: Boolean = false
        private set

    var isEnlarged: Boolean = false
        private set

    private var isFlashOn: Boolean = false

    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    fun start(
        anchorY: Int,
        isDockedOnRight: Boolean,
        screenWidth: Int,
        safeBottom: Int
    ) {
        if (isModeActive) return

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            listener.onRequestCameraPermission()
            return
        }

        if (ocrWindowView == null) {
            ocrWindowView = LayoutInflater.from(context).inflate(R.layout.layout_floating_ocr, null)
        }
        val ocr = ocrWindowView ?: return

        val density = context.resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()
        val gap = (10 * density).toInt()

        isEnlarged = ModelConfig.isCameraEnlarged(context, MODE_OCR)
        val maxAvailableWidth = screenWidth - bubbleWidthPx - gap - (12 * density).toInt()
        val ocrWidth = if (isEnlarged) {
            (340 * density).toInt().coerceIn((200 * density).toInt(), maxAvailableWidth)
        } else {
            (250 * density).toInt().coerceAtMost(maxAvailableWidth)
        }

        val cardSize = ocrWidth - (16 * density).toInt()
        val estimatedHeight = cardSize + (120 * density).toInt()
        val micY = anchorY + (30 * density).toInt()
        val maxAllowedY = safeBottom - estimatedHeight - (8 * density).toInt()
        val targetY = (micY - (estimatedHeight / 2)).coerceIn(0, maxOf(0, maxAllowedY))

        ocrWindowLayoutParams = WindowManager.LayoutParams(
            ocrWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (isDockedOnRight) Gravity.TOP or Gravity.RIGHT else Gravity.TOP or Gravity.LEFT
            x = bubbleWidthPx + gap
            y = targetY
        }

        val btnClose = ocr.findViewById<ImageButton>(R.id.btn_close_ocr)
        val btnScale = ocr.findViewById<ImageButton>(R.id.btn_ocr_scale)
        val btnFlash = ocr.findViewById<ImageButton>(R.id.btn_ocr_flash)
        val btnAutoEnter = ocr.findViewById<ImageButton>(R.id.btn_ocr_auto_enter)
        val btnShutter = ocr.findViewById<FrameLayout>(R.id.btn_ocr_shutter)
        val sliderZoom = ocr.findViewById<SeekBar>(R.id.slider_ocr_zoom)
        val viewFinder = ocr.findViewById<PreviewView>(R.id.ocr_view_finder)
        val cardPreview = ocr.findViewById<MaterialCardView>(R.id.card_ocr_preview)

        btnScale.setImageResource(if (isEnlarged) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
        cardPreview.layoutParams = cardPreview.layoutParams.apply {
            width = cardSize
            height = cardSize
        }

        btnClose.setOnClickListener {
            HapticUtil.click(context)
            stop()
        }

        btnScale.setOnClickListener {
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

        val savedZoom = ModelConfig.getCameraZoom(context, MODE_OCR)
        sliderZoom.progress = (savedZoom * 100f).roundToInt().coerceIn(0, 100)

        sliderZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val zoom = progress / 100f
                    camera?.cameraControl?.setLinearZoom(zoom)
                    ModelConfig.setCameraZoom(context, MODE_OCR, zoom)
                    HapticUtil.tick(context)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnShutter.setOnClickListener {
            HapticUtil.heavyClick(context)
            if (!isEnlarged) {
                setEnlarged(true, anchorY, isDockedOnRight, screenWidth, safeBottom)
            }
            triggerSnapshot()
        }

        if (isDockedOnRight) {
            ocr.pivotX = ocrWidth.toFloat()
            ocr.translationX = (40 * density)
        } else {
            ocr.pivotX = 0f
            ocr.translationX = -(40 * density)
        }
        ocr.pivotY = (140 * density)
        ocr.alpha = 0f
        ocr.scaleX = 0.7f
        ocr.scaleY = 0.7f

        try {
            windowManager.addView(ocr, ocrWindowLayoutParams)
            isModeActive = true
            listener.onShowXButton()

            ocr.animate()
                .alpha(1f)
                .translationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(240)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .start()

            startCameraForOcr(viewFinder)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add ocrWindowView", e)
        }
    }

    fun stop(
        hideX: Boolean = true,
        isDockedOnRight: Boolean = false
    ) {
        val ocr = ocrWindowView ?: return
        if (!isModeActive) return
        isModeActive = false
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
        ocr.animate()
            .alpha(0f)
            .translationX(targetTranslationX)
            .scaleX(0.7f)
            .scaleY(0.7f)
            .setDuration(180)
            .withEndAction {
                if (ocr.isAttachedToWindow) {
                    try {
                        windowManager.removeView(ocr)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to remove ocrWindowView", e)
                    }
                }
                ocrWindowView = null
                listener.onOcrClosed(hideX)
            }
            .start()
    }

    fun toggle(
        anchorY: Int,
        isDockedOnRight: Boolean,
        screenWidth: Int,
        safeBottom: Int
    ) {
        if (isModeActive) {
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
        ModelConfig.setCameraEnlarged(context, MODE_OCR, enlarged)
        val ocr = ocrWindowView ?: return
        val lp = ocrWindowLayoutParams ?: return
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
        val cardPreview = ocr.findViewById<MaterialCardView>(R.id.card_ocr_preview)
        cardPreview?.layoutParams?.apply {
            width = cardSize
            height = cardSize
        }
        cardPreview?.requestLayout()

        lp.width = targetWidth
        val btnScale = ocr.findViewById<ImageButton>(R.id.btn_ocr_scale)
        btnScale?.setImageResource(if (enlarged) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)

        val estimatedHeight = cardSize + (120 * density).toInt()
        val micY = anchorY + (30 * density).toInt()
        val maxAllowedY = safeBottom - estimatedHeight - (8 * density).toInt()
        val targetY = (micY - (estimatedHeight / 2)).coerceIn(0, maxOf(0, maxAllowedY))
        lp.y = targetY
        lp.gravity = if (isDockedOnRight) (Gravity.TOP or Gravity.RIGHT) else (Gravity.TOP or Gravity.LEFT)
        lp.x = bubbleWidthPx + gap

        if (ocr.isAttachedToWindow) {
            try {
                windowManager.updateViewLayout(ocr, lp)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update OCR window layout on setEnlarged", e)
            }
        }
    }

    fun updatePosition(
        anchorY: Int,
        isDockedOnRight: Boolean,
        screenWidth: Int,
        safeBottom: Int
    ) {
        val ocr = ocrWindowView ?: return
        val lp = ocrWindowLayoutParams ?: return
        if (!isModeActive || !ocr.isAttachedToWindow) return
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
            val cardPreview = ocr.findViewById<MaterialCardView>(R.id.card_ocr_preview)
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
            windowManager.updateViewLayout(ocr, lp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update OCR window layout", e)
        }
    }

    fun triggerSnapshot() {
        if (!ModelConfig.isOcrReady(context)) {
            Toast.makeText(context, R.string.toast_ocr_model_not_downloaded, Toast.LENGTH_LONG).show()
            val intent = Intent(context, ImeSettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return
        }

        val viewFinder = ocrWindowView?.findViewById<PreviewView>(R.id.ocr_view_finder)
            ?: fallbackPreviewProvider()
            ?: return

        val rawBitmap = viewFinder.bitmap ?: run {
            Toast.makeText(context, R.string.toast_camera_capture_failed, Toast.LENGTH_SHORT).show()
            return
        }

        val maxDim = 1280
        val bitmap = if (rawBitmap.width > maxDim || rawBitmap.height > maxDim) {
            val scale = maxDim.toFloat() / maxOf(rawBitmap.width, rawBitmap.height)
            val newW = (rawBitmap.width * scale).toInt()
            val newH = (rawBitmap.height * scale).toInt()
            val scaled = Bitmap.createScaledBitmap(rawBitmap, newW, newH, true)
            if (scaled !== rawBitmap) {
                rawBitmap.recycle()
            }
            scaled
        } else {
            rawBitmap
        }

        showSnapshotView(bitmap)
    }

    private fun showSnapshotView(bitmap: Bitmap) {
        currentSnapshotBitmap?.let {
            if (!it.isRecycled) it.recycle()
        }
        currentSnapshotBitmap = bitmap

        if (ocrSnapshotView == null) {
            ocrSnapshotView = LayoutInflater.from(context).inflate(R.layout.layout_ocr_snapshot, null)
        }
        val ocrView = ocrSnapshotView ?: return

        ocrSnapshotLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )

        val ivSnapshot = ocrView.findViewById<ImageView>(R.id.iv_ocr_snapshot)
        val boxesOverlay = ocrView.findViewById<OcrBoxesOverlayView>(R.id.view_ocr_boxes)
        val btnCloseOcr = ocrView.findViewById<ImageButton>(R.id.btn_close_ocr)
        val btnRotateOcr = ocrView.findViewById<ImageButton>(R.id.btn_rotate_ocr)
        val tvStatus = ocrView.findViewById<TextView>(R.id.tv_ocr_status)
        val progress = ocrView.findViewById<ProgressBar>(R.id.progress_ocr)
        val frameContainer = ocrView.findViewById<View>(R.id.container_snapshot_frame)
        val bottomBar = ocrView.findViewById<LinearLayout>(R.id.layout_ocr_bottom_bar)
        val btnSelectAll = ocrView.findViewById<TextView>(R.id.btn_ocr_select_all)
        val btnClear = ocrView.findViewById<TextView>(R.id.btn_ocr_clear)
        val btnConfirm = ocrView.findViewById<TextView>(R.id.btn_ocr_confirm)

        ivSnapshot.setImageBitmap(bitmap)
        boxesOverlay.clearBoxes()
        bottomBar.visibility = View.GONE
        tvStatus.text = "正在偵測文字區塊…"
        progress.visibility = View.VISIBLE

        btnCloseOcr.setOnClickListener {
            HapticUtil.click(context)
            dismissSnapshot()
        }

        boxesOverlay.onSelectionChanged = { selectedIndices ->
            val count = selectedIndices.size
            if (count > 0) {
                if (bottomBar.visibility != View.VISIBLE) {
                    bottomBar.visibility = View.VISIBLE
                    bottomBar.alpha = 0f
                    bottomBar.translationY = 30f
                    bottomBar.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(160)
                        .start()
                }
                tvStatus.text = "已選取 $count 個區塊 (按序號組合)，點「辨識並輸入」"
                btnConfirm.text = if (count == 1) "辨識並輸入" else "辨識並輸入 ($count)"
            } else {
                if (bottomBar.visibility == View.VISIBLE) {
                    bottomBar.animate()
                        .alpha(0f)
                        .translationY(30f)
                        .setDuration(140)
                        .withEndAction { bottomBar.visibility = View.GONE }
                        .start()
                }
                tvStatus.text = "滑動拉框圈選或點選欲輸入之文字區塊"
            }
        }

        boxesOverlay.onQuickConfirm = { selectedBox, _ ->
            HapticUtil.click(context)
            val currentBitmap = currentSnapshotBitmap ?: bitmap
            onOcrBoxesSelected(currentBitmap, listOf(selectedBox), tvStatus, progress)
        }

        btnSelectAll.setOnClickListener {
            HapticUtil.click(context)
            boxesOverlay.selectAll()
        }

        btnClear.setOnClickListener {
            HapticUtil.click(context)
            boxesOverlay.clearSelection()
        }

        btnConfirm.setOnClickListener {
            HapticUtil.click(context)
            val selectedBoxes = boxesOverlay.getSelectedBoxes()
            if (selectedBoxes.isNotEmpty()) {
                val currentBitmap = currentSnapshotBitmap ?: bitmap
                onOcrBoxesSelected(currentBitmap, selectedBoxes, tvStatus, progress)
            }
        }

        var detectJob: Job? = null
        fun startDetection(targetBitmap: Bitmap, statusPrefix: String = "正在偵測文字區塊…") {
            detectJob?.cancel()
            boxesOverlay.clearBoxes()
            bottomBar.visibility = View.GONE
            tvStatus.text = statusPrefix
            progress.visibility = View.VISIBLE

            detectJob = scope.launch {
                try {
                    if (!ppOcrEngine.isReady) {
                        val loaded = ppOcrEngine.load()
                        if (!loaded) {
                            progress.visibility = View.GONE
                            val reason = ppOcrEngine.lastLoadError ?: "請確認模型檔案完整"
                            tvStatus.text = "PP-OCR 模型載入失敗: $reason"
                            return@launch
                        }
                    }

                    val detectedRects = withContext(Dispatchers.Default) {
                        ppOcrEngine.detectText(targetBitmap)
                    }

                    progress.visibility = View.GONE
                    if (detectedRects.isEmpty()) {
                        tvStatus.text = "未偵測到清晰文字，可嘗試翻轉 180° 或重試"
                    } else {
                        tvStatus.text = "滑動拉框圈選或點選文字區塊 (共 ${detectedRects.size} 處)"
                        boxesOverlay.setDetectedBoxes(detectedRects, targetBitmap.width, targetBitmap.height)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "OCR detection error: ${e.message}", e)
                    progress.visibility = View.GONE
                    tvStatus.text = "OCR 處理發生錯誤 (${e.javaClass.simpleName}): ${e.localizedMessage ?: e.message ?: "未知錯誤"}"
                }
            }
        }

        btnRotateOcr?.setOnClickListener {
            HapticUtil.click(context)
            val current = currentSnapshotBitmap ?: return@setOnClickListener
            if (current.isRecycled) return@setOnClickListener

            val matrix = Matrix().apply { postRotate(180f) }
            val rotated = Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
            currentSnapshotBitmap = rotated
            if (current !== rotated) {
                current.recycle()
            }

            ivSnapshot.setImageBitmap(rotated)
            startDetection(rotated, "已翻轉 180°，正在重新偵測文字…")
        }

        frameContainer.alpha = 0f
        frameContainer.scaleX = 0.6f
        frameContainer.scaleY = 0.6f

        try {
            windowManager.addView(ocrView, ocrSnapshotLayoutParams)
            isSnapshotActive = true
            listener.onShowXButton()

            frameContainer.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(240)
                .setInterpolator(DecelerateInterpolator())
                .start()

            startDetection(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add ocrSnapshotView", e)
        }
    }

    private fun onOcrBoxesSelected(
        bitmap: Bitmap,
        boxes: List<OcrBoxesOverlayView.TextBoundingBox>,
        tvStatus: TextView,
        progress: ProgressBar
    ) {
        if (boxes.isEmpty()) return

        tvStatus.text = if (boxes.size == 1) "正在辨識選取文字…" else "正在辨識選取的 ${boxes.size} 個區塊…"
        progress.visibility = View.VISIBLE

        scope.launch {
            try {
                val recognizedBlocks = mutableListOf<OcrBlock>()
                withContext(Dispatchers.Default) {
                    for (box in boxes) {
                        val text = ppOcrEngine.recognizeBox(bitmap, box.originalRect)
                        if (text.isNotBlank()) {
                            recognizedBlocks.add(OcrBlock(text = text.trim(), rect = box.originalRect))
                        }
                    }
                }

                progress.visibility = View.GONE
                if (recognizedBlocks.isNotEmpty()) {
                    val sep = ModelConfig.ocrSeparator(context)
                    val processed = if (ModelConfig.isOcrSmartStitchEnabled(context)) {
                        TextStitcher.stitch(
                            recognizedBlocks,
                            fallbackSep = sep,
                            panguSpacing = ModelConfig.isOcrPanguSpacingEnabled(context)
                        )
                    } else {
                        recognizedBlocks.joinToString(sep) { it.text }
                    }
                    HapticUtil.heavyClick(context)
                    listener.onOcrTextConfirmed(processed)
                } else {
                    tvStatus.text = "未能成功辨識，請重試或選取其他文字"
                }
            } catch (e: Throwable) {
                Log.e(TAG, "OCR recognition error: ${e.message}", e)
                progress.visibility = View.GONE
                tvStatus.text = "辨識失敗 (${e.javaClass.simpleName}): ${e.localizedMessage ?: e.message ?: "未知錯誤"}"
            }
        }
    }

    fun dismissSnapshot(hideX: Boolean = true) {
        val ocrView = ocrSnapshotView ?: return
        if (!isSnapshotActive) return
        isSnapshotActive = false

        if (hideX) {
            listener.onHideXButton()
        }

        val ivSnapshot = ocrView.findViewById<ImageView>(R.id.iv_ocr_snapshot)
        ivSnapshot?.setImageBitmap(null)
        currentSnapshotBitmap?.let {
            if (!it.isRecycled) {
                it.recycle()
            }
        }
        currentSnapshotBitmap = null

        ocrView.animate()
            .alpha(0f)
            .setDuration(160)
            .withEndAction {
                if (ocrView.isAttachedToWindow) {
                    try {
                        windowManager.removeView(ocrView)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to remove ocrSnapshotView", e)
                    }
                }
                ocrSnapshotView = null
                listener.onSnapshotDismissed(hideX)
            }
            .start()
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

        try {
            ppOcrEngine.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release ppOcrEngine in destroy", e)
        }

        dismissSnapshot(hideX = false)

        val ocr = ocrWindowView
        if (ocr != null && ocr.isAttachedToWindow) {
            try {
                windowManager.removeView(ocr)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove ocrWindowView in destroy", e)
            }
        }
        ocrWindowView = null
        isModeActive = false
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
                    ModelConfig.setCameraZoom(context, MODE_OCR, newZoom)
                    return true
                }
            }
        )
        viewFinder.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun startCameraForOcr(viewFinder: PreviewView) {
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

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview
                )
                val savedZoom = ModelConfig.getCameraZoom(context, MODE_OCR)
                camera?.cameraControl?.setLinearZoom(savedZoom)
                val sliderZoom = ocrWindowView?.findViewById<SeekBar>(R.id.slider_ocr_zoom)
                if (sliderZoom != null) {
                    setupPinchToZoom(viewFinder, sliderZoom)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera initialization for OCR failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
