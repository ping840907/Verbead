package com.ping.verbead

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.PersistableBundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.app.NotificationCompat
import com.google.android.material.card.MaterialCardView
import com.k2fsa.sherpa.onnx.OnlineStream
import com.ping.verbead.bubble.BubbleState as State
import com.ping.verbead.bubble.BubbleStateMachine
import com.ping.verbead.bubble.CapsuleMenuController
import com.ping.verbead.engine.AudioRecorder
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.engine.Qwen3AsrEngine
import com.ping.verbead.engine.XAsrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt

import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.widget.ImageButton
import android.widget.SeekBar
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.ping.verbead.camera.FrameMetrics
import com.ping.verbead.camera.setCovered
import com.ping.verbead.camera.setFrameRoi
import com.ping.verbead.engine.AudioRoutingManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.ViewGroup
import android.widget.EditText
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ping.verbead.ocr.OcrBoxesOverlayView
import com.ping.verbead.ocr.PpOcrEngine
import com.ping.verbead.util.HapticUtil
import de.markusfisch.android.zxingcpp.ZxingCpp
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FloatingBubbleService : Service(), LifecycleOwner {

    companion object {
        private const val TAG = "FloatingBubbleService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "floating_bubble_channel"
        private const val ACTION_STOP = "com.ping.verbead.ACTION_STOP_BUBBLE"
        const val MODE_VOICE = 0
        const val MODE_OCR = 1
        const val MODE_BARCODE = 2
        const val MODE_SCANNER = 2
        const val MODE_PHRASES = 3
        private const val PREF_BUBBLE_MODE = "bubble_mode_pref"
        private const val KEY_MODE = "current_mode"

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var instance: FloatingBubbleService? = null
            private set

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "Cannot start FloatingBubbleService: overlay permission not granted")
                return
            }
            val intent = Intent(context, FloatingBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            context.stopService(intent)
        }
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val themedCtx by lazy { ContextThemeWrapper(this, R.style.Theme_Verbead) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val qwen3Asr by lazy { Qwen3AsrEngine(this) }
    private val xAsr by lazy { XAsrEngine(this) }
    private val recorder = AudioRecorder(this)
    private val routingManager by lazy { AudioRoutingManager.getInstance(this) }
    private val ppOcrEngine by lazy { PpOcrEngine(this) }

    private lateinit var windowManager: WindowManager
    private lateinit var windowLayoutParams: WindowManager.LayoutParams
    private lateinit var bubbleView: View

    private lateinit var previewLayoutParams: WindowManager.LayoutParams
    private lateinit var previewView: View

    private lateinit var btnBubbleMic: FrameLayout
    private lateinit var ivBubbleIcon: ImageView
    private lateinit var progressBubble: ProgressBar
    private lateinit var cardBubblePreview: MaterialCardView
    private lateinit var tvBubblePreview: TextView

    // Standalone floating X button overlay window
    private var xButtonView: View? = null
    private var xButtonLayoutParams: WindowManager.LayoutParams? = null
    private var isRestoringSnapshot = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // Standalone outer pulse ring volume indicator window
    private var pulseView: View? = null
    private var pulseLayoutParams: WindowManager.LayoutParams? = null
    private var viewPulseRing: View? = null
    private var viewPulseWave: View? = null
    private var isPulseShowing = false
    private var lastWaveTime = 0L

    // Capsule menu controller & mode
    private lateinit var capsuleMenuController: CapsuleMenuController
    private val isCapsuleMenuShowing: Boolean
        get() = ::capsuleMenuController.isInitialized && capsuleMenuController.isShowing
    private var currentMode = MODE_VOICE

    // Barcode scanner
    private var scannerView: View? = null
    private var scannerLayoutParams: WindowManager.LayoutParams? = null
    private var isScannerModeActive = false
    private var isScannerEnlarged = false
    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var isFlashOn = false
    @Volatile private var isProcessingBarcode = false
    private var useLocalAverage = false
    private val readerOptions = ZxingCpp.ReaderOptions(
        tryHarder = true,
        tryRotate = true,
        tryInvert = true,
        tryDownscale = true,
        maxNumberOfSymbols = 1,
        textMode = ZxingCpp.TextMode.PLAIN
    )

    // Dedicated OCR floating window
    private var ocrWindowView: View? = null
    private var ocrWindowLayoutParams: WindowManager.LayoutParams? = null
    private var isOcrModeActive = false
    private var isOcrEnlarged = false

    // PP-OCR snapshot
    private var ocrSnapshotView: View? = null
    private var currentSnapshotBitmap: Bitmap? = null
    private var ocrSnapshotLayoutParams: WindowManager.LayoutParams? = null
    private var isOcrSnapshotActive = false

    // Quick Phrases Drawer
    private var phrasesDrawerView: View? = null
    private var phrasesDrawerLayoutParams: WindowManager.LayoutParams? = null
    private var isPhrasesDrawerActive = false
    private var quickPhrasesAdapter: QuickPhrasesAdapter? = null
    private var phrasesList: MutableList<String> = mutableListOf()

    // Touch sampling & screen-space velocity tracking
    private data class TouchPoint(val time: Long, val rawX: Float, val rawY: Float)
    private val recentTouchSamples = ArrayList<TouchPoint>()
    private var dragDistanceSinceTick = 0f

    private fun recordTouchSample(rawX: Float, rawY: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        recentTouchSamples.add(TouchPoint(now, rawX, rawY))
        val cutoff = now - 120
        while (recentTouchSamples.isNotEmpty() && recentTouchSamples.first().time < cutoff) {
            recentTouchSamples.removeAt(0)
        }
    }

    private fun computeScreenVelocity(): Pair<Float, Float> {
        val now = android.os.SystemClock.uptimeMillis()
        val cutoff = now - 120
        while (recentTouchSamples.isNotEmpty() && recentTouchSamples.first().time < cutoff) {
            recentTouchSamples.removeAt(0)
        }
        if (recentTouchSamples.size < 2) return 0f to 0f

        val oldest = recentTouchSamples.first()
        val newest = recentTouchSamples.last()
        val dtMs = newest.time - oldest.time
        if (dtMs < 15) return 0f to 0f

        val dtSec = dtMs / 1000f
        val vx = (newest.rawX - oldest.rawX) / dtSec
        val vy = (newest.rawY - oldest.rawY) / dtSec
        return vx to vy
    }

    private fun updateSystemGestureExclusion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ::bubbleView.isInitialized && bubbleView.isAttachedToWindow) {
            val w = bubbleView.width
            val h = bubbleView.height
            if (w > 0 && h > 0) {
                bubbleView.systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
            }
        }
    }

    private var activeStream: OnlineStream? = null
    private var recordingJob: Job? = null
    private var isRecording = false
    private var isAborted = false
    private var lastStreamingText = ""
    private var autoHidePreviewJob: Job? = null
    private var xButtonAutoHideJob: Job? = null
    private var snapAnimator: ValueAnimator? = null
    private var dynamicKeyboardTop: Int = 0
    private var isDockedOnRight = true
    private var isXButtonShowing = false
    private var isBubbleMoving = false

    // 邊緣收納隱藏狀態 (~30% 可視，~70% 位於畫面外)
    private var isTucked = false
    private val tuckRatio = 0.70f

    private fun getVisibleWidthPx(): Int {
        val density = resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()
        return (bubbleWidthPx * (1f - tuckRatio)).roundToInt()
    }

    private fun getHiddenWidthPx(): Int {
        val density = resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()
        return bubbleWidthPx - getVisibleWidthPx()
    }

    private fun getTuckLeftX(): Int = -getHiddenWidthPx()
    private fun getTuckRightX(): Int = getScreenWidth() - getVisibleWidthPx()
    private fun getNormalLeftX(): Int = 0
    private fun getNormalRightX(): Int = getScreenWidth() - (60 * resources.displayMetrics.density).toInt()

    // §6 狀態機: RECORDING, TRANSCRIBING, PASTED, SCANNING
    private val stateMachine = BubbleStateMachine()
    private val state: State
        get() = stateMachine.state

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundWithNotification()
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission not granted! Aborting service startup.")
            stopSelf()
            return
        }
        instance = this
        isRunning = true
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        routingManager.start()
        currentMode = getSharedPreferences(PREF_BUBBLE_MODE, Context.MODE_PRIVATE).getInt(KEY_MODE, MODE_VOICE)
        if (currentMode !in listOf(MODE_VOICE, MODE_OCR, MODE_BARCODE, MODE_PHRASES)) {
            currentMode = MODE_VOICE
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        capsuleMenuController = CapsuleMenuController(
            context = themedCtx,
            windowManager = windowManager,
            listener = object : CapsuleMenuController.Listener {
                override fun onModeSelected(mode: Int) {
                    applyCapsuleSelection(mode)
                    animateModeIconIntoBubble(mode)
                }
                override fun onDismissed() {
                    if (::btnBubbleMic.isInitialized) {
                        btnBubbleMic.alpha = 1f
                    }
                }
            }
        )
        setupBubbleView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "START_STICKY restarted without overlay permission, stopping self.")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.bubble_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_bubble_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, ImeSettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingBubbleService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_bubble_title))
            .setContentText(getString(R.string.notif_bubble_text))
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_bubble_action_close), stopIntent)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startForeground with MICROPHONE type: ${e.message}", e)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to startForeground: ${e2.message}", e2)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubbleView() {
        bubbleView = LayoutInflater.from(themedCtx).inflate(R.layout.layout_floating_bubble, null)

        btnBubbleMic          = bubbleView.findViewById(R.id.btn_bubble_mic)
        ivBubbleIcon          = bubbleView.findViewById(R.id.iv_bubble_icon)
        progressBubble        = bubbleView.findViewById(R.id.progress_bubble)
        updateBubbleIconForMode(currentMode)

        previewView           = LayoutInflater.from(themedCtx).inflate(R.layout.layout_floating_preview, null)
        cardBubblePreview     = previewView.findViewById(R.id.card_bubble_preview)
        tvBubblePreview       = previewView.findViewById(R.id.tv_bubble_preview)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = resources.displayMetrics
        val density = displayMetrics.density
        val edgeMargin = (16 * density).toInt()
        val bubbleWidthPx = (60 * density).toInt()
        val bubbleHeightPx = (60 * density).toInt()

        val minY = getMinY()
        val maxY = getMaxY()
        val initialY = ((minY + maxY) / 2).coerceIn(minY, maxY)

        val screenWidth = getScreenWidth()
        val rightX = screenWidth - bubbleWidthPx

        // Fixed-size window for mic bubble: start flush to right screen edge
        windowLayoutParams = WindowManager.LayoutParams(
            bubbleWidthPx,
            bubbleHeightPx,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = rightX
            y = initialY
        }

        // Dedicated overlay window for speech preview tooltip
        previewLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.RIGHT
            x = bubbleWidthPx + (8 * density).toInt()
            y = initialY + (12 * density).toInt()
        }

        isDockedOnRight = true

        var initialX = 0
        var initialYPos = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var isDragging = false
        var isLongPressTriggered = false
        val longPressHandler = Handler(Looper.getMainLooper())
        val longPressRunnable = Runnable {
            isLongPressTriggered = true
            HapticUtil.heavyClick(this)
            if (isTucked) {
                untuckBubble(animate = false)
            }
            showCapsuleMenu(touchStartY)
        }

        btnBubbleMic.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    updateSystemGestureExclusion()
                    snapAnimator?.cancel()
                    isBubbleMoving = true
                    bubbleView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    initialX = windowLayoutParams.x
                    initialYPos = windowLayoutParams.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    isDragging = false
                    isLongPressTriggered = false
                    recentTouchSamples.clear()
                    recordTouchSample(event.rawX, event.rawY)
                    longPressHandler.removeCallbacks(longPressRunnable)
                    longPressHandler.postDelayed(longPressRunnable, 350)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    recordTouchSample(event.rawX, event.rawY)
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    val dist = hypot(dx, dy)

                    if (isLongPressTriggered || isCapsuleMenuShowing) {
                        capsuleMenuController.updateDrag(event.rawX, event.rawY)
                        return@setOnTouchListener true
                    }

                    val touchSlop = ViewConfiguration.get(this@FloatingBubbleService).scaledTouchSlop.toFloat().coerceAtMost(8 * density)
                    if (!isDragging && dist > touchSlop) {
                        longPressHandler.removeCallbacks(longPressRunnable)
                        isDragging = true
                        hidePreviewText()
                        hideXButtonImmediately()
                        if (isPhrasesDrawerActive) {
                            closePhrasesDrawer()
                        }
                        initialX = windowLayoutParams.x
                        initialYPos = windowLayoutParams.y
                    }
                    if (isDragging) {
                        val screenWidth = getScreenWidth()
                        val screenHeight = getScreenHeight()
                        val minDragX = getTuckLeftX()
                        val maxDragX = getTuckRightX()
                        val minDragY = 0
                        val maxDragY = screenHeight - bubbleHeightPx

                        val newX = (initialX + (event.rawX - touchStartX)).toInt().coerceIn(minDragX, maxDragX)
                        val newY = (initialYPos + (event.rawY - touchStartY)).toInt().coerceIn(minDragY, maxDragY)
                        if (windowLayoutParams.x != newX || windowLayoutParams.y != newY) {
                            windowLayoutParams.x = newX
                            windowLayoutParams.y = newY
                            windowManager.updateViewLayout(bubbleView, windowLayoutParams)
                            updateXButtonPosition()
                            updatePulsePosition()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    recordTouchSample(event.rawX, event.rawY)
                    longPressHandler.removeCallbacks(longPressRunnable)
                    if (isLongPressTriggered || isCapsuleMenuShowing) {
                        capsuleMenuController.finishDrag(event.rawX, event.rawY)
                        isLongPressTriggered = false
                        isBubbleMoving = false
                        bubbleView.setLayerType(View.LAYER_TYPE_NONE, null)
                    } else {
                        val (vx, vy) = computeScreenVelocity()

                        if (!isDragging) {
                            isBubbleMoving = false
                            bubbleView.setLayerType(View.LAYER_TYPE_NONE, null)
                            if (isTucked) {
                                HapticUtil.click(this)
                                untuckBubble(animate = true)
                            } else {
                                HapticUtil.click(this)
                                onBubbleClick()
                            }
                        } else {
                            snapToSafeBoundsWithInertia(vx, vy)
                        }
                    }
                    recentTouchSamples.clear()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    if (isLongPressTriggered || isCapsuleMenuShowing) {
                        capsuleMenuController.dismiss()
                        isLongPressTriggered = false
                        isBubbleMoving = false
                        bubbleView.setLayerType(View.LAYER_TYPE_NONE, null)
                    } else if (isDragging) {
                        val (vx, vy) = computeScreenVelocity()
                        snapToSafeBoundsWithInertia(vx, vy)
                    } else {
                        isBubbleMoving = false
                        bubbleView.setLayerType(View.LAYER_TYPE_NONE, null)
                    }
                    recentTouchSamples.clear()
                    true
                }
                else -> false
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            bubbleView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateSystemGestureExclusion()
            }
        }

        windowManager.addView(bubbleView, windowLayoutParams)
        previewView.visibility = View.GONE
        windowManager.addView(previewView, previewLayoutParams)
        setState(State.IDLE)

        // Listen for soft keyboard open/close and dynamic height changes via SharedFlow
        scope.launch {
            VoiceAccessibilityService.keyboardStateFlow.collect { info ->
                dynamicKeyboardTop = if (info.isVisible) info.keyboardTop else 0
                val onlyOnKeyboard = ModelConfig.isShowOnlyOnKeyboard(this@FloatingBubbleService)
                if (onlyOnKeyboard) {
                    setBubbleVisible(info.isVisible, animate = true)
                } else {
                    setBubbleVisible(true, animate = true)
                }

                if (info.isVisible && ::bubbleView.isInitialized && bubbleView.isAttachedToWindow) {
                    if (isBubbleMoving || snapAnimator?.isRunning == true) {
                        return@collect
                    }
                    val safeMax = getMaxY()
                    if (windowLayoutParams.y > safeMax) {
                        snapAnimator?.cancel()
                        val startY = windowLayoutParams.y
                        ValueAnimator.ofInt(startY, safeMax).apply {
                            duration = 200
                            interpolator = DecelerateInterpolator()
                            addUpdateListener { anim ->
                                if (bubbleView.isAttachedToWindow) {
                                    val newY = anim.animatedValue as Int
                                    if (windowLayoutParams.y != newY) {
                                        windowLayoutParams.y = newY
                                        windowManager.updateViewLayout(bubbleView, windowLayoutParams)
                                        updateXButtonPosition()
                                        updatePulsePosition()
                                    }
                                }
                            }
                            start()
                        }
                    }
                }
            }
        }

        // §6.2 被動自動隱藏規則 (PASTED 狀態下打字、游標移動、失焦自動隱藏 X 鍵)
        fun handlePastedDismiss() {
            if (state == State.PASTED) {
                hideXButton()
                setState(State.IDLE)
                checkAndHideBubbleIfKeyboardClosed(animate = true)
            }
        }
        scope.launch {
            VoiceAccessibilityService.manualTypingFlow.collect { handlePastedDismiss() }
        }
        scope.launch {
            VoiceAccessibilityService.cursorMovedFlow.collect { handlePastedDismiss() }
        }
        scope.launch {
            VoiceAccessibilityService.inputFocusLostFlow.collect { handlePastedDismiss() }
        }

        val initialKeyboard = VoiceAccessibilityService.instance?.checkKeyboardState()
        val isKeyboardOpen = initialKeyboard?.isVisible == true
        if (isKeyboardOpen) {
            dynamicKeyboardTop = initialKeyboard.keyboardTop
        }
        val onlyOnKeyboard = ModelConfig.isShowOnlyOnKeyboard(this)
        setBubbleVisible(if (onlyOnKeyboard) isKeyboardOpen else true, animate = false)
    }

    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else (24 * resources.displayMetrics.density).toInt()
    }

    private fun getMinY(): Int {
        val density = resources.displayMetrics.density
        return getStatusBarHeight() + (8 * density).toInt()
    }

    private fun getMaxY(): Int {
        val density = resources.displayMetrics.density
        val screenHeight = getScreenHeight()
        val bubbleHeight = (60 * density).toInt()
        val margin = (8 * density).toInt()

        val keyboardTop = if (dynamicKeyboardTop in 1 until screenHeight) {
            dynamicKeyboardTop
        } else {
            (screenHeight * 0.58f).toInt()
        }

        val calculatedMax = keyboardTop - bubbleHeight - margin
        return maxOf(getMinY(), calculatedMax)
    }

    private fun getScreenWidth(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width()
        } else {
            resources.displayMetrics.widthPixels
        }
    }

    private fun getScreenHeight(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            resources.displayMetrics.heightPixels
        }
    }

    fun setBubbleVisible(visible: Boolean, animate: Boolean = true) {
        if (!::bubbleView.isInitialized) return
        if (!visible && (state == State.RECORDING || state == State.TRANSCRIBING || state == State.PASTED || isScannerModeActive || isOcrModeActive || isOcrSnapshotActive || isPhrasesDrawerActive || isCapsuleMenuShowing)) {
            return
        }
        val isCurrentlyShowing = bubbleView.visibility == View.VISIBLE && bubbleView.alpha > 0.99f
        if (visible && isCurrentlyShowing) return
        val isCurrentlyHidden = bubbleView.visibility == View.GONE || (bubbleView.visibility == View.VISIBLE && bubbleView.alpha < 0.01f)
        if (!visible && isCurrentlyHidden) return

        bubbleView.animate().cancel()
        if (visible) {
            bubbleView.visibility = View.VISIBLE
            if (animate) {
                bubbleView.animate().alpha(1f).setDuration(180).withEndAction {
                    updateSystemGestureExclusion()
                }.start()
            } else {
                bubbleView.alpha = 1f
                bubbleView.post { updateSystemGestureExclusion() }
            }
        } else {
            hideXButtonImmediately()
            if (animate) {
                bubbleView.animate().alpha(0f).setDuration(180).withEndAction {
                    if (state != State.RECORDING && state != State.TRANSCRIBING && !isScannerModeActive && !isOcrModeActive && !isOcrSnapshotActive && !isPhrasesDrawerActive) {
                        bubbleView.visibility = View.GONE
                    } else {
                        bubbleView.alpha = 1f
                    }
                }.start()
            } else {
                bubbleView.visibility = View.GONE
                bubbleView.alpha = 0f
            }
        }
    }

    fun applyKeyboardOnlySetting() {
        val onlyOnKeyboard = ModelConfig.isShowOnlyOnKeyboard(this)
        val isKeyboardOpen = VoiceAccessibilityService.instance?.checkKeyboardState()?.isVisible == true
        if (onlyOnKeyboard) {
            setBubbleVisible(isKeyboardOpen, animate = true)
        } else {
            setBubbleVisible(true, animate = true)
        }
    }

    private fun checkAndHideBubbleIfKeyboardClosed(animate: Boolean = true) {
        if (!ModelConfig.isShowOnlyOnKeyboard(this)) {
            // 常駐模式：不因鍵盤收起而隱藏，常駐於側邊
            return
        }
        val isKeyboardOpen = VoiceAccessibilityService.instance?.checkKeyboardState()?.isVisible == true
        if (!isKeyboardOpen && !isScannerModeActive && !isOcrModeActive && !isOcrSnapshotActive && !isPhrasesDrawerActive && state != State.PASTED && state != State.RECORDING && state != State.TRANSCRIBING && !isCapsuleMenuShowing) {
            setBubbleVisible(false, animate = animate)
        }
    }

    private fun updateLayoutForEdge(onRightEdge: Boolean) {
        isDockedOnRight = onRightEdge
        updatePreviewPosition()
    }

    private fun untuckBubble(animate: Boolean = true, onComplete: (() -> Unit)? = null) {
        if (!isTucked || !::bubbleView.isInitialized || !bubbleView.isAttachedToWindow) {
            isTucked = false
            onComplete?.invoke()
            return
        }
        isTucked = false
        val screenWidth = getScreenWidth()
        val density = resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()
        val targetX = if (isDockedOnRight) (screenWidth - bubbleWidthPx) else 0
        val targetY = windowLayoutParams.y.coerceIn(getMinY(), getMaxY())

        if (!animate) {
            snapAnimator?.cancel()
            windowLayoutParams.x = targetX
            windowLayoutParams.y = targetY
            windowManager.updateViewLayout(bubbleView, windowLayoutParams)
            updateXButtonPosition()
            updatePulsePosition()
            updatePreviewPosition()
            updateSystemGestureExclusion()
            onComplete?.invoke()
            return
        }

        snapAnimator?.cancel()
        val startX = windowLayoutParams.x
        val startY = windowLayoutParams.y
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = OvershootInterpolator(1.15f)
            addUpdateListener { anim ->
                if (bubbleView.isAttachedToWindow) {
                    val f = anim.animatedFraction
                    val curX = (startX + (targetX - startX) * f).toInt()
                    val curY = (startY + (targetY - startY) * f).toInt()
                    if (windowLayoutParams.x != curX || windowLayoutParams.y != curY) {
                        windowLayoutParams.x = curX
                        windowLayoutParams.y = curY
                        windowManager.updateViewLayout(bubbleView, windowLayoutParams)
                        updateXButtonPosition()
                        updatePulsePosition()
                    }
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (bubbleView.isAttachedToWindow) {
                        windowLayoutParams.x = targetX
                        windowLayoutParams.y = targetY
                        windowManager.updateViewLayout(bubbleView, windowLayoutParams)
                        updateXButtonPosition()
                        updatePulsePosition()
                        updatePreviewPosition()
                        updateSystemGestureExclusion()
                    }
                    onComplete?.invoke()
                }
            })
            start()
        }
    }

    private fun snapToSafeBoundsWithInertia(vx: Float = 0f, vy: Float = 0f) {
        if (!bubbleView.isAttachedToWindow) return
        snapAnimator?.cancel()
        isBubbleMoving = true
        bubbleView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val screenWidth = getScreenWidth()
        val density = resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()

        val startX = windowLayoutParams.x.toFloat()
        val startY = windowLayoutParams.y.toFloat()

        // 1. Friction coasting model to determine projected rest coordinate
        val tau = 0.22f // Effective friction deceleration coasting time constant (tau = 1 / beta)
        val currentCenterX = startX + bubbleWidthPx / 2f
        val projectedCenterX = currentCenterX + vx * tau

        // Target edge is determined strictly by whether the projected rest position crosses the screen midpoint
        val targetOnRight = projectedCenterX >= screenWidth / 2f
        isDockedOnRight = targetOnRight

        val normalLeftX = 0f
        val normalRightX = (screenWidth - bubbleWidthPx).toFloat()
        val tuckLeftX = getTuckLeftX().toFloat()
        val tuckRightX = getTuckRightX().toFloat()
        val hiddenWidthPx = getHiddenWidthPx().toFloat()

        val allowTuck = (state == State.IDLE || state == State.SCANNING) && !isScannerModeActive && !isOcrModeActive && !isOcrSnapshotActive && !isPhrasesDrawerActive
        val shouldTuck = if (!allowTuck) {
            false
        } else if (targetOnRight) {
            if (isTucked) {
                // Currently tucked: untuck only if pulled inward significantly or flung inward
                val pullInwardThreshold = normalRightX + hiddenWidthPx * 0.60f
                !(startX < pullInwardThreshold || vx < -180f)
            } else {
                // Currently untucked: tuck if pushed outward past normal edge or flung outward from edge
                val pushOutwardThreshold = normalRightX + hiddenWidthPx * 0.30f
                (startX >= pushOutwardThreshold && vx >= -100f) ||
                    (startX >= normalRightX - 12 * density && vx > 220f)
            }
        } else {
            if (isTucked) {
                // Currently tucked: untuck only if pulled inward significantly or flung inward
                val pullInwardThreshold = normalLeftX - hiddenWidthPx * 0.60f
                !(startX > pullInwardThreshold || vx > 180f)
            } else {
                // Currently untucked: tuck if pushed outward past normal edge or flung outward from edge
                val pushOutwardThreshold = normalLeftX - hiddenWidthPx * 0.30f
                (startX <= pushOutwardThreshold && vx <= 100f) ||
                    (startX <= normalLeftX + 12 * density && vx < -220f)
            }
        }

        isTucked = shouldTuck
        val targetX = if (targetOnRight) {
            if (shouldTuck) tuckRightX else normalRightX
        } else {
            if (shouldTuck) tuckLeftX else normalLeftX
        }

        val curMinY = getMinY().toFloat()
        val curMaxY = getMaxY().toFloat()
        val projectedY = startY + vy * tau
        val targetY = projectedY.coerceIn(curMinY, curMaxY)

        // 2. Facebook Messenger / AOSP Damped Harmonic Oscillator (Spring) Physics
        // Natural frequency and damping ratio:
        val omegaN = 16.0f // rad/s
        val zeta = 0.82f // Slightly underdamped for snappy response with subtle organic edge cushion
        val gamma = zeta * omegaN
        val omegaD = (omegaN * kotlin.math.sqrt(1.0 - (zeta * zeta))).toFloat()

        val c1x = startX - targetX
        val c2x = if (omegaD > 0.001f) (vx + gamma * c1x) / omegaD else 0f

        val c1y = startY - targetY
        val c2y = if (omegaD > 0.001f) (vy + gamma * c1y) / omegaD else 0f

        val durationMs = 380L
        val minClampX = tuckLeftX.toInt()
        val maxClampX = tuckRightX.toInt()

        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { anim ->
                if (bubbleView.isAttachedToWindow) {
                    val f = anim.animatedFraction
                    val t = f * (durationMs / 1000f) // time in seconds
                    val exp = kotlin.math.exp((-gamma * t).toDouble()).toFloat()
                    val cos = kotlin.math.cos((omegaD * t).toDouble()).toFloat()
                    val sin = kotlin.math.sin((omegaD * t).toDouble()).toFloat()

                    val currentX = targetX + exp * (c1x * cos + c2x * sin)
                    val currentY = targetY + exp * (c1y * cos + c2y * sin)

                    val newX = currentX.toInt().coerceIn(minClampX, maxClampX)
                    val newY = currentY.toInt().coerceIn(curMinY.toInt(), curMaxY.toInt())

                    // Early exit when settled within subpixel threshold to eliminate tail micro-jitter
                    if (t > 0.20f && kotlin.math.abs(currentX - targetX) < 0.75f && kotlin.math.abs(currentY - targetY) < 0.75f) {
                        val finalX = targetX.toInt().coerceIn(minClampX, maxClampX)
                        val finalY = targetY.toInt().coerceIn(curMinY.toInt(), curMaxY.toInt())
                        if (windowLayoutParams.x != finalX || windowLayoutParams.y != finalY) {
                            windowLayoutParams.x = finalX
                            windowLayoutParams.y = finalY
                            windowManager.updateViewLayout(bubbleView, windowLayoutParams)
                        }
                        anim.cancel()
                        return@addUpdateListener
                    }

                    if (windowLayoutParams.x != newX || windowLayoutParams.y != newY) {
                        windowLayoutParams.x = newX
                        windowLayoutParams.y = newY
                        windowManager.updateViewLayout(bubbleView, windowLayoutParams)
                        updateXButtonPosition()
                        updatePulsePosition()
                    }
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    isBubbleMoving = false
                    if (!bubbleView.isAttachedToWindow) return
                    bubbleView.setLayerType(View.LAYER_TYPE_NONE, null)
                    isDockedOnRight = targetOnRight
                    val finalX = targetX.toInt().coerceIn(minClampX, maxClampX)
                    val finalY = targetY.toInt().coerceIn(curMinY.toInt(), curMaxY.toInt())
                    if (windowLayoutParams.x != finalX || windowLayoutParams.y != finalY) {
                        windowLayoutParams.x = finalX
                        windowLayoutParams.y = finalY
                        windowManager.updateViewLayout(bubbleView, windowLayoutParams)
                        updateXButtonPosition()
                        updatePulsePosition()
                    }
                    updatePreviewPosition()
                    updateSystemGestureExclusion()
                    if (!isTucked && (isScannerModeActive || isOcrModeActive || isPhrasesDrawerActive || state == State.PASTED || state == State.RECORDING || state == State.TRANSCRIBING)) {
                        showXButton()
                    }
                    HapticUtil.click(this@FloatingBubbleService)
                }
            })
            start()
        }
    }

    // 懸浮按鈕 X 鍵點擊事件
    internal fun onXButtonClick() {
        if (isRestoringSnapshot) {
            Log.w(TAG, "onXButtonClick: snapshot restore already in progress, debouncing")
            return
        }
        HapticUtil.click(this)
        when {
            state == State.PASTED || VoiceAccessibilityService.instance?.hasValidSnapshot() == true -> {
                isRestoringSnapshot = true
                try {
                    // 復原文字框 (Undo)：透過無障礙快照還原至貼上前之內容與游標位置、state → IDLE
                    val restored = VoiceAccessibilityService.instance?.restoreLastSnapshot() ?: false
                    if (restored) {
                        showPreviewText(getString(R.string.preview_restored), autoHide = true)
                    }
                    setState(State.IDLE)
                    hideXButton()
                    checkAndHideBubbleIfKeyboardClosed(animate = true)
                } finally {
                    mainHandler.postDelayed({ isRestoringSnapshot = false }, 500)
                }
            }
            isPhrasesDrawerActive -> {
                // 關閉常用語抽屜視窗
                closePhrasesDrawer()
                hideXButton()
            }
            isOcrSnapshotActive -> {
                // 關閉文字辨識快照與視窗
                dismissOcrSnapshot()
                stopOcrMode()
                hideXButton()
            }
            isOcrModeActive -> {
                // 關閉文字辨識相機視窗
                stopOcrMode()
                hideXButton()
            }
            isScannerModeActive -> {
                // 關閉條碼掃描相機視窗
                stopScannerMode()
                hideXButton()
            }
            state == State.RECORDING -> {
                // 中止錄音：停止 AudioRecord、丟棄音訊 buffer、關閉預覽氣泡、state → IDLE
                isAborted = true
                isRecording = false
                recordingJob?.cancel()
                recorder.stopEarly()
                activeStream?.let { runCatching { it.release() } }
                activeStream = null
                hidePreviewText()
                setState(State.IDLE)
                hideXButton()
            }
            state == State.TRANSCRIBING -> {
                // 中斷模型推論 (Abort)：Cancel 背景轉譯協程、設置 isAborted 旗標阻止後續貼上、state → IDLE
                isAborted = true
                recordingJob?.cancel()
                hidePreviewText()
                setState(State.IDLE)
                hideXButton()
            }
            else -> {
                val restored = VoiceAccessibilityService.instance?.restoreLastSnapshot() ?: false
                if (restored) {
                    showPreviewText(getString(R.string.preview_restored), autoHide = true)
                }
                setState(State.IDLE)
                hideXButton()
            }
        }
    }

    private fun updateXButtonPosition() {
        val view = xButtonView ?: return
        if (!isXButtonShowing || !view.isAttachedToWindow) return
        val lp = xButtonLayoutParams ?: return
        val density = resources.displayMetrics.density
        val btnSize = (44 * density).toInt()
        val bubbleWidthPx = (60 * density).toInt()
        val statusBar = getStatusBarHeight()
        val preferredY = windowLayoutParams.y - (48 * density).toInt()
        val targetY = if (preferredY < statusBar) {
            // 上方空間不足（接近狀態列/螢幕頂端），智慧翻轉顯示在懸浮球正下方
            windowLayoutParams.y + bubbleWidthPx + (4 * density).toInt()
        } else {
            preferredY
        }
        lp.x = windowLayoutParams.x + ((bubbleWidthPx - btnSize) / 2)
        lp.y = targetY
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update xButton layout", e)
        }
    }

    // 獨立懸浮 X 鍵視窗（完全獨立 WindowManager 視窗，徹底消除氣泡視窗高度變更產生的任何擠壓或閃爍）
    internal fun showXButton() {
        xButtonAutoHideJob?.cancel()
        if (isTucked) {
            untuckBubble(animate = false)
        }
        if (isXButtonShowing) {
            updateXButtonPosition()
            return
        }
        isXButtonShowing = true

        if (xButtonView == null) {
            xButtonView = LayoutInflater.from(themedCtx).inflate(R.layout.layout_bubble_x, null).apply {
                setOnClickListener { onXButtonClick() }
            }
        }
        val view = xButtonView ?: return
        val density = resources.displayMetrics.density

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val btnSize = (44 * density).toInt()
        val bubbleWidthPx = (60 * density).toInt()
        val xOffset = windowLayoutParams.x + ((bubbleWidthPx - btnSize) / 2)
        val statusBar = getStatusBarHeight()
        val preferredY = windowLayoutParams.y - (48 * density).toInt()
        val isFlipped = preferredY < statusBar
        val targetY = if (isFlipped) {
            windowLayoutParams.y + bubbleWidthPx + (4 * density).toInt()
        } else {
            preferredY
        }

        xButtonLayoutParams = WindowManager.LayoutParams(
            btnSize,
            btnSize,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = xOffset
            y = targetY
        }

        view.animate().cancel()
        if (view.isAttachedToWindow) {
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove previous xButtonView", e)
            }
        }
        view.alpha = 0f
        view.translationY = if (isFlipped) -dpToPx(12f) else dpToPx(12f)
        view.scaleX = 0.6f
        view.scaleY = 0.6f
        windowManager.addView(view, xButtonLayoutParams)

        view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
    }

    private fun hideXButton() {
        xButtonAutoHideJob?.cancel()
        if (!isXButtonShowing) return
        isXButtonShowing = false
        val view = xButtonView ?: return
        view.animate().cancel()

        view.animate()
            .alpha(0f)
            .translationY(dpToPx(10f))
            .scaleX(0.6f)
            .scaleY(0.6f)
            .setDuration(160)
            .withEndAction {
                if (view.isAttachedToWindow) {
                    try {
                        windowManager.removeView(view)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to remove xButtonView on hide end", e)
                    }
                }
            }
            .start()
    }

    private fun hideXButtonImmediately() {
        xButtonAutoHideJob?.cancel()
        if (!isXButtonShowing) return
        isXButtonShowing = false
        val view = xButtonView ?: return
        view.animate().cancel()
        if (view.isAttachedToWindow) {
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove xButtonView immediately", e)
            }
        }
    }

    // 獨立脈衝音量外圈視窗（像教學動畫般，於錄音中圍繞懸浮球根據 RMS 音量即時外擴收縮）
    private fun updatePulsePosition() {
        val view = pulseView ?: return
        if (!isPulseShowing || !view.isAttachedToWindow) return
        val lp = pulseLayoutParams ?: return
        val density = resources.displayMetrics.density
        val pulseSize = (104 * density).toInt()
        val bubbleWidthPx = (60 * density).toInt()
        val bubbleHeightPx = (60 * density).toInt()
        lp.x = windowLayoutParams.x + ((bubbleWidthPx - pulseSize) / 2)
        lp.y = windowLayoutParams.y + ((bubbleHeightPx - pulseSize) / 2)
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update pulse layout", e)
        }
    }

    private fun showPulseView() {
        if (isPulseShowing) {
            updatePulsePosition()
            return
        }
        isPulseShowing = true

        if (pulseView == null) {
            pulseView = LayoutInflater.from(themedCtx).inflate(R.layout.layout_bubble_pulse, null).apply {
                viewPulseRing = findViewById(R.id.view_bubble_pulse_ring)
                viewPulseWave = findViewById(R.id.view_bubble_pulse_wave)
            }
        }
        val view = pulseView ?: return
        val density = resources.displayMetrics.density

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val pulseSize = (104 * density).toInt()
        val bubbleWidthPx = (60 * density).toInt()
        val bubbleHeightPx = (60 * density).toInt()
        val xOffset = windowLayoutParams.x + ((bubbleWidthPx - pulseSize) / 2)
        val yOffset = windowLayoutParams.y + ((bubbleHeightPx - pulseSize) / 2)

        pulseLayoutParams = WindowManager.LayoutParams(
            pulseSize,
            pulseSize,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = xOffset
            y = yOffset
        }

        view.animate().cancel()
        viewPulseRing?.animate()?.cancel()
        viewPulseWave?.animate()?.cancel()

        if (view.isAttachedToWindow) {
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove previous pulseView", e)
            }
        }

        viewPulseRing?.alpha = 0.25f
        viewPulseRing?.scaleX = 1.0f
        viewPulseRing?.scaleY = 1.0f

        viewPulseWave?.alpha = 0f
        viewPulseWave?.scaleX = 1.0f
        viewPulseWave?.scaleY = 1.0f

        try {
            windowManager.addView(view, pulseLayoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add pulseView to WindowManager", e)
        }
    }

    private fun hidePulseView() {
        if (!isPulseShowing) return
        isPulseShowing = false
        val view = pulseView ?: return
        viewPulseRing?.animate()?.cancel()
        viewPulseWave?.animate()?.cancel()

        viewPulseRing?.animate()
            ?.alpha(0f)
            ?.scaleX(1.0f)
            ?.scaleY(1.0f)
            ?.setDuration(160)
            ?.withEndAction {
                if (view.isAttachedToWindow && !isPulseShowing) {
                    try {
                        windowManager.removeView(view)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to remove pulseView on hide", e)
                    }
                }
            }
            ?.start()

        viewPulseWave?.animate()?.alpha(0f)?.setDuration(120)?.start()
    }

    private fun hidePulseViewImmediately() {
        if (!isPulseShowing) return
        isPulseShowing = false
        val view = pulseView ?: return
        viewPulseRing?.animate()?.cancel()
        viewPulseWave?.animate()?.cancel()
        if (view.isAttachedToWindow) {
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove pulseView immediately", e)
            }
        }
    }

    private fun onAudioRmsUpdate(rms: Float) {
        if (!isRecording || !isPulseShowing) return
        Handler(Looper.getMainLooper()).post {
            if (!isRecording || !isPulseShowing) return@post
            val ring = viewPulseRing ?: return@post
            val wave = viewPulseWave ?: return@post

            // RMS 能量範圍通常介於 0.005（靜音底噪）~ 0.18（清晰說話）
            val norm = ((rms - 0.008f) / 0.12f).coerceIn(0f, 1f)

            // 動態外擴比例：1.0f（同懸浮球大小）~ 1.48f（外擴脈衝）
            val targetScale = 1.0f + norm * 0.48f
            val targetAlpha = if (norm > 0.05f) (0.25f + norm * 0.55f).coerceAtMost(0.85f) else 0.12f

            ring.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .alpha(targetAlpha)
                .setDuration(70)
                .start()

            // 說話重音峰值時觸發擴散外波（如音量大於門檻且間隔達 320ms）
            val now = android.os.SystemClock.uptimeMillis()
            if (norm > 0.35f && (now - lastWaveTime > 320L)) {
                lastWaveTime = now
                wave.animate().cancel()
                wave.scaleX = 1.05f
                wave.scaleY = 1.05f
                wave.alpha = 0.65f
                wave.animate()
                    .scaleX(1.55f)
                    .scaleY(1.55f)
                    .alpha(0f)
                    .setDuration(380)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun updatePreviewPosition() {
        if (!::previewView.isInitialized || !::bubbleView.isInitialized) return
        val density = resources.displayMetrics.density
        val padding = (14 * density).toInt()
        val visualGap = (8 * density).toInt()
        val bubbleWidthPx = (60 * density).toInt()
        val windowX = bubbleWidthPx + visualGap - padding

        if (isDockedOnRight) {
            previewLayoutParams.gravity = Gravity.TOP or Gravity.RIGHT
            previewLayoutParams.x = windowX
        } else {
            previewLayoutParams.gravity = Gravity.TOP or Gravity.LEFT
            previewLayoutParams.x = windowX
        }
        previewLayoutParams.y = windowLayoutParams.y + (12 * density).toInt() - padding

        if (previewView.isAttachedToWindow) {
            windowManager.updateViewLayout(previewView, previewLayoutParams)
        }
    }

    private fun hidePreviewText() {
        autoHidePreviewJob?.cancel()
        if (::previewView.isInitialized && previewView.isAttachedToWindow) {
            previewView.visibility = View.GONE
        }
    }

    private fun showPreviewText(msg: String, autoHide: Boolean = true) {
        if (!::previewView.isInitialized) return
        if (isTucked) {
            untuckBubble(animate = false)
        }
        tvBubblePreview.text = msg
        updatePreviewPosition()
        if (previewView.isAttachedToWindow) {
            previewView.visibility = View.VISIBLE
        }
        autoHidePreviewJob?.cancel()
        if (autoHide) {
            autoHidePreviewJob = scope.launch {
                delay(5000L)
                hidePreviewText()
            }
        }
    }

    private fun onBubbleClick() {
        if (isTucked) {
            untuckBubble(animate = true)
            return
        }
        if (state == State.PASTED) {
            hideXButton()
            setState(State.IDLE)
        }
        if (currentMode == MODE_PHRASES || isPhrasesDrawerActive) {
            togglePhrasesDrawer()
            return
        }
        if (currentMode == MODE_OCR || isOcrModeActive) {
            toggleOcrMode()
            return
        }
        if (currentMode == MODE_BARCODE || isScannerModeActive || state == State.SCANNING) {
            toggleScannerMode()
            return
        }
        when (state) {
            State.RECORDING -> {
                recorder.stopEarly()
                hidePreviewText()
                setState(State.TRANSCRIBING)
            }
            State.LOADING -> { /* Waiting for model */ }
            State.TRANSCRIBING -> { /* Processing */ }
            State.IDLE, State.PASTED -> {
                hideXButton()
                startRecording()
            }
            State.SCANNING -> {
                toggleScannerMode()
            }
        }
    }

    private fun isDualEngineActive(): Boolean = ModelConfig.isDualEngineActive(this)

    private fun startRecording() {
        isAborted = false
        if (isTucked) {
            untuckBubble(animate = false)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.toast_mic_permission_voice, Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            return
        }

        try {
            if (isDualEngineActive()) {
                if (!xAsr.isLoaded() || !qwen3Asr.isLoaded() || qwen3NeedsReload()) {
                    preloadDualEngineAndStart()
                    return
                }
                startDualEngineRecording()
                return
            }

            val engine = ModelConfig.selectedEngine(this)
            if (engine == ModelConfig.ENGINE_X_ASR) {
                if (!xAsr.isLoaded()) {
                    preloadAndStart(engine)
                    return
                }
                startStreamingRecording()
            } else {
                if (!qwen3Asr.isLoaded() || qwen3NeedsReload()) {
                    preloadAndStart(engine)
                    return
                }
                startOfflineRecording()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "startRecording failed: ${e.message}", e)
            setState(State.IDLE)
            showPreviewText(getString(R.string.preview_voice_start_failed, e.localizedMessage ?: "未知錯誤"), autoHide = true)
        }
    }

    private fun preloadDualEngineAndStart() {
        setState(State.LOADING)
        showPreviewText(getString(R.string.preview_loading_dual_models), autoHide = false)

        scope.launch {
            val (xOk, qOk) = withContext(Dispatchers.IO) {
                val x = xAsr.load().success
                val q = qwen3Asr.load(buildHotwords()).success
                x to q
            }
            if (xOk && qOk) {
                startDualEngineRecording()
            } else {
                setState(State.IDLE)
                showPreviewText(getString(R.string.preview_model_load_failed), autoHide = true)
            }
        }
    }

    private fun preloadAndStart(engine: String) {
        setState(State.LOADING)
        showPreviewText(getString(R.string.preview_loading_models), autoHide = false)

        scope.launch {
            val success = withContext(Dispatchers.IO) {
                if (engine == ModelConfig.ENGINE_X_ASR) {
                    xAsr.load().success
                } else {
                    qwen3Asr.load(buildHotwords()).success
                }
            }
            if (success) {
                if (engine == ModelConfig.ENGINE_X_ASR) {
                    startStreamingRecording()
                } else {
                    startOfflineRecording()
                }
            } else {
                setState(State.IDLE)
                showPreviewText(getString(R.string.preview_model_load_failed), autoHide = true)
            }
        }
    }

    // 雙引擎模式底層運作機制
    private fun startDualEngineRecording() {
        val stream = xAsr.createStream() ?: run {
            showPreviewText(getString(R.string.preview_cannot_create_stream), autoHide = true)
            return
        }
        activeStream = stream
        isRecording = true
        isAborted = false
        setState(State.RECORDING)
        showXButton()
        showPreviewText(getString(R.string.preview_listening), autoHide = false)

        val audioBuffer = mutableListOf<FloatArray>()
        var accumulatedXAsr = ""
        lastStreamingText = ""

        recordingJob = scope.launch {
            val stopReason = withContext(Dispatchers.IO) {
                recorder.recordStreaming(
                    silenceSeconds = ModelConfig.vadSilenceSeconds(this@FloatingBubbleService),
                    onChunk = { chunk ->
                        if (isAborted) return@recordStreaming
                        audioBuffer.add(chunk.copyOf())
                        xAsr.acceptWaveform(stream, chunk)
                        while (xAsr.isReady(stream)) {
                            xAsr.decode(stream)
                        }
                        val partial = xAsr.getResult(stream)
                        val combined = accumulatedXAsr + partial
                        if (combined.isNotBlank() && combined != lastStreamingText) {
                            lastStreamingText = combined
                            Handler(Looper.getMainLooper()).post {
                                if (isRecording && !isAborted) {
                                    showPreviewText(combined, autoHide = false)
                                }
                            }
                        }
                        if (xAsr.isEndpoint(stream)) {
                            if (partial.isNotBlank()) accumulatedXAsr += partial
                            xAsr.reset(stream)
                        }
                    },
                    onRmsUpdate = { rms -> onAudioRmsUpdate(rms) }
                )
            }

            isRecording = false
            hidePulseView()
            activeStream = null
            runCatching { xAsr.inputFinished(stream) }
            runCatching {
                while (xAsr.isReady(stream)) {
                    xAsr.decode(stream)
                }
            }
            val finalPartial = xAsr.getResult(stream).trim()
            val totalXAsrText = (accumulatedXAsr + finalPartial).trim()
            lastStreamingText = totalXAsrText
            runCatching { stream.release() }

            if (isAborted) return@launch

            if (stopReason == AudioRecorder.StopReason.INITIAL_TIMEOUT) {
                setState(State.IDLE)
                hideXButton()
                showPreviewText(getString(R.string.preview_no_speech_detected), autoHide = true)
                return@launch
            }

            // 錄音結束，預覽氣泡淡出，維持目標框純淨
            Handler(Looper.getMainLooper()).post {
                hidePreviewText()
                setState(State.TRANSCRIBING)
            }

            // 合併完整的音訊 buffer
            val totalSamples = audioBuffer.sumOf { it.size }
            if (totalSamples == 0) {
                setState(State.IDLE)
                hideXButton()
                showPreviewText(getString(R.string.preview_no_speech_detected), autoHide = true)
                return@launch
            }
            val combinedAudio = FloatArray(totalSamples)
            var offset = 0
            for (chunk in audioBuffer) {
                System.arraycopy(chunk, 0, combinedAudio, offset, chunk.size)
                offset += chunk.size
            }

            // §2.4 Qwen3 轉譯與降級防護 (Fallback)
            try {
                val raw = withContext(Dispatchers.Default) {
                    if (isAborted) return@withContext ""
                    qwen3Asr.transcribe(combinedAudio)
                }
                if (isAborted) return@launch
                val text = if (raw.isNotBlank()) postProcess(raw) else ""
                onTranscriptionDone(text)
            } catch (ex: Throwable) {
                if (ex is kotlinx.coroutines.CancellationException || isAborted) return@launch
                Log.e(TAG, "Qwen3 batch inference failed: ${ex.message}", ex)
                val fallbackText = lastStreamingText
                if (fallbackText.isNotEmpty()) {
                    val processedFallback = postProcess(fallbackText)
                    onTranscriptionDone(processedFallback)
                    Toast.makeText(this@FloatingBubbleService, R.string.toast_offline_model_error_applied_streaming, Toast.LENGTH_SHORT).show()
                } else {
                    setState(State.IDLE)
                    hideXButton()
                    showPreviewText(getString(R.string.preview_transcribe_failed), autoHide = true)
                    Toast.makeText(this@FloatingBubbleService, R.string.toast_transcribe_failed_retry, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startOfflineRecording() {
        isRecording = true
        isAborted = false
        setState(State.RECORDING)
        showXButton()
        showPreviewText(getString(R.string.preview_listening), autoHide = false)

        recordingJob = scope.launch {
            val recording = withContext(Dispatchers.IO) {
                recorder.recordUntilSilence(
                    silenceSeconds = ModelConfig.vadSilenceSeconds(this@FloatingBubbleService),
                    onRmsUpdate = { rms -> onAudioRmsUpdate(rms) }
                )
            }
            isRecording = false
            hidePulseView()
            if (isAborted) return@launch

            if (recording.samples.isNotEmpty()) {
                setState(State.TRANSCRIBING)
                hidePreviewText()
                try {
                    val raw = withContext(Dispatchers.Default) {
                        if (isAborted) return@withContext ""
                        qwen3Asr.transcribe(recording.samples)
                    }
                    if (isAborted) return@launch
                    val text = if (raw.isNotBlank()) postProcess(raw) else ""
                    onTranscriptionDone(text)
                } catch (ex: Throwable) {
                    if (ex is kotlinx.coroutines.CancellationException || isAborted) return@launch
                    setState(State.IDLE)
                    hideXButton()
                    showPreviewText(getString(R.string.preview_transcribe_failed), autoHide = true)
                }
            } else {
                setState(State.IDLE)
                hideXButton()
                showPreviewText(getString(R.string.preview_no_speech_detected), autoHide = true)
            }
        }
    }

    private fun startStreamingRecording() {
        val engine = xAsr
        val stream = engine.createStream() ?: run {
            showPreviewText(getString(R.string.preview_cannot_create_stream), autoHide = true)
            return
        }
        activeStream = stream
        isRecording = true
        isAborted = false
        setState(State.RECORDING)
        showXButton()
        showPreviewText(getString(R.string.preview_listening), autoHide = false)

        var accumulated = ""
        var lastShown = ""

        recordingJob = scope.launch {
            val stopReason = withContext(Dispatchers.IO) {
                recorder.recordStreaming(
                    silenceSeconds = ModelConfig.vadSilenceSeconds(this@FloatingBubbleService),
                    onChunk = { chunk ->
                        if (isAborted) return@recordStreaming
                        engine.acceptWaveform(stream, chunk)
                        while (engine.isReady(stream)) {
                            engine.decode(stream)
                        }
                        val partial = engine.getResult(stream)
                        val combined = accumulated + partial
                        if (combined.isNotBlank() && combined != lastShown) {
                            lastShown = combined
                            Handler(Looper.getMainLooper()).post {
                                if (isRecording && !isAborted) {
                                    showPreviewText(combined, autoHide = false)
                                }
                            }
                        }
                        if (engine.isEndpoint(stream)) {
                            if (partial.isNotBlank()) accumulated += partial
                            engine.reset(stream)
                        }
                    },
                    onRmsUpdate = { rms -> onAudioRmsUpdate(rms) }
                )
            }

            isRecording = false
            hidePulseView()
            activeStream = null

            runCatching { engine.inputFinished(stream) }
            runCatching {
                while (engine.isReady(stream)) {
                    engine.decode(stream)
                }
            }
            val finalSegment = engine.getResult(stream).trim()
            runCatching { stream.release() }

            if (isAborted) return@launch

            if (stopReason == AudioRecorder.StopReason.INITIAL_TIMEOUT) {
                setState(State.IDLE)
                hideXButton()
                showPreviewText(getString(R.string.preview_no_speech_detected), autoHide = true)
                return@launch
            }

            val fullText = (accumulated + finalSegment).trim()
            onTranscriptionDone(fullText)
        }
    }

    /**
     * Copies text to system clipboard with sensitive flag as fallback when direct injection is unavailable.
     */
    private fun copyToClipboardFallback(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = ClipData.newPlainText("transcription", text).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
        }
        clipboard.setPrimaryClip(clip)
    }

    /**
     * Transitions to PASTED state, reveals the floating X button, and schedules auto-dismiss after [delayMs].
     */
    internal fun enterPastedState(delayMs: Long = 10_000) {
        setState(State.PASTED)
        showXButton()
        xButtonAutoHideJob?.cancel()
        xButtonAutoHideJob = scope.launch {
            delay(delayMs)
            if (state == State.PASTED) {
                hideXButton()
                setState(State.IDLE)
                checkAndHideBubbleIfKeyboardClosed(animate = true)
            }
        }
    }

    private fun onTranscriptionDone(text: String) {
        if (text.isBlank()) {
            setState(State.IDLE)
            hideXButton()
            showPreviewText(getString(R.string.preview_no_text_detected), autoHide = true)
            return
        }

        // 透過無障礙服務安全直接填入（避免污染剪貼簿）
        val accService = VoiceAccessibilityService.instance
        val injected = accService?.inputText(text) ?: false

        if (injected) {
            // §6 貼上完成後進入 PASTED 狀態，顯示 X 鍵供 10 秒內 Undo
            enterPastedState()
        } else {
            // 若未找到焦點輸入框或無障礙服務不可用，備援複製至剪貼簿（具備 EXTRA_IS_SENSITIVE 隱私標記）
            copyToClipboardFallback(text)
            setState(State.IDLE)
            hideXButton()
            showPreviewText(getString(R.string.preview_copied), autoHide = true)
        }
    }

    internal fun setState(s: State) {
        stateMachine.transitionTo(s)
        if (state == State.RECORDING) {
            showPulseView()
        } else {
            hidePulseView()
        }
        if (!::btnBubbleMic.isInitialized) return
        when (state) {
            State.IDLE -> {
                btnBubbleMic.setBackgroundResource(R.drawable.bubble_background)
                updateBubbleIconForMode(currentMode)
                ivBubbleIcon.visibility = View.VISIBLE
                progressBubble.visibility = View.GONE
            }
            State.LOADING -> {
                btnBubbleMic.setBackgroundResource(R.drawable.bubble_background)
                ivBubbleIcon.visibility = View.GONE
                progressBubble.visibility = View.VISIBLE
            }
            State.RECORDING -> {
                btnBubbleMic.setBackgroundResource(R.drawable.bubble_background_active)
                ivBubbleIcon.setImageResource(R.drawable.ic_mic_active)
                ivBubbleIcon.visibility = View.VISIBLE
                progressBubble.visibility = View.GONE
            }
            State.TRANSCRIBING -> {
                btnBubbleMic.setBackgroundResource(R.drawable.bubble_background_active)
                ivBubbleIcon.visibility = View.GONE
                progressBubble.visibility = View.VISIBLE
            }
            State.PASTED -> {
                btnBubbleMic.setBackgroundResource(R.drawable.bubble_background)
                updateBubbleIconForMode(currentMode)
                ivBubbleIcon.visibility = View.VISIBLE
                progressBubble.visibility = View.GONE
            }
            State.SCANNING -> {
                btnBubbleMic.setBackgroundResource(R.drawable.bubble_background)
                updateBubbleIconForMode(currentMode)
                ivBubbleIcon.visibility = View.VISIBLE
                progressBubble.visibility = View.GONE
            }
        }
    }

    internal fun showPastedStateForTest() {
        enterPastedState(15_000)
    }

    private fun getIconAndDescForMode(mode: Int): Pair<Int, String> {
        return when (mode) {
            MODE_OCR -> R.drawable.ic_ocr to "懸浮文字辨識"
            MODE_BARCODE -> R.drawable.ic_barcode to "懸浮條碼掃描"
            MODE_PHRASES -> R.drawable.ic_quick_phrases to "常用語抽屜"
            else -> R.drawable.ic_mic to "懸浮語音輸入"
        }
    }

    private fun updateBubbleIconForMode(mode: Int) {
        if (!::ivBubbleIcon.isInitialized) return
        val (iconRes, desc) = getIconAndDescForMode(mode)
        ivBubbleIcon.setImageResource(iconRes)
        ivBubbleIcon.contentDescription = desc
    }

    private fun animateModeIconIntoBubble(mode: Int) {
        if (!::ivBubbleIcon.isInitialized) return
        updateBubbleIconForMode(mode)
        ivBubbleIcon.scaleX = 0.3f
        ivBubbleIcon.scaleY = 0.3f
        ivBubbleIcon.alpha = 0.6f
        ivBubbleIcon.animate()
            .scaleX(1.2f)
            .scaleY(1.2f)
            .alpha(1f)
            .setDuration(160)
            .withEndAction {
                ivBubbleIcon.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(100)
                    .start()
            }
            .start()
    }

    private fun qwen3NeedsReload(): Boolean =
        buildHotwords() != qwen3Asr.loadedHotwords

    private fun buildHotwords(): String =
        UserDictionary.load(this).values.distinct().sorted().joinToString("\n")

    private fun postProcess(raw: String): String {
        val converted = if (ModelConfig.selectedEngine(this) == ModelConfig.ENGINE_X_ASR && !isDualEngineActive()) {
            ModelConfig.normalizeTaiwanVariants(raw)
        } else {
            ModelConfig.toTaiwanTraditional(raw)
        }
        val userReplaced = UserDictionary.apply(converted, UserDictionary.load(this))
        val postReplaced = ModelConfig.applyPostRegexReplacements(userReplaced)
        return if (ModelConfig.isFilterPunctuationEnabled(this)) {
            ModelConfig.filterChinesePunctuation(postReplaced)
        } else {
            postReplaced
        }
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    // ══════════════════════════════════════════════════════════════════
    // Capsule Menu Methods
    // ══════════════════════════════════════════════════════════════════
    private fun showCapsuleMenu(startRawY: Float = 0f) {
        if (isCapsuleMenuShowing) return
        if (isTucked) {
            untuckBubble(animate = false)
        }
        hideXButtonImmediately()
        btnBubbleMic.alpha = 0f
        val density = resources.displayMetrics.density
        capsuleMenuController.show(
            bubbleX = windowLayoutParams.x,
            bubbleY = windowLayoutParams.y,
            bubbleWidthPx = (60 * density).toInt(),
            bubbleHeightPx = (60 * density).toInt(),
            mode = currentMode,
            startRawY = startRawY
        )
    }

    private fun dismissCapsuleMenu() {
        if (::capsuleMenuController.isInitialized) {
            capsuleMenuController.dismiss()
        }
    }

    private fun applyCapsuleSelection(mode: Int) {
        hidePreviewText()
        currentMode = mode
        getSharedPreferences(PREF_BUBBLE_MODE, Context.MODE_PRIVATE).edit().putInt(KEY_MODE, mode).apply()
        HapticUtil.click(this)
        when (mode) {
            MODE_VOICE -> {
                setState(State.IDLE)
                showPreviewText(getString(R.string.preview_mode_voice), autoHide = true)
                stopScannerMode()
                stopOcrMode()
                closePhrasesDrawer()
            }
            MODE_OCR -> {
                setState(State.IDLE)
                showPreviewText(getString(R.string.preview_mode_ocr), autoHide = true)
                stopScannerMode()
                closePhrasesDrawer()
            }
            MODE_BARCODE -> {
                setState(State.IDLE)
                showPreviewText(getString(R.string.preview_mode_barcode), autoHide = true)
                stopOcrMode()
                closePhrasesDrawer()
            }
            MODE_PHRASES -> {
                setState(State.IDLE)
                showPreviewText(getString(R.string.preview_mode_phrases), autoHide = true)
                stopScannerMode()
                stopOcrMode()
                openPhrasesDrawer()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Scanner Mode Methods
    // ══════════════════════════════════════════════════════════════════
    private fun toggleScannerMode() {
        if (isScannerModeActive) {
            stopScannerMode()
        } else {
            startScannerMode()
        }
    }

    private fun startScannerMode() {
        hidePreviewText()
        if (isTucked) {
            untuckBubble(animate = false)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.toast_camera_permission_scan, Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            return
        }

        if (isOcrModeActive) {
            stopOcrMode()
        }
        if (isPhrasesDrawerActive) {
            closePhrasesDrawer()
        }

        if (scannerView == null) {
            scannerView = LayoutInflater.from(themedCtx).inflate(R.layout.layout_floating_scanner, null)
        }
        val scanner = scannerView ?: return

        val density = resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()
        val gap = (10 * density).toInt()
        val screenHeight = getScreenHeight()
        val screenWidth = getScreenWidth()

        isScannerEnlarged = ModelConfig.isCameraEnlarged(this, MODE_BARCODE)
        val scannerWidth = if (isScannerEnlarged) {
            val maxW = screenWidth - bubbleWidthPx - gap - (12 * density).toInt()
            (340 * density).toInt().coerceIn((260 * density).toInt(), maxW)
        } else {
            (250 * density).toInt()
        }
        val cardSize = scannerWidth - (16 * density).toInt()

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val estimatedHeight = cardSize + (120 * density).toInt()
        val micY = windowLayoutParams.y + (30 * density).toInt()
        val targetY = (micY - (estimatedHeight / 2)).coerceIn(
            getMinY(),
            maxOf(getMinY(), screenHeight - estimatedHeight - (16 * density).toInt())
        )

        scannerLayoutParams = WindowManager.LayoutParams(
            scannerWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (isDockedOnRight) {
                gravity = Gravity.TOP or Gravity.RIGHT
                x = bubbleWidthPx + gap
            } else {
                gravity = Gravity.TOP or Gravity.LEFT
                x = bubbleWidthPx + gap
            }
            y = targetY
        }

        val btnClose = scanner.findViewById<ImageButton>(R.id.btn_close_scanner)
        val btnScale = scanner.findViewById<ImageButton>(R.id.btn_scanner_scale)
        val btnFlash = scanner.findViewById<ImageButton>(R.id.btn_scanner_flash)
        val btnAutoEnter = scanner.findViewById<ImageButton>(R.id.btn_scanner_auto_enter)
        val sliderZoom = scanner.findViewById<SeekBar>(R.id.slider_scanner_zoom)
        val viewFinder = scanner.findViewById<PreviewView>(R.id.scanner_view_finder)
        val cardPreview = scanner.findViewById<CardView>(R.id.card_scanner_preview)

        btnScale?.setImageResource(if (isScannerEnlarged) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
        cardPreview?.layoutParams = cardPreview?.layoutParams?.apply {
            width = cardSize
            height = cardSize
        }

        btnClose.setOnClickListener {
            HapticUtil.click(this)
            stopScannerMode()
        }

        btnScale?.setOnClickListener {
            HapticUtil.click(this)
            setScannerEnlarged(!isScannerEnlarged)
        }

        btnFlash.setOnClickListener {
            HapticUtil.click(this)
            isFlashOn = !isFlashOn
            camera?.cameraControl?.enableTorch(isFlashOn)
            btnFlash.setImageResource(if (isFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        }

        val isAutoEnter = ModelConfig.isOcrAutoEnterEnabled(this)
        btnAutoEnter.setImageResource(if (isAutoEnter) R.drawable.ic_auto_enter_on else R.drawable.ic_auto_enter_off)
        btnAutoEnter.setOnClickListener {
            HapticUtil.click(this)
            val nextState = !ModelConfig.isOcrAutoEnterEnabled(this)
            ModelConfig.setOcrAutoEnterEnabled(this, nextState)
            btnAutoEnter.setImageResource(if (nextState) R.drawable.ic_auto_enter_on else R.drawable.ic_auto_enter_off)
            Toast.makeText(this, if (nextState) R.string.toast_auto_enter_enabled else R.string.toast_auto_enter_disabled, Toast.LENGTH_SHORT).show()
        }

        val savedZoom = ModelConfig.getCameraZoom(this, MODE_BARCODE)
        sliderZoom.progress = (savedZoom * 100f).roundToInt().coerceIn(0, 100)

        sliderZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val zoom = progress / 100f
                    camera?.cameraControl?.setLinearZoom(zoom)
                    ModelConfig.setCameraZoom(this@FloatingBubbleService, MODE_BARCODE, zoom)
                    HapticUtil.tick(this@FloatingBubbleService)
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

        windowManager.addView(scanner, scannerLayoutParams)
        isScannerModeActive = true
        showXButton()

        scanner.animate()
            .alpha(1f)
            .translationX(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(240)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()

        startCameraForScanner(viewFinder)
    }

    private fun setScannerEnlarged(enlarged: Boolean) {
        isScannerEnlarged = enlarged
        ModelConfig.setCameraEnlarged(this, MODE_BARCODE, enlarged)
        val scanner = scannerView ?: return
        val lp = scannerLayoutParams ?: return
        val density = resources.displayMetrics.density
        val screenWidth = getScreenWidth()
        val screenHeight = getScreenHeight()
        val bubbleWidthPx = (60 * density).toInt()
        val gap = (10 * density).toInt()

        val targetWidth = if (enlarged) {
            val maxW = screenWidth - bubbleWidthPx - gap - (12 * density).toInt()
            (340 * density).toInt().coerceIn((260 * density).toInt(), maxW)
        } else {
            (250 * density).toInt()
        }

        val cardSize = targetWidth - (16 * density).toInt()
        val cardPreview = scanner.findViewById<CardView>(R.id.card_scanner_preview)
        cardPreview?.layoutParams?.apply {
            width = cardSize
            height = cardSize
        }
        cardPreview?.requestLayout()

        lp.width = targetWidth
        val btnScale = scanner.findViewById<ImageButton>(R.id.btn_scanner_scale)
        btnScale?.setImageResource(if (enlarged) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)

        val estimatedHeight = cardSize + (120 * density).toInt()
        val micY = windowLayoutParams.y + (30 * density).toInt()
        val targetY = (micY - (estimatedHeight / 2)).coerceIn(
            getMinY(),
            maxOf(getMinY(), screenHeight - estimatedHeight - (16 * density).toInt())
        )
        lp.y = targetY

        if (scanner.isAttachedToWindow) {
            windowManager.updateViewLayout(scanner, lp)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPinchToZoom(viewFinder: PreviewView, sliderZoom: SeekBar, mode: Int) {
        val scaleGestureDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val currentZoom = camera?.cameraInfo?.zoomState?.value?.linearZoom
                        ?: (sliderZoom.progress / 100f)
                    val delta = (detector.scaleFactor - 1.0f) * 1.5f
                    val newZoom = (currentZoom + delta).coerceIn(0f, 1f)
                    camera?.cameraControl?.setLinearZoom(newZoom)
                    sliderZoom.progress = (newZoom * 100f).roundToInt().coerceIn(0, 100)
                    ModelConfig.setCameraZoom(this@FloatingBubbleService, mode, newZoom)
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

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
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
                    this@FloatingBubbleService,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                val savedZoom = ModelConfig.getCameraZoom(this@FloatingBubbleService, MODE_BARCODE)
                camera?.cameraControl?.setLinearZoom(savedZoom)
                val sliderZoom = scannerView?.findViewById<SeekBar>(R.id.slider_scanner_zoom)
                if (sliderZoom != null) {
                    setupPinchToZoom(viewFinder, sliderZoom, MODE_BARCODE)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera initialization failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopScannerMode(hideX: Boolean = true) {
        val scanner = scannerView ?: return
        if (!isScannerModeActive) return
        isScannerModeActive = false
        isFlashOn = false

        if (hideX && state != State.PASTED) {
            hideXButton()
        }
        if (state != State.PASTED) {
            setState(State.IDLE)
        }

        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unbind cameraProvider in stopScannerMode", e)
        }

        val density = resources.displayMetrics.density
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
                checkAndHideBubbleIfKeyboardClosed(animate = true)
            }
            .start()
    }

    // ══════════════════════════════════════════════════════════════════
    // Dedicated OCR Mode Window Methods
    // ══════════════════════════════════════════════════════════════════
    private fun toggleOcrMode() {
        if (isOcrModeActive) {
            stopOcrMode()
        } else {
            startOcrMode()
        }
    }

    private fun startOcrMode() {
        hidePreviewText()
        if (isTucked) {
            untuckBubble(animate = false)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.toast_camera_permission_ocr, Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            return
        }

        if (isScannerModeActive) {
            stopScannerMode()
        }
        if (isPhrasesDrawerActive) {
            closePhrasesDrawer()
        }

        if (ocrWindowView == null) {
            ocrWindowView = LayoutInflater.from(themedCtx).inflate(R.layout.layout_floating_ocr, null)
        }
        val ocr = ocrWindowView ?: return

        val density = resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()
        val gap = (10 * density).toInt()
        val screenHeight = getScreenHeight()
        val screenWidth = getScreenWidth()

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        isOcrEnlarged = ModelConfig.isCameraEnlarged(this, MODE_OCR)
        val ocrWidth = if (isOcrEnlarged) {
            val maxW = screenWidth - bubbleWidthPx - gap - (12 * density).toInt()
            (340 * density).toInt().coerceIn((260 * density).toInt(), maxW)
        } else {
            (250 * density).toInt()
        }

        val cardSize = ocrWidth - (16 * density).toInt()
        val estimatedHeight = cardSize + (120 * density).toInt()
        val micY = windowLayoutParams.y + (30 * density).toInt()
        val targetY = (micY - (estimatedHeight / 2)).coerceIn(
            getMinY(),
            maxOf(getMinY(), screenHeight - estimatedHeight - (16 * density).toInt())
        )

        ocrWindowLayoutParams = WindowManager.LayoutParams(
            ocrWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (isDockedOnRight) {
                gravity = Gravity.TOP or Gravity.RIGHT
                x = bubbleWidthPx + gap
            } else {
                gravity = Gravity.TOP or Gravity.LEFT
                x = bubbleWidthPx + gap
            }
            y = targetY
        }

        val btnClose = ocr.findViewById<ImageButton>(R.id.btn_close_ocr)
        val btnScale = ocr.findViewById<ImageButton>(R.id.btn_ocr_scale)
        val btnFlash = ocr.findViewById<ImageButton>(R.id.btn_ocr_flash)
        val btnAutoEnter = ocr.findViewById<ImageButton>(R.id.btn_ocr_auto_enter)
        val btnShutter = ocr.findViewById<FrameLayout>(R.id.btn_ocr_shutter)
        val sliderZoom = ocr.findViewById<SeekBar>(R.id.slider_ocr_zoom)
        val viewFinder = ocr.findViewById<PreviewView>(R.id.ocr_view_finder)
        val cardPreview = ocr.findViewById<CardView>(R.id.card_ocr_preview)

        btnScale.setImageResource(if (isOcrEnlarged) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
        cardPreview.layoutParams = cardPreview.layoutParams.apply {
            width = cardSize
            height = cardSize
        }

        btnClose.setOnClickListener {
            HapticUtil.click(this)
            stopOcrMode()
        }

        btnScale.setOnClickListener {
            HapticUtil.click(this)
            setOcrEnlarged(!isOcrEnlarged)
        }

        btnFlash.setOnClickListener {
            HapticUtil.click(this)
            isFlashOn = !isFlashOn
            camera?.cameraControl?.enableTorch(isFlashOn)
            btnFlash.setImageResource(if (isFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        }

        val isAutoEnter = ModelConfig.isOcrAutoEnterEnabled(this)
        btnAutoEnter.setImageResource(if (isAutoEnter) R.drawable.ic_auto_enter_on else R.drawable.ic_auto_enter_off)
        btnAutoEnter.setOnClickListener {
            HapticUtil.click(this)
            val nextState = !ModelConfig.isOcrAutoEnterEnabled(this)
            ModelConfig.setOcrAutoEnterEnabled(this, nextState)
            btnAutoEnter.setImageResource(if (nextState) R.drawable.ic_auto_enter_on else R.drawable.ic_auto_enter_off)
            Toast.makeText(this, if (nextState) R.string.toast_auto_enter_enabled else R.string.toast_auto_enter_disabled, Toast.LENGTH_SHORT).show()
        }

        val savedZoom = ModelConfig.getCameraZoom(this, MODE_OCR)
        sliderZoom.progress = (savedZoom * 100f).roundToInt().coerceIn(0, 100)

        sliderZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val zoom = progress / 100f
                    camera?.cameraControl?.setLinearZoom(zoom)
                    ModelConfig.setCameraZoom(this@FloatingBubbleService, MODE_OCR, zoom)
                    HapticUtil.tick(this@FloatingBubbleService)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnShutter.setOnClickListener {
            HapticUtil.heavyClick(this)
            if (!isOcrEnlarged) {
                setOcrEnlarged(true)
            }
            triggerOcrSnapshot()
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

        windowManager.addView(ocr, ocrWindowLayoutParams)
        isOcrModeActive = true
        showXButton()

        ocr.animate()
            .alpha(1f)
            .translationX(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(240)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()

        startCameraForOcr(viewFinder)
    }

    private fun setOcrEnlarged(enlarged: Boolean) {
        isOcrEnlarged = enlarged
        ModelConfig.setCameraEnlarged(this, MODE_OCR, enlarged)
        val ocr = ocrWindowView ?: return
        val lp = ocrWindowLayoutParams ?: return
        val density = resources.displayMetrics.density
        val screenWidth = getScreenWidth()
        val screenHeight = getScreenHeight()
        val bubbleWidthPx = (60 * density).toInt()
        val gap = (10 * density).toInt()

        val targetWidth = if (enlarged) {
            val maxW = screenWidth - bubbleWidthPx - gap - (12 * density).toInt()
            (340 * density).toInt().coerceIn((260 * density).toInt(), maxW)
        } else {
            (250 * density).toInt()
        }

        val cardSize = targetWidth - (16 * density).toInt()
        val cardPreview = ocr.findViewById<CardView>(R.id.card_ocr_preview)
        cardPreview?.layoutParams?.apply {
            width = cardSize
            height = cardSize
        }
        cardPreview?.requestLayout()

        lp.width = targetWidth
        val btnScale = ocr.findViewById<ImageButton>(R.id.btn_ocr_scale)
        btnScale?.setImageResource(if (enlarged) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)

        val estimatedHeight = cardSize + (120 * density).toInt()
        val micY = windowLayoutParams.y + (30 * density).toInt()
        val targetY = (micY - (estimatedHeight / 2)).coerceIn(
            getMinY(),
            maxOf(getMinY(), screenHeight - estimatedHeight - (16 * density).toInt())
        )
        lp.y = targetY

        if (ocr.isAttachedToWindow) {
            windowManager.updateViewLayout(ocr, lp)
        }
    }

    private fun stopOcrMode(hideX: Boolean = true) {
        val ocr = ocrWindowView ?: return
        if (!isOcrModeActive) return
        isOcrModeActive = false
        isFlashOn = false

        if (hideX && state != State.PASTED) {
            hideXButton()
        }

        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unbind cameraProvider in stopOcrMode", e)
        }

        val density = resources.displayMetrics.density
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
                checkAndHideBubbleIfKeyboardClosed(animate = true)
            }
            .start()
    }

    private fun startCameraForOcr(viewFinder: PreviewView) {
        if (cameraExecutor == null || cameraExecutor?.isShutdown == true) {
            cameraExecutor = Executors.newSingleThreadExecutor()
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    this@FloatingBubbleService,
                    cameraSelector,
                    preview
                )
                val savedZoom = ModelConfig.getCameraZoom(this@FloatingBubbleService, MODE_OCR)
                camera?.cameraControl?.setLinearZoom(savedZoom)
                val sliderZoom = ocrWindowView?.findViewById<SeekBar>(R.id.slider_ocr_zoom)
                if (sliderZoom != null) {
                    setupPinchToZoom(viewFinder, sliderZoom, MODE_OCR)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera initialization for OCR failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeBarcodeImage(imageProxy: ImageProxy) {
        if (isProcessingBarcode || isOcrSnapshotActive || !isScannerModeActive) {
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
                    onBarcodeDetected(result.text)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Barcode decode error: ${e.message}", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun onBarcodeDetected(code: String) {
        HapticUtil.heavyClick(this)
        val injected = VoiceAccessibilityService.instance?.inputText(code) ?: false
        if (injected) {
            if (ModelConfig.isOcrAutoEnterEnabled(this)) {
                Handler(Looper.getMainLooper()).postDelayed({
                    VoiceAccessibilityService.instance?.sendEnterKey()
                }, 100)
            }
            enterPastedState()
        } else {
            copyToClipboardFallback(code)
            setState(State.IDLE)
            hideXButton()
            showPreviewText(getString(R.string.preview_copied_barcode), autoHide = true)
        }
        stopScannerMode(hideX = !injected)

        Handler(Looper.getMainLooper()).postDelayed({
            isProcessingBarcode = false
        }, 800)
    }

    // ══════════════════════════════════════════════════════════════════
    // PP-OCR Snapshot Methods
    // ══════════════════════════════════════════════════════════════════
    private fun triggerOcrSnapshot() {
        if (!ModelConfig.isOcrReady(this)) {
            Toast.makeText(this, R.string.toast_ocr_model_not_downloaded, Toast.LENGTH_LONG).show()
            val intent = Intent(this, ImeSettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            return
        }

        val viewFinder = ocrWindowView?.findViewById<PreviewView>(R.id.ocr_view_finder)
            ?: scannerView?.findViewById<PreviewView>(R.id.scanner_view_finder)
            ?: return
        val rawBitmap = viewFinder.bitmap ?: run {
            Toast.makeText(this, R.string.toast_camera_capture_failed, Toast.LENGTH_SHORT).show()
            return
        }

        // 限制截圖最大邊長以避免高解析度相機畫面導致 OOM
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

        showOcrSnapshotView(bitmap)
    }

    private fun showOcrSnapshotView(bitmap: Bitmap) {
        currentSnapshotBitmap?.let {
            if (!it.isRecycled) it.recycle()
        }
        currentSnapshotBitmap = bitmap

        if (ocrSnapshotView == null) {
            ocrSnapshotView = LayoutInflater.from(themedCtx).inflate(R.layout.layout_ocr_snapshot, null)
        }
        val ocrView = ocrSnapshotView ?: return

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        ocrSnapshotLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
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
            HapticUtil.click(this)
            dismissOcrSnapshot()
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
                tvStatus.text = "點選欲輸入之文字區塊 (可多選)"
            }
        }

        boxesOverlay.onQuickConfirm = { selectedBox, _ ->
            HapticUtil.click(this@FloatingBubbleService)
            val currentBitmap = currentSnapshotBitmap ?: bitmap
            onOcrBoxesSelected(currentBitmap, listOf(selectedBox), tvStatus, progress)
        }

        btnSelectAll.setOnClickListener {
            HapticUtil.click(this@FloatingBubbleService)
            boxesOverlay.selectAll()
        }

        btnClear.setOnClickListener {
            HapticUtil.click(this@FloatingBubbleService)
            boxesOverlay.clearSelection()
        }

        btnConfirm.setOnClickListener {
            HapticUtil.click(this@FloatingBubbleService)
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
                        tvStatus.text = "點選欲輸入之文字區塊 (可多選，共 ${detectedRects.size} 處)"
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
            HapticUtil.click(this)
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

        windowManager.addView(ocrView, ocrSnapshotLayoutParams)
        isOcrSnapshotActive = true

        frameContainer.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(240)
            .setInterpolator(DecelerateInterpolator())
            .start()

        startDetection(bitmap)
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
                val results = mutableListOf<String>()
                withContext(Dispatchers.Default) {
                    for (box in boxes) {
                        val text = ppOcrEngine.recognizeBox(bitmap, box.originalRect)
                        if (text.isNotBlank()) {
                            results.add(text.trim())
                        }
                    }
                }

                progress.visibility = View.GONE
                if (results.isNotEmpty()) {
                    val sep = ModelConfig.ocrSeparator(this@FloatingBubbleService)
                    val processed = results.joinToString(sep)
                    HapticUtil.heavyClick(this@FloatingBubbleService)

                    dismissOcrSnapshot(hideX = false)
                    stopOcrMode(hideX = false)
                    stopScannerMode(hideX = false)

                    val injected = VoiceAccessibilityService.instance?.inputText(processed) ?: false
                    if (injected) {
                        if (ModelConfig.isOcrAutoEnterEnabled(this@FloatingBubbleService)) {
                            delay(100)
                            VoiceAccessibilityService.instance?.sendEnterKey()
                        }
                        enterPastedState()
                    } else {
                        copyToClipboardFallback(processed)
                        setState(State.IDLE)
                        hideXButton()
                        val previewMsg = if (processed.length > 25) "${processed.take(25)}…" else processed
                        showPreviewText(getString(R.string.preview_copied_with_text, previewMsg), autoHide = true)
                    }
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

    private fun dismissOcrSnapshot(hideX: Boolean = true) {
        val ocrView = ocrSnapshotView ?: return
        if (!isOcrSnapshotActive) return
        isOcrSnapshotActive = false

        if (hideX && !isOcrModeActive && state != State.PASTED) {
            hideXButton()
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
                checkAndHideBubbleIfKeyboardClosed(animate = true)
            }
            .start()
    }

    // ══════════════════════════════════════════════════════════════════
    // Quick Phrases Drawer Methods
    // ══════════════════════════════════════════════════════════════════
    private fun togglePhrasesDrawer() {
        if (isPhrasesDrawerActive) {
            closePhrasesDrawer()
        } else {
            openPhrasesDrawer()
        }
    }

    private fun openPhrasesDrawer() {
        hidePreviewText()
        if (isTucked) {
            untuckBubble(animate = false)
        }
        if (isScannerModeActive) {
            stopScannerMode()
        }
        if (isOcrModeActive) {
            stopOcrMode()
        }

        if (phrasesDrawerView == null) {
            phrasesDrawerView = LayoutInflater.from(themedCtx).inflate(R.layout.layout_floating_phrases_drawer, null)
        }
        val drawer = phrasesDrawerView ?: return

        val density = resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()
        val gap = (10 * density).toInt()
        val screenHeight = getScreenHeight()
        val drawerWidth = (260 * density).toInt()

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val estimatedHeight = (280 * density).toInt()
        val micY = windowLayoutParams.y + (30 * density).toInt()
        val targetY = (micY - (estimatedHeight / 2)).coerceIn(
            getMinY(),
            maxOf(getMinY(), screenHeight - estimatedHeight - (16 * density).toInt())
        )

        phrasesDrawerLayoutParams = WindowManager.LayoutParams(
            drawerWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (isDockedOnRight) {
                gravity = Gravity.TOP or Gravity.RIGHT
                x = bubbleWidthPx + gap
            } else {
                gravity = Gravity.TOP or Gravity.LEFT
                x = bubbleWidthPx + gap
            }
            y = targetY
        }

        val btnClose = drawer.findViewById<ImageButton>(R.id.btn_close_phrases_drawer)
        val btnAdd = drawer.findViewById<ImageButton>(R.id.btn_add_phrase)
        val rvPhrases = drawer.findViewById<RecyclerView>(R.id.rv_phrases)

        btnClose.setOnClickListener {
            HapticUtil.click(this)
            closePhrasesDrawer()
        }

        btnAdd.setOnClickListener {
            HapticUtil.click(this)
            showAddPhraseDialog()
        }

        phrasesList = QuickPhrasesManager.load(this).toMutableList()
        quickPhrasesAdapter = QuickPhrasesAdapter(
            phrases = phrasesList,
            onItemClick = { phrase ->
                insertQuickPhrase(phrase)
            },
            onEditClick = { index, phrase ->
                HapticUtil.click(this)
                showEditPhraseDialog(index, phrase)
            },
            onDeleteClick = { index, phrase ->
                HapticUtil.click(this)
                showDeletePhraseDialog(index, phrase)
            }
        )
        rvPhrases.layoutManager = LinearLayoutManager(themedCtx)
        rvPhrases.adapter = quickPhrasesAdapter
        refreshPhrasesList()

        if (isDockedOnRight) {
            drawer.pivotX = drawerWidth.toFloat()
            drawer.translationX = (40 * density)
        } else {
            drawer.pivotX = 0f
            drawer.translationX = -(40 * density)
        }
        drawer.pivotY = (140 * density)
        drawer.alpha = 0f
        drawer.scaleX = 0.7f
        drawer.scaleY = 0.7f

        try {
            windowManager.addView(drawer, phrasesDrawerLayoutParams)
            isPhrasesDrawerActive = true
            showXButton()

            drawer.animate()
                .alpha(1f)
                .translationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(240)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .start()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add phrasesDrawerView", e)
        }
    }

    private fun closePhrasesDrawer(hideX: Boolean = true) {
        if (!isPhrasesDrawerActive) return
        isPhrasesDrawerActive = false
        if (hideX) {
            hideXButton()
        }
        val drawer = phrasesDrawerView ?: return
        val density = resources.displayMetrics.density
        val targetTranslationX = if (isDockedOnRight) (40 * density) else -(40 * density)
        drawer.animate()
            .alpha(0f)
            .translationX(targetTranslationX)
            .scaleX(0.7f)
            .scaleY(0.7f)
            .setDuration(180)
            .withEndAction {
                if (drawer.isAttachedToWindow) {
                    try {
                        windowManager.removeView(drawer)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to remove phrasesDrawerView", e)
                    }
                }
                phrasesDrawerView = null
                checkAndHideBubbleIfKeyboardClosed(animate = true)
            }
            .start()
    }

    private fun updatePhrasesDrawerPosition() {
        val drawer = phrasesDrawerView ?: return
        val lp = phrasesDrawerLayoutParams ?: return
        val density = resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()
        val gap = (10 * density).toInt()
        val screenHeight = getScreenHeight()
        val estimatedHeight = (280 * density).toInt()
        val micY = windowLayoutParams.y + (30 * density).toInt()

        if (isDockedOnRight) {
            lp.gravity = Gravity.TOP or Gravity.RIGHT
            lp.x = bubbleWidthPx + gap
        } else {
            lp.gravity = Gravity.TOP or Gravity.LEFT
            lp.x = bubbleWidthPx + gap
        }
        lp.y = (micY - (estimatedHeight / 2)).coerceIn(
            getMinY(),
            maxOf(getMinY(), screenHeight - estimatedHeight - (16 * density).toInt())
        )
        if (drawer.isAttachedToWindow) {
            try {
                windowManager.updateViewLayout(drawer, lp)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update phrasesDrawerView layout", e)
            }
        }
    }

    private fun refreshPhrasesList() {
        val drawer = phrasesDrawerView ?: return
        val emptyView = drawer.findViewById<View>(R.id.ll_empty_phrases)
        val rvPhrases = drawer.findViewById<RecyclerView>(R.id.rv_phrases) ?: return

        phrasesList.clear()
        phrasesList.addAll(QuickPhrasesManager.load(this))

        if (phrasesList.isEmpty()) {
            emptyView?.visibility = View.VISIBLE
            rvPhrases.visibility = View.GONE
        } else {
            emptyView?.visibility = View.GONE
            rvPhrases.visibility = View.VISIBLE
            val density = resources.displayMetrics.density
            rvPhrases.layoutParams = rvPhrases.layoutParams.apply {
                height = if (phrasesList.size >= 5) {
                    (260 * density).toInt()
                } else {
                    ViewGroup.LayoutParams.WRAP_CONTENT
                }
            }
            quickPhrasesAdapter?.notifyDataSetChanged()
        }
    }

    private fun insertQuickPhrase(phrase: String) {
        HapticUtil.click(this)
        val injected = VoiceAccessibilityService.instance?.inputText(phrase) ?: false
        if (injected) {
            showPreviewText(getString(R.string.preview_inserted_phrase), autoHide = true)
            enterPastedState()
        } else {
            copyToClipboardFallback(phrase)
            setState(State.IDLE)
            showPreviewText(getString(R.string.toast_fallback_clipboard), autoHide = true)
        }
        closePhrasesDrawer(hideX = !injected)
    }

    private fun showAddPhraseDialog() {
        val dialogView = LayoutInflater.from(themedCtx).inflate(R.layout.dialog_phrase_entry, null)
        val etPhrase = dialogView.findViewById<EditText>(R.id.et_phrase)

        val dialog = MaterialAlertDialogBuilder(themedCtx)
            .setTitle(R.string.dialog_phrase_add_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                HapticUtil.click(this)
                val text = etPhrase.text.toString().trim()
                if (text.isNotBlank()) {
                    QuickPhrasesManager.add(this, text)
                    Toast.makeText(this, R.string.toast_phrase_added, Toast.LENGTH_SHORT).show()
                    refreshPhrasesList()
                }
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                HapticUtil.click(this)
            }
            .create()

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        dialog.window?.setType(windowType)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        etPhrase.requestFocus()
    }

    private fun showEditPhraseDialog(index: Int, currentPhrase: String) {
        val dialogView = LayoutInflater.from(themedCtx).inflate(R.layout.dialog_phrase_entry, null)
        val etPhrase = dialogView.findViewById<EditText>(R.id.et_phrase)
        etPhrase.setText(currentPhrase)
        etPhrase.setSelection(currentPhrase.length)

        val dialog = MaterialAlertDialogBuilder(themedCtx)
            .setTitle(R.string.dialog_phrase_edit_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                HapticUtil.click(this)
                val text = etPhrase.text.toString().trim()
                if (text.isNotBlank()) {
                    QuickPhrasesManager.update(this, index, text)
                    Toast.makeText(this, R.string.toast_phrase_updated, Toast.LENGTH_SHORT).show()
                    refreshPhrasesList()
                }
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                HapticUtil.click(this)
            }
            .create()

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        dialog.window?.setType(windowType)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        etPhrase.requestFocus()
    }

    private fun showDeletePhraseDialog(index: Int, phrase: String) {
        val dialog = MaterialAlertDialogBuilder(themedCtx)
            .setTitle(R.string.dialog_phrase_delete_title)
            .setMessage(getString(R.string.dialog_phrase_delete_confirm, phrase))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                HapticUtil.click(this)
                QuickPhrasesManager.removeAt(this, index)
                Toast.makeText(this, R.string.toast_phrase_deleted, Toast.LENGTH_SHORT).show()
                refreshPhrasesList()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                HapticUtil.click(this)
            }
            .create()

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        dialog.window?.setType(windowType)
        dialog.show()
    }

    private class QuickPhrasesAdapter(
        private val phrases: MutableList<String>,
        private val onItemClick: (String) -> Unit,
        private val onEditClick: (Int, String) -> Unit,
        private val onDeleteClick: (Int, String) -> Unit
    ) : RecyclerView.Adapter<QuickPhrasesAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val root: View = view.findViewById(R.id.layout_phrase_item)
            val tvText: TextView = view.findViewById(R.id.tv_phrase_text)
            val btnEdit: ImageButton = view.findViewById(R.id.btn_edit_phrase)
            val btnDelete: ImageButton = view.findViewById(R.id.btn_delete_phrase)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_quick_phrase, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val phrase = phrases[position]
            holder.tvText.text = phrase
            holder.root.setOnClickListener {
                onItemClick(phrase)
            }
            holder.btnEdit.setOnClickListener {
                onEditClick(holder.bindingAdapterPosition, phrase)
            }
            holder.btnDelete.setOnClickListener {
                onDeleteClick(holder.bindingAdapterPosition, phrase)
            }
        }

        override fun getItemCount(): Int = phrases.size
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::bubbleView.isInitialized && bubbleView.isAttachedToWindow) {
            val screenWidth = getScreenWidth()
            val bubbleWidthPx = (60 * resources.displayMetrics.density).toInt()
            val finalX = if (isTucked) {
                if (isDockedOnRight) getTuckRightX() else getTuckLeftX()
            } else {
                if (isDockedOnRight) (screenWidth - bubbleWidthPx) else 0
            }
            val minY = getMinY()
            val maxY = getMaxY()
            val finalY = windowLayoutParams.y.coerceIn(minY, maxY)
            if (windowLayoutParams.x != finalX || windowLayoutParams.y != finalY) {
                windowLayoutParams.x = finalX
                windowLayoutParams.y = finalY
                windowManager.updateViewLayout(bubbleView, windowLayoutParams)
            }
            updateXButtonPosition()
            updatePulsePosition()
            updatePreviewPosition()
            updateSystemGestureExclusion()
        }
        if (isScannerModeActive) {
            setScannerEnlarged(isScannerEnlarged)
        }
        if (isOcrModeActive) {
            setOcrEnlarged(isOcrEnlarged)
        }
        if (isPhrasesDrawerActive) {
            updatePhrasesDrawerPosition()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (instance === this) instance = null
            isRunning = false
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED

            safely("routingManager") { routingManager.stop() }
            safely("scanner") { stopScannerMode() }
            safely("ocr") { stopOcrMode() }
            safely("phrasesDrawer") { closePhrasesDrawer() }
            safely("capsule") { if (::capsuleMenuController.isInitialized) capsuleMenuController.destroy() }
            safely("snapshot") { dismissOcrSnapshot() }
            safely("camera") { cameraExecutor?.shutdown() }
            safely("ppOcr") { ppOcrEngine.release() }



            safely("jobs") {
                snapAnimator?.cancel()
                recordingJob?.cancel()
                autoHidePreviewJob?.cancel()
                xButtonAutoHideJob?.cancel()
            }
            safely("recorder") { recorder.stopEarly() }
            safely("stream") { activeStream?.release() }
            activeStream = null
            safely("qwen3") { if (qwen3Asr.isLoaded()) qwen3Asr.release() }
            safely("xAsr") { if (xAsr.isLoaded()) xAsr.release() }

            removeOverlay("xButton", xButtonView)
            xButtonView = null
            removeOverlay("pulse", pulseView)
            pulseView = null
            if (::bubbleView.isInitialized) removeOverlay("bubble", bubbleView)
            if (::previewView.isInitialized) removeOverlay("preview", previewView)
        } finally {
            scope.cancel()
        }
    }

    private inline fun safely(tag: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "onDestroy: $tag failed", e)
        }
    }

    private fun removeOverlay(tag: String, view: View?) {
        if (view == null || !view.isAttachedToWindow) return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove $tag in onDestroy", e)
        }
    }
}
