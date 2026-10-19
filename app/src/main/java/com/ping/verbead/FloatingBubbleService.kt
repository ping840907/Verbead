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
import androidx.core.app.NotificationCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.k2fsa.sherpa.onnx.OnlineStream
import com.ping.verbead.bubble.BubbleState as State
import com.ping.verbead.bubble.BubbleStateMachine
import com.ping.verbead.bubble.CapsuleMenuController
import com.ping.verbead.bubble.BubblePhysics
import com.ping.verbead.bubble.BubblePosition
import com.ping.verbead.bubble.BubbleDeleteZoneController
import com.ping.verbead.bubble.BubbleShakeDetector
import com.ping.verbead.bubble.FloatingBubbleItem
import com.ping.verbead.engine.AudioRecorder
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.engine.ModelDownloadState
import com.ping.verbead.engine.Qwen3AsrEngine
import com.ping.verbead.engine.XAsrEngine
import com.ping.verbead.slm.SlmChatAdapter
import com.ping.verbead.slm.SlmChatMessage
import com.ping.verbead.slm.SlmInferenceEngine
import com.ping.verbead.slm.SlmModelConfig
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
import com.ping.verbead.engine.AudioRoutingManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.ViewGroup
import android.widget.EditText
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import com.ping.verbead.ocr.OcrBoxesOverlayView
import com.ping.verbead.ocr.OcrBlock
import com.ping.verbead.ocr.PpOcrEngine
import com.ping.verbead.ocr.TextStitcher
import com.ping.verbead.util.HapticUtil
import com.ping.verbead.util.TextInsertion
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
        const val MODE_PHRASES = 3
        const val MODE_SLM = 4
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
    val bubbleItems = mutableListOf<FloatingBubbleItem>()
    private var activeAnchorBubble: FloatingBubbleItem? = null
    private val currentAnchorBubble: FloatingBubbleItem?
        get() = (activeAnchorBubble?.takeIf { bubbleItems.contains(it) }) ?: bubbleItems.firstOrNull()

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

    // Dynamic multi-bubble shake-to-duplicate & magnetic delete zone
    private lateinit var bubbleDeleteZoneController: BubbleDeleteZoneController
    private var shakeDetector: BubbleShakeDetector? = null
    private var currentlyDraggedBubble: FloatingBubbleItem? = null

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
    private var drawerItems: MutableList<DrawerItem> = mutableListOf()
    private var phrasesItemTouchHelper: ItemTouchHelper? = null

    // SLM Floating Chat & AI Voice Input State
    private var slmChatView: View? = null
    private var slmChatLayoutParams: WindowManager.LayoutParams? = null
    private var isSlmChatActive = false
    private val slmMessages = mutableListOf<SlmChatMessage>()
    private var slmChatAdapter: SlmChatAdapter? = null
    private val slmInferenceEngine by lazy { SlmInferenceEngine(this, scope) }
    private var slmVoiceJob: Job? = null
    private var isSlmVoiceRecording = false
    private var isRecordingForSlm = false
    private var capturedSlmContext: TextInsertion.EditableTextContext? = null

    // Touch sampling & screen-space velocity tracking
    private data class TouchPoint(val time: Long, val rawX: Float, val rawY: Float)
    private val recentTouchSamples = ArrayList<TouchPoint>()

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (item in bubbleItems) {
                val v = item.view
                if (v.isAttachedToWindow) {
                    val w = v.width
                    val h = v.height
                    if (w > 0 && h > 0) {
                        v.systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
                    }
                }
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
        if (currentMode !in listOf(MODE_VOICE, MODE_OCR, MODE_BARCODE, MODE_PHRASES, MODE_SLM)) {
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
                    for (b in bubbleItems) {
                        b.btnBubble.alpha = 1f
                    }
                    if (::btnBubbleMic.isInitialized) {
                        btnBubbleMic.alpha = 1f
                    }
                }
            }
        )
        bubbleDeleteZoneController = BubbleDeleteZoneController(
            context = themedCtx,
            windowManager = windowManager
        )

        val density = resources.displayMetrics.density
        shakeDetector = BubbleShakeDetector(
            density = density,
            onShakeStroke = { strokeCount, _, progress, isHorizontal ->
                when (strokeCount) {
                    1 -> HapticUtil.tick(this@FloatingBubbleService)
                    2 -> HapticUtil.click(this@FloatingBubbleService)
                    else -> HapticUtil.click(this@FloatingBubbleService)
                }
                currentlyDraggedBubble?.let { item ->
                    if (item.view.isAttachedToWindow) {
                        val squish = (0.16f * progress).coerceIn(0f, 0.18f)
                        if (isHorizontal) {
                            item.view.scaleX = 1f
                            item.view.scaleY = 1f - squish
                        } else {
                            item.view.scaleX = 1f - squish
                            item.view.scaleY = 1f
                        }
                    }
                }
            },
            onShakeTriggered = {
                currentlyDraggedBubble?.let { item ->
                    item.view.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                    handleShakeToDuplicate(item)
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

        val isMulti = ModelConfig.isMultiBubbleEnabled(this)
        val initialMode0 = if (isMulti) {
            getBubbleMode(0, MODE_VOICE)
        } else {
            currentMode
        }
        val (icon0Res, desc0) = getIconAndDescForMode(initialMode0)
        ivBubbleIcon.setImageResource(icon0Res)
        ivBubbleIcon.contentDescription = desc0

        val bubble0 = FloatingBubbleItem(
            id = 0,
            mode = initialMode0,
            view = bubbleView,
            windowLayoutParams = windowLayoutParams,
            btnBubble = btnBubbleMic,
            ivIcon = ivBubbleIcon,
            progress = progressBubble
        ).apply {
            this.isDockedOnRight = true
        }
        activeAnchorBubble = bubble0
        bubbleItems.clear()
        bubbleItems.add(bubble0)
        attachBubbleTouchListener(bubble0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            bubbleView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateSystemGestureExclusion()
            }
        }

        windowManager.addView(bubbleView, windowLayoutParams)
        previewView.visibility = View.GONE
        windowManager.addView(previewView, previewLayoutParams)
        setState(State.IDLE)
        if (ModelConfig.isMultiBubbleEnabled(this)) {
            updateMultiBubbleMode()
        }

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

                if (::bubbleView.isInitialized && bubbleView.isAttachedToWindow) {
                    if (isBubbleMoving || snapAnimator?.isRunning == true) {
                        return@collect
                    }
                    if (ModelConfig.isMultiBubbleEnabled(this@FloatingBubbleService)) {
                        resolveAndApplyEdgeStacking(animate = true)
                    } else {
                        val safeMax = getMaxY()
                        if (windowLayoutParams.y > safeMax) {
                            snapAnimator?.cancel()
                            val startY = windowLayoutParams.y
                            snapAnimator = ValueAnimator.ofInt(startY, safeMax).apply {
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
                                            if (isOcrModeActive) updateOcrPosition()
                                            if (isScannerModeActive) updateScannerPosition()
                                            if (isPhrasesDrawerActive) updatePhrasesDrawerPosition()
                                            updatePreviewPosition()
                                        }
                                    }
                                }
                                start()
                            }
                        } else {
                            if (isOcrModeActive) updateOcrPosition()
                            if (isScannerModeActive) updateScannerPosition()
                            if (isPhrasesDrawerActive) updatePhrasesDrawerPosition()
                            updateXButtonPosition()
                            updatePreviewPosition()
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

    private fun getNavigationBarHeight(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windowManager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars())
            if (insets.bottom > 0) return insets.bottom
        }
        val resId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else (16 * resources.displayMetrics.density).toInt()
    }

    /**
     * 各式選單唯一的邊界限制只有真正的畫面邊界 (螢幕全高)。
     */
    private fun getSafeBottom(): Int {
        return getScreenHeight()
    }

    private fun getCapsuleMenuDistance(): Int {
        val density = resources.displayMetrics.density
        return (CapsuleMenuController.CAPSULE_MENU_MAX_EXTENT_DP * density).toInt()
    }

    /**
     * 各式選單唯一的邊界限制只有真正的畫面邊界 (頂部為 0)。
     */
    private fun getMinPopupY(): Int = 0

    /**
     * 懸浮球頂部邊界限制 (保留邊界安全間距，選單則不受此限制)。
     */
    private fun getMinY(): Int {
        val density = resources.displayMetrics.density
        return (8 * density).toInt()
    }

    /**
     * 懸浮球底部邊界限制：只有懸浮球會受鍵盤邊界的範圍限制 (若鍵盤開啟則停留在鍵盤上方，否則停留在螢幕安全底部)。
     */
    private fun getMaxY(): Int {
        val density = resources.displayMetrics.density
        val bubbleHeight = (60 * density).toInt()
        val margin = (8 * density).toInt()
        val screenHeight = getScreenHeight()

        val calculatedMax = if (dynamicKeyboardTop in 1 until screenHeight) {
            dynamicKeyboardTop - bubbleHeight - margin
        } else {
            screenHeight - getNavigationBarHeight() - bubbleHeight - margin
        }
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
        if (!visible && (state == State.RECORDING || state == State.TRANSCRIBING || state == State.PASTED || isScannerModeActive || isOcrModeActive || isOcrSnapshotActive || isPhrasesDrawerActive || isSlmChatActive || isCapsuleMenuShowing)) {
            return
        }
        for (item in bubbleItems) {
            val v = item.view
            if (!v.isAttachedToWindow) continue
            val isCurrentlyShowing = v.visibility == View.VISIBLE && v.alpha > 0.99f
            if (visible && isCurrentlyShowing) continue
            val isCurrentlyHidden = v.visibility == View.GONE || (v.visibility == View.VISIBLE && v.alpha < 0.01f)
            if (!visible && isCurrentlyHidden) continue

            v.animate().cancel()
            if (visible) {
                v.visibility = View.VISIBLE
                if (animate) {
                    v.animate().alpha(1f).setDuration(180).withEndAction {
                        updateSystemGestureExclusion()
                    }.start()
                } else {
                    v.alpha = 1f
                    v.post { updateSystemGestureExclusion() }
                }
            } else {
                if (item.id == 0) hideXButtonImmediately()
                if (animate) {
                    v.animate().alpha(0f).setDuration(180).withEndAction {
                        if (state != State.RECORDING && state != State.TRANSCRIBING && !isScannerModeActive && !isOcrModeActive && !isOcrSnapshotActive && !isPhrasesDrawerActive && !isSlmChatActive) {
                            v.visibility = View.GONE
                        } else {
                            v.alpha = 1f
                        }
                    }.start()
                } else {
                    v.visibility = View.GONE
                    v.alpha = 0f
                }
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
        if (!isKeyboardOpen && !isScannerModeActive && !isOcrModeActive && !isOcrSnapshotActive && !isPhrasesDrawerActive && !isSlmChatActive && state != State.PASTED && state != State.RECORDING && state != State.TRANSCRIBING && !isCapsuleMenuShowing) {
            setBubbleVisible(false, animate = animate)
        }
    }

    private fun untuckBubble(animate: Boolean = true, onComplete: (() -> Unit)? = null) {
        val target = currentAnchorBubble ?: return
        untuckBubble(target, animate, onComplete)
    }

    private fun untuckBubble(item: FloatingBubbleItem, animate: Boolean = true, onComplete: (() -> Unit)? = null) {
        if (!item.isTucked || !item.view.isAttachedToWindow) {
            item.isTucked = false
            if (item.id == 0) isTucked = false
            onComplete?.invoke()
            return
        }
        item.isTucked = false
        if (item.id == 0) isTucked = false
        val screenWidth = getScreenWidth()
        val density = resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()
        val targetX = if (item.isDockedOnRight) (screenWidth - bubbleWidthPx) else 0
        val targetY = item.windowLayoutParams.y.coerceIn(getMinY(), getMaxY())

        if (!animate) {
            item.snapAnimator?.cancel()
            item.windowLayoutParams.x = targetX
            item.windowLayoutParams.y = targetY
            windowManager.updateViewLayout(item.view, item.windowLayoutParams)
            if (item.id == (currentAnchorBubble?.id ?: 0)) {
                updateXButtonPosition()
                updatePreviewPosition()
                updatePulsePosition()
                if (isOcrModeActive) updateOcrPosition()
                if (isScannerModeActive) updateScannerPosition()
                if (isPhrasesDrawerActive) updatePhrasesDrawerPosition()
            }
            if (item.id == 0) {
                windowLayoutParams.x = targetX
                windowLayoutParams.y = targetY
                updatePulsePosition()
                updateSystemGestureExclusion()
            }
            onComplete?.invoke()
            return
        }

        item.snapAnimator?.cancel()
        val startX = item.windowLayoutParams.x
        val startY = item.windowLayoutParams.y
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = OvershootInterpolator(1.15f)
            addUpdateListener { anim ->
                if (item.view.isAttachedToWindow) {
                    val f = anim.animatedFraction
                    val curX = (startX + (targetX - startX) * f).toInt()
                    val curY = (startY + (targetY - startY) * f).toInt()
                    if (item.windowLayoutParams.x != curX || item.windowLayoutParams.y != curY) {
                        item.windowLayoutParams.x = curX
                        item.windowLayoutParams.y = curY
                        windowManager.updateViewLayout(item.view, item.windowLayoutParams)
                        if (item.id == (currentAnchorBubble?.id ?: 0)) {
                            updateXButtonPosition()
                            updatePreviewPosition()
                            updatePulsePosition()
                            if (isOcrModeActive) updateOcrPosition()
                            if (isScannerModeActive) updateScannerPosition()
                            if (isPhrasesDrawerActive) updatePhrasesDrawerPosition()
                        }
                        if (item.id == 0) {
                            windowLayoutParams.x = curX
                            windowLayoutParams.y = curY
                            updatePulsePosition()
                        }
                    }
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (item.view.isAttachedToWindow) {
                        item.windowLayoutParams.x = targetX
                        item.windowLayoutParams.y = targetY
                        windowManager.updateViewLayout(item.view, item.windowLayoutParams)
                        if (item.id == (currentAnchorBubble?.id ?: 0)) {
                            updateXButtonPosition()
                            updatePreviewPosition()
                            updatePulsePosition()
                            if (isOcrModeActive) updateOcrPosition()
                            if (isScannerModeActive) updateScannerPosition()
                            if (isPhrasesDrawerActive) updatePhrasesDrawerPosition()
                        }
                        if (item.id == 0) {
                            windowLayoutParams.x = targetX
                            windowLayoutParams.y = targetY
                            updatePulsePosition()
                            updateSystemGestureExclusion()
                        }
                    }
                    onComplete?.invoke()
                }
            })
            start()
        }
        item.snapAnimator = anim
        if (item.id == 0) snapAnimator = anim
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachBubbleTouchListener(item: FloatingBubbleItem) {
        val density = resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()
        val bubbleHeightPx = (60 * density).toInt()
        val initialPositions = mutableMapOf<Int, Pair<Int, Int>>()
        var touchStartX = 0f
        var touchStartY = 0f
        var isDragging = false
        var isLongPressTriggered = false
        var lastCollisionHapticTime = 0L
        val longPressHandler = Handler(Looper.getMainLooper())
        val longPressRunnable = Runnable {
            isLongPressTriggered = true
            HapticUtil.heavyClick(this@FloatingBubbleService)
            if (item.isTucked) {
                untuckBubble(item, animate = false)
            }
            activeAnchorBubble = item
            showCapsuleMenu(item, touchStartY)
        }

        item.btnBubble.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    currentlyDraggedBubble = item
                    shakeDetector?.reset()
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    updateSystemGestureExclusion()
                    for (b in bubbleItems) {
                        b.snapAnimator?.cancel()
                    }
                    snapAnimator?.cancel()
                    item.isBubbleMoving = true
                    item.view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    for (b in bubbleItems) {
                        initialPositions[b.id] = b.windowLayoutParams.x to b.windowLayoutParams.y
                    }
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
                        if (isSlmChatActive) {
                            closeSlmChat()
                        }
                        if (bubbleItems.size > 1) {
                            val deleteZoneTargetY = getMinY() + (24 * density).toInt()
                            bubbleDeleteZoneController.show(getScreenWidth(), deleteZoneTargetY)
                        }
                    }
                    if (isDragging) {
                        shakeDetector?.addSample(event.rawX, event.rawY)

                        val screenHeight = getScreenHeight()
                        val screenWidth = getScreenWidth()
                        val safeMinY = getMinY().toFloat()
                        val safeMaxY = getMaxY().toFloat()
                        val normalLeftX = 0f
                        val normalRightX = (screenWidth - bubbleWidthPx).toFloat()
                        val minDragX = getTuckLeftX()
                        val maxDragX = getTuckRightX()

                        val (initX, initY) = initialPositions[item.id] ?: (item.windowLayoutParams.x to item.windowLayoutParams.y)
                        val rawX = (initX + dx).toFloat()
                        val dampedX = when {
                            rawX < normalLeftX -> normalLeftX - (normalLeftX - rawX) * 0.35f
                            rawX > normalRightX -> normalRightX + (rawX - normalRightX) * 0.35f
                            else -> rawX
                        }
                        val newX = dampedX.toInt().coerceIn(minDragX, maxDragX)

                        val rawY = initY + dy
                        val dampedY = when {
                            rawY < safeMinY -> safeMinY - (safeMinY - rawY) * 0.35f
                            rawY > safeMaxY -> safeMaxY + (rawY - safeMaxY) * 0.35f
                            else -> rawY
                        }
                        val minDragY = 0f
                        val maxDragY = if (dynamicKeyboardTop in 1 until screenHeight) (safeMaxY + (20 * density)) else (screenHeight - bubbleHeightPx).toFloat()
                        val newY = dampedY.coerceIn(minDragY, maxDragY).toInt()

                        val bubbleCenterX = newX + bubbleWidthPx / 2f
                        val bubbleCenterY = newY + bubbleHeightPx / 2f

                        val mag = if (bubbleDeleteZoneController.isShowing) {
                            bubbleDeleteZoneController.updateMagnetism(
                                bubbleCenterX = bubbleCenterX,
                                bubbleCenterY = bubbleCenterY,
                                bubbleRadiusPx = bubbleWidthPx / 2f,
                                rawBubbleX = newX.toFloat(),
                                rawBubbleY = newY.toFloat()
                            )
                        } else null

                        if (mag?.isAttracted == true) {
                            item.windowLayoutParams.x = mag.targetX.toInt()
                            item.windowLayoutParams.y = mag.targetY.toInt()
                            item.view.scaleX = if (mag.isLocked) 0.82f else 0.90f
                            item.view.scaleY = if (mag.isLocked) 0.82f else 0.90f
                        } else {
                            item.windowLayoutParams.x = newX
                            item.windowLayoutParams.y = newY
                            if (shakeDetector?.isShaking == false) {
                                item.view.scaleX = 1f
                                item.view.scaleY = 1f
                            }
                        }

                        if (mag?.isAttracted != true && bubbleItems.size > 1) {
                            val tuckLeftX = getTuckLeftX().toFloat()
                            val tuckRightX = getTuckRightX().toFloat()
                            val allowTuck = (state == State.IDLE || state == State.SCANNING) && !isScannerModeActive && !isOcrModeActive && !isOcrSnapshotActive && !isPhrasesDrawerActive && !isSlmChatActive
                            val minDistancePx = (60 * density) + (8 * density)

                            // Check if dragged item is actively squeezing an untucked edge bubble into the wall
                            if (allowTuck) {
                                val itemCenterX = newX + bubbleWidthPx / 2f
                                val itemCenterY = newY + bubbleHeightPx / 2f
                                for (other in bubbleItems) {
                                    if (other.id != item.id && !other.isTucked) {
                                        val otherCenterX = other.windowLayoutParams.x + bubbleWidthPx / 2f
                                        val otherCenterY = other.windowLayoutParams.y + bubbleHeightPx / 2f
                                        val dxToOther = otherCenterX - itemCenterX
                                        val dyToOther = otherCenterY - itemCenterY
                                        val distToOther = hypot(dxToOther, dyToOther)
                                        if (distToOther < minDistancePx) {
                                            val nx = if (distToOther > 0.001f) dxToOther / distToOther else 0f
                                            if (nx > 0.35f && other.windowLayoutParams.x >= normalRightX - 8 * density) {
                                                // Squeezed into right tuck mode!
                                                other.isTucked = true
                                                other.isDockedOnRight = true
                                                other.windowLayoutParams.x = tuckRightX.toInt()
                                            } else if (nx < -0.35f && other.windowLayoutParams.x <= normalLeftX + 8 * density) {
                                                // Squeezed into left tuck mode!
                                                other.isTucked = true
                                                other.isDockedOnRight = false
                                                other.windowLayoutParams.x = tuckLeftX.toInt()
                                            }
                                        }
                                    }
                                }
                            }

                            val boundsMap = mutableMapOf<Int, Pair<Float, Float>>()
                            val positions = bubbleItems.map {
                                val posX = if (it.isTucked) {
                                    if (it.isDockedOnRight) tuckRightX else tuckLeftX
                                } else {
                                    it.windowLayoutParams.x.toFloat()
                                }
                                val (bMinX, bMaxX) = if (it.isTucked) {
                                    if (it.isDockedOnRight) (tuckRightX to tuckRightX) else (tuckLeftX to tuckLeftX)
                                } else {
                                    (normalLeftX to normalRightX)
                                }
                                boundsMap[it.id] = bMinX to bMaxX
                                BubblePosition(it.id, posX, it.windowLayoutParams.y.toFloat(), 30f * density)
                            }
                            val collisionMaxDragY = if (dynamicKeyboardTop in 1 until screenHeight) safeMaxY else (screenHeight - bubbleHeightPx).toFloat()
                            val oldPositions = bubbleItems.associate { it.id to (it.windowLayoutParams.x to it.windowLayoutParams.y) }
                            val anyMoved = BubblePhysics.resolveCollisions(
                                bubbles = positions,
                                draggedId = item.id,
                                minDragX = tuckLeftX,
                                maxDragX = tuckRightX,
                                minDragY = safeMinY,
                                maxDragY = collisionMaxDragY,
                                gap = 8f * density,
                                bubbleBounds = boundsMap
                            )
                            if (anyMoved) {
                                val now = System.currentTimeMillis()
                                if (now - lastCollisionHapticTime > 140L) {
                                    lastCollisionHapticTime = now
                                    HapticUtil.tick(this@FloatingBubbleService)
                                }
                                for (other in bubbleItems) {
                                    if (other.id != item.id) {
                                        val (origX, origY) = oldPositions[other.id] ?: continue
                                        val p = positions.firstOrNull { it.id == other.id } ?: continue
                                        val px = p.x.roundToInt()
                                        val py = p.y.roundToInt()
                                        if (px != origX || py != origY) {
                                            other.snapAnimator?.cancel()
                                            val finalX = if (other.isTucked) {
                                                if (other.isDockedOnRight) tuckRightX.toInt() else tuckLeftX.toInt()
                                            } else {
                                                px.coerceIn(normalLeftX.toInt(), normalRightX.toInt())
                                            }
                                            other.windowLayoutParams.x = finalX
                                            other.windowLayoutParams.y = py
                                            if (other.view.isAttachedToWindow) {
                                                windowManager.updateViewLayout(other.view, other.windowLayoutParams)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (item.view.isAttachedToWindow) {
                            windowManager.updateViewLayout(item.view, item.windowLayoutParams)
                        }
                        if (item.id == (currentAnchorBubble?.id ?: 0)) {
                            updateXButtonPosition()
                            updatePreviewPosition()
                            updatePulsePosition()
                            if (isOcrModeActive) updateOcrPosition()
                            if (isScannerModeActive) updateScannerPosition()
                            if (isPhrasesDrawerActive) updatePhrasesDrawerPosition()
                            if (isSlmChatActive) updateSlmChatPosition()
                        }
                        if (item.id == 0) {
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
                        item.isBubbleMoving = false
                        item.view.setLayerType(View.LAYER_TYPE_NONE, null)
                    } else {
                        currentlyDraggedBubble = null
                        shakeDetector?.reset()

                        if (isDragging && bubbleDeleteZoneController.isShowing && bubbleDeleteZoneController.isAttracted) {
                            item.isBubbleMoving = false
                            item.view.setLayerType(View.LAYER_TYPE_NONE, null)
                            handleDeleteBubble(item)
                        } else {
                            item.view.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                            if (bubbleDeleteZoneController.isShowing) {
                                bubbleDeleteZoneController.hide(animated = true)
                            }
                            val (vx, vy) = computeScreenVelocity()
                            if (!isDragging) {
                                item.isBubbleMoving = false
                                item.view.setLayerType(View.LAYER_TYPE_NONE, null)
                                HapticUtil.click(this@FloatingBubbleService)
                                if (item.isTucked) {
                                    untuckBubble(item, animate = true)
                                } else {
                                    if (ModelConfig.isMultiBubbleEnabled(this@FloatingBubbleService)) {
                                        onBubbleClickForMode(item)
                                    } else {
                                        onBubbleClick()
                                    }
                                }
                            } else {
                                snapBubbleToSafeBoundsWithInertia(item, vx, vy)
                            }
                        }
                    }
                    recentTouchSamples.clear()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    item.view.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                    currentlyDraggedBubble = null
                    shakeDetector?.reset()
                    if (bubbleDeleteZoneController.isShowing) {
                        bubbleDeleteZoneController.hide(animated = true)
                    }
                    if (isLongPressTriggered || isCapsuleMenuShowing) {
                        capsuleMenuController.dismiss()
                        isLongPressTriggered = false
                        item.isBubbleMoving = false
                        item.view.setLayerType(View.LAYER_TYPE_NONE, null)
                    } else if (isDragging) {
                        val (vx, vy) = computeScreenVelocity()
                        snapBubbleToSafeBoundsWithInertia(item, vx, vy)
                    } else {
                        item.isBubbleMoving = false
                        item.view.setLayerType(View.LAYER_TYPE_NONE, null)
                    }
                    recentTouchSamples.clear()
                    true
                }
                else -> false
            }
        }
    }

    fun updateMultiBubbleMode() {
        if (!::bubbleView.isInitialized) return
        val enabled = ModelConfig.isMultiBubbleEnabled(this)
        val targetCount = if (enabled) ModelConfig.getMultiBubbleCount(this) else 1

        if (enabled) {
            val defaultModes = intArrayOf(MODE_VOICE, MODE_OCR, MODE_BARCODE, MODE_PHRASES, MODE_SLM)
            val bubble0 = bubbleItems.firstOrNull()
            if (bubble0 != null) {
                bubble0.mode = getBubbleMode(0, defaultModes[0])
                val (iconRes, desc) = getIconAndDescForMode(bubble0.mode)
                bubble0.ivIcon.setImageResource(iconRes)
                bubble0.ivIcon.contentDescription = desc
            }

            // Remove bubbles with id >= targetCount
            val it = bubbleItems.iterator()
            while (it.hasNext()) {
                val item = it.next()
                if (item.id >= targetCount) {
                    if (item.view.isAttachedToWindow) {
                        removeOverlay("bubble_${item.id}", item.view)
                    }
                    it.remove()
                }
            }

            val density = resources.displayMetrics.density
            val bubbleWidthPx = (60 * density).toInt()
            val bubbleHeightPx = (60 * density).toInt()
            val screenWidth = getScreenWidth()
            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            for (id in bubbleItems.size until targetCount) {
                val mode = getBubbleMode(id, defaultModes.getOrElse(id) { MODE_VOICE })
                val v = LayoutInflater.from(themedCtx).inflate(R.layout.layout_floating_bubble, null)
                val btn = v.findViewById<FrameLayout>(R.id.btn_bubble_mic)
                val iv = v.findViewById<ImageView>(R.id.iv_bubble_icon)
                val prog = v.findViewById<ProgressBar>(R.id.progress_bubble)
                val (iconRes, desc) = getIconAndDescForMode(mode)
                iv.setImageResource(iconRes)
                iv.contentDescription = desc

                val initialX = if (isDockedOnRight) (screenWidth - bubbleWidthPx) else 0
                val initialY = (windowLayoutParams.y + id * (68 * density).toInt()).coerceIn(getMinY(), getMaxY())

                val lp = WindowManager.LayoutParams(
                    bubbleWidthPx,
                    bubbleHeightPx,
                    layoutFlag,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.LEFT
                    x = initialX
                    y = initialY
                }

                val item = FloatingBubbleItem(
                    id = id,
                    mode = mode,
                    view = v,
                    windowLayoutParams = lp,
                    btnBubble = btn,
                    ivIcon = iv,
                    progress = prog
                ).apply {
                    this.isDockedOnRight = this@FloatingBubbleService.isDockedOnRight
                }

                attachBubbleTouchListener(item)
                bubbleItems.add(item)
                windowManager.addView(v, lp)
            }

            resolveAndApplyEdgeStacking()
        } else {
            val it = bubbleItems.iterator()
            while (it.hasNext()) {
                val item = it.next()
                if (item.id != 0) {
                    if (item.view.isAttachedToWindow) {
                        removeOverlay("bubble_${item.id}", item.view)
                    }
                    it.remove()
                }
            }
            val bubble0 = bubbleItems.firstOrNull()
            if (bubble0 != null) {
                bubble0.mode = currentMode
                val (iconRes, desc) = getIconAndDescForMode(currentMode)
                bubble0.ivIcon.setImageResource(iconRes)
                bubble0.ivIcon.contentDescription = desc
                activeAnchorBubble = bubble0
            }
        }
    }

    private fun handleShakeToDuplicate(parent: FloatingBubbleItem) {
        if (bubbleItems.size >= ModelConfig.MAX_MULTI_BUBBLE_COUNT) {
            HapticUtil.tick(this)
            return
        }

        val newCount = bubbleItems.size + 1
        ModelConfig.setMultiBubbleEnabled(this, true)
        ModelConfig.setMultiBubbleCount(this, newCount)

        val allModes = intArrayOf(MODE_VOICE, MODE_OCR, MODE_BARCODE, MODE_PHRASES, MODE_SLM)
        val usedModes = bubbleItems.map { it.mode }.toSet()
        val newMode = allModes.firstOrNull { it !in usedModes } ?: MODE_VOICE
        val newId = bubbleItems.size
        setBubbleMode(newId, newMode)

        val density = resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()
        val bubbleHeightPx = (60 * density).toInt()
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val v = LayoutInflater.from(themedCtx).inflate(R.layout.layout_floating_bubble, null)
        val btn = v.findViewById<FrameLayout>(R.id.btn_bubble_mic)
        val iv = v.findViewById<ImageView>(R.id.iv_bubble_icon)
        val prog = v.findViewById<ProgressBar>(R.id.progress_bubble)
        val (iconRes, desc) = getIconAndDescForMode(newMode)
        iv.setImageResource(iconRes)
        iv.contentDescription = desc

        val initialX = parent.windowLayoutParams.x
        val initialY = parent.windowLayoutParams.y

        val lp = WindowManager.LayoutParams(
            bubbleWidthPx,
            bubbleHeightPx,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = initialX
            y = initialY
        }

        val newItem = FloatingBubbleItem(
            id = newId,
            mode = newMode,
            view = v,
            windowLayoutParams = lp,
            btnBubble = btn,
            ivIcon = iv,
            progress = prog
        ).apply {
            this.isDockedOnRight = parent.isDockedOnRight
        }

        attachBubbleTouchListener(newItem)
        bubbleItems.add(newItem)

        v.scaleX = 0.25f
        v.scaleY = 0.25f
        v.alpha = 0.3f

        windowManager.addView(v, lp)

        // Haptic feedback pop
        HapticUtil.heavyClick(this)

        // Parent recoil/bounce (squish inward without exceeding 1.0f)
        parent.view.animate().cancel()
        parent.view.animate()
            .scaleX(0.88f)
            .scaleY(0.88f)
            .setDuration(120L)
            .withEndAction {
                parent.view.animate().scaleX(1f).scaleY(1f).setDuration(160L).setInterpolator(DecelerateInterpolator()).start()
            }
            .start()

        // New bubble blooms smoothly to full size (capped at 1.0f so edges are never clipped by the window)
        v.animate().cancel()
        v.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(260L)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()

        // Repulsion displacement alongside the parent bubble (staying at current X until finger release)
        val repulsionDistance = (68 * density).toInt()
        val safeMin = getMinY()
        val safeMax = getMaxY()
        val existingYs = bubbleItems.map { it.windowLayoutParams.y }
        val candidateDown = (initialY + repulsionDistance).coerceIn(safeMin, safeMax)
        val candidateUp = (initialY - repulsionDistance).coerceIn(safeMin, safeMax)
        val distDown = existingYs.minOfOrNull { kotlin.math.abs(it - candidateDown) } ?: Int.MAX_VALUE
        val distUp = existingYs.minOfOrNull { kotlin.math.abs(it - candidateUp) } ?: Int.MAX_VALUE
        val targetRepulsionY = if (distDown >= distUp) candidateDown else candidateUp

        val screenWidth = getScreenWidth()
        val targetOnRight = (initialX + bubbleWidthPx / 2f) >= screenWidth / 2f
        newItem.isDockedOnRight = targetOnRight

        newItem.snapAnimator?.cancel()
        newItem.snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200L
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { va ->
                if (v.isAttachedToWindow) {
                    val f = va.animatedFraction
                    lp.x = initialX
                    lp.y = (initialY + (targetRepulsionY - initialY) * f).toInt()
                    windowManager.updateViewLayout(v, lp)
                }
            }
            start()
        }

        // Show delete zone if parent is currently being dragged and size > 1
        if (bubbleItems.size > 1 && !bubbleDeleteZoneController.isShowing) {
            val deleteZoneTargetY = getMinY() + (24 * density).toInt()
            bubbleDeleteZoneController.show(getScreenWidth(), deleteZoneTargetY)
        }
    }

    private fun handleDeleteBubble(bubbleToDelete: FloatingBubbleItem) {
        if (bubbleItems.size <= 1) {
            if (bubbleDeleteZoneController.isShowing) {
                bubbleDeleteZoneController.hide(animated = true)
            }
            return
        }

        bubbleToDelete.view.animate().cancel()
        bubbleToDelete.view.animate()
            .scaleX(0f)
            .scaleY(0f)
            .alpha(0f)
            .setDuration(160L)
            .start()

        bubbleDeleteZoneController.playDeleteBurst {
            if (bubbleToDelete.id == 0) {
                // bubble0 is the service's primary bound bubbleView.
                // Inherit state from a surviving bubble to preserve windowManager view binding.
                val survivor = bubbleItems.firstOrNull { it.id != 0 }
                if (survivor != null) {
                    bubbleToDelete.mode = survivor.mode
                    val (iconRes, desc) = getIconAndDescForMode(survivor.mode)
                    bubbleToDelete.ivIcon.setImageResource(iconRes)
                    bubbleToDelete.ivIcon.contentDescription = desc
                    bubbleToDelete.windowLayoutParams.x = survivor.windowLayoutParams.x
                    bubbleToDelete.windowLayoutParams.y = survivor.windowLayoutParams.y
                    bubbleToDelete.isDockedOnRight = survivor.isDockedOnRight
                    bubbleToDelete.isTucked = survivor.isTucked
                    windowLayoutParams.x = survivor.windowLayoutParams.x
                    windowLayoutParams.y = survivor.windowLayoutParams.y
                    isDockedOnRight = survivor.isDockedOnRight
                    isTucked = survivor.isTucked
                    setBubbleMode(0, survivor.mode)

                    bubbleToDelete.view.scaleX = 1f
                    bubbleToDelete.view.scaleY = 1f
                    bubbleToDelete.view.alpha = 1f
                    if (bubbleToDelete.view.isAttachedToWindow) {
                        windowManager.updateViewLayout(bubbleToDelete.view, bubbleToDelete.windowLayoutParams)
                    }

                    if (survivor.view.isAttachedToWindow) {
                        removeOverlay("bubble_${survivor.id}", survivor.view)
                    }
                    bubbleItems.remove(survivor)
                    activeAnchorBubble = bubbleToDelete
                }
            } else {
                if (bubbleToDelete.view.isAttachedToWindow) {
                    removeOverlay("bubble_${bubbleToDelete.id}", bubbleToDelete.view)
                }
                bubbleItems.remove(bubbleToDelete)
                if (activeAnchorBubble?.id == bubbleToDelete.id) {
                    activeAnchorBubble = bubbleItems.firstOrNull()
                }
            }

            // Normalize and save IDs for remaining bubbles
            bubbleItems.forEachIndexed { index, item ->
                item.id = index
                setBubbleMode(index, item.mode)
            }

            val newCount = bubbleItems.size
            if (newCount <= 1) {
                ModelConfig.setMultiBubbleEnabled(this, false)
                ModelConfig.setMultiBubbleCount(this, 2)
                val survivor = bubbleItems.firstOrNull()
                if (survivor != null) {
                    currentMode = survivor.mode
                    snapBubbleToSafeBoundsWithInertia(survivor, vx = 0f, vy = 0f)
                }
            } else {
                ModelConfig.setMultiBubbleCount(this, newCount)
                val firstSurvivor = bubbleItems.firstOrNull()
                if (firstSurvivor != null) {
                    snapBubbleToSafeBoundsWithInertia(firstSurvivor, vx = 0f, vy = 0f)
                }
            }
        }
    }

    private fun resolveAndApplyEdgeStacking(animate: Boolean = false) {
        val density = resources.displayMetrics.density
        val curMinY = getMinY().toFloat()
        val curMaxY = getMaxY().toFloat()

        val rightBubbles = bubbleItems.filter { it.isDockedOnRight }
        val rightTargets = if (rightBubbles.isNotEmpty()) {
            val positions = rightBubbles.map {
                BubblePosition(it.id, it.windowLayoutParams.x.toFloat(), it.windowLayoutParams.y.toFloat(), 30f * density)
            }
            BubblePhysics.resolveEdgeStacking(positions, curMinY, curMaxY, 8f * density)
        } else emptyMap()

        val leftBubbles = bubbleItems.filter { !it.isDockedOnRight }
        val leftTargets = if (leftBubbles.isNotEmpty()) {
            val positions = leftBubbles.map {
                BubblePosition(it.id, it.windowLayoutParams.x.toFloat(), it.windowLayoutParams.y.toFloat(), 30f * density)
            }
            BubblePhysics.resolveEdgeStacking(positions, curMinY, curMaxY, 8f * density)
        } else emptyMap()

        val allTargets = rightTargets + leftTargets
        val bubblesToMove = bubbleItems.filter { item ->
            val targetY = allTargets[item.id]?.roundToInt() ?: item.windowLayoutParams.y
            targetY != item.windowLayoutParams.y
        }

        fun updateAllPopups() {
            updateXButtonPosition()
            updatePulsePosition()
            if (isOcrModeActive) updateOcrPosition()
            if (isScannerModeActive) updateScannerPosition()
            if (isPhrasesDrawerActive) updatePhrasesDrawerPosition()
            updatePreviewPosition()
        }

        if (bubblesToMove.isEmpty()) {
            updateAllPopups()
            return
        }

        if (animate) {
            val startYMap = bubblesToMove.associate { it.id to it.windowLayoutParams.y }
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 200
                interpolator = DecelerateInterpolator()
                addUpdateListener { va ->
                    val frac = va.animatedFraction
                    for (item in bubblesToMove) {
                        val startY = startYMap[item.id] ?: continue
                        val targetY = allTargets[item.id]?.roundToInt() ?: continue
                        val currentY = (startY + (targetY - startY) * frac).roundToInt()
                        if (item.windowLayoutParams.y != currentY) {
                            item.windowLayoutParams.y = currentY
                            if (item.view.isAttachedToWindow) {
                                windowManager.updateViewLayout(item.view, item.windowLayoutParams)
                            }
                            if (item.id == 0) {
                                windowLayoutParams.y = currentY
                            }
                        }
                    }
                    updateAllPopups()
                }
                start()
            }
        } else {
            for (item in bubblesToMove) {
                val targetY = allTargets[item.id]?.roundToInt() ?: continue
                item.windowLayoutParams.y = targetY
                if (item.view.isAttachedToWindow) {
                    windowManager.updateViewLayout(item.view, item.windowLayoutParams)
                }
                if (item.id == 0) {
                    windowLayoutParams.y = targetY
                }
            }
            updateAllPopups()
        }
    }

    private fun onBubbleClickForMode(item: FloatingBubbleItem) {
        activeAnchorBubble = item
        if (state == State.PASTED) {
            hideXButton()
            setState(State.IDLE)
        }
        when (item.mode) {
            MODE_VOICE -> {
                if (isScannerModeActive) stopScannerMode()
                if (isOcrModeActive) stopOcrMode()
                if (isPhrasesDrawerActive) closePhrasesDrawer()
                if (isSlmChatActive) closeSlmChat()
                when (state) {
                    State.RECORDING -> {
                        recorder.stopEarly()
                        hidePreviewText()
                        setState(State.TRANSCRIBING)
                    }
                    State.IDLE, State.PASTED -> {
                        startRecording(forSlm = false)
                    }
                    State.LOADING, State.TRANSCRIBING -> {
                        // In progress, ignore
                    }
                    State.SCANNING -> {
                        stopScannerMode()
                        startRecording(forSlm = false)
                    }
                }
            }
            MODE_OCR -> {
                if (state == State.RECORDING) {
                    recorder.stopEarly()
                    hidePreviewText()
                    setState(State.IDLE)
                }
                if (isScannerModeActive) stopScannerMode()
                if (isPhrasesDrawerActive) closePhrasesDrawer()
                if (isSlmChatActive) closeSlmChat()
                toggleOcrMode()
            }
            MODE_BARCODE -> {
                if (state == State.RECORDING) {
                    recorder.stopEarly()
                    hidePreviewText()
                    setState(State.IDLE)
                }
                if (isOcrModeActive) stopOcrMode()
                if (isPhrasesDrawerActive) closePhrasesDrawer()
                if (isSlmChatActive) closeSlmChat()
                toggleScannerMode()
            }
            MODE_PHRASES -> {
                if (state == State.RECORDING) {
                    recorder.stopEarly()
                    hidePreviewText()
                    setState(State.IDLE)
                }
                if (isScannerModeActive) stopScannerMode()
                if (isOcrModeActive) stopOcrMode()
                if (isSlmChatActive) closeSlmChat()
                togglePhrasesDrawer()
            }
            MODE_SLM -> {
                if (isScannerModeActive) stopScannerMode()
                if (isOcrModeActive) stopOcrMode()
                if (isPhrasesDrawerActive) closePhrasesDrawer()
                if (isSlmChatActive) {
                    closeSlmChat()
                    return
                }
                when (state) {
                    State.RECORDING -> {
                        recorder.stopEarly()
                        hidePreviewText()
                        setState(State.TRANSCRIBING)
                    }
                    State.IDLE, State.PASTED -> {
                        startSlmVoiceAction()
                    }
                    State.LOADING, State.TRANSCRIBING -> {
                        // In progress, ignore
                    }
                    State.SCANNING -> {
                        stopScannerMode()
                        startSlmVoiceAction()
                    }
                }
            }
        }
    }

    private fun evaluateTuck(
        startX: Float,
        vx: Float,
        isRight: Boolean,
        isCurrentlyTucked: Boolean,
        normalX: Float,
        hiddenWidthPx: Float,
        density: Float
    ): Boolean {
        return if (isRight) {
            if (isCurrentlyTucked) {
                val pullInwardThreshold = normalX + hiddenWidthPx * 0.60f
                !(startX < pullInwardThreshold || vx < -180f)
            } else {
                val pushOutwardThreshold = normalX + hiddenWidthPx * 0.30f
                (startX >= pushOutwardThreshold && vx >= -100f) ||
                    (startX >= normalX - 12 * density && vx > 220f)
            }
        } else {
            if (isCurrentlyTucked) {
                val pullInwardThreshold = normalX - hiddenWidthPx * 0.60f
                !(startX > pullInwardThreshold || vx > 180f)
            } else {
                val pushOutwardThreshold = normalX - hiddenWidthPx * 0.30f
                (startX <= pushOutwardThreshold && vx <= 100f) ||
                    (startX <= normalX + 12 * density && vx < -220f)
            }
        }
    }

    private fun snapBubbleToSafeBoundsWithInertia(item: FloatingBubbleItem, vx: Float = 0f, vy: Float = 0f) {
        if (!item.view.isAttachedToWindow) return
        item.snapAnimator?.cancel()
        item.isBubbleMoving = true
        item.view.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val screenWidth = getScreenWidth()
        val density = resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()
        val bubbleHeightPx = (60 * density).toInt()

        val startX = item.windowLayoutParams.x.toFloat()
        val startY = item.windowLayoutParams.y.toFloat()

        // 1. Friction coasting model to determine projected rest coordinate
        val tau = 0.22f // Effective friction deceleration coasting time constant (tau = 1 / beta)
        val currentCenterX = startX + bubbleWidthPx / 2f
        val projectedCenterX = currentCenterX + vx * tau

        // Target edge is determined strictly by whether the projected rest position crosses the screen midpoint
        val targetOnRight = projectedCenterX >= screenWidth / 2f
        item.isDockedOnRight = targetOnRight
        if (item.id == 0) isDockedOnRight = targetOnRight

        val normalLeftX = 0f
        val normalRightX = (screenWidth - bubbleWidthPx).toFloat()
        val tuckLeftX = getTuckLeftX().toFloat()
        val tuckRightX = getTuckRightX().toFloat()
        val hiddenWidthPx = getHiddenWidthPx().toFloat()

        val isMultiBubble = ModelConfig.isMultiBubbleEnabled(this)
        val allowTuck = (state == State.IDLE || state == State.SCANNING) && !isScannerModeActive && !isOcrModeActive && !isOcrSnapshotActive && !isPhrasesDrawerActive
        val shouldTuck = if (!allowTuck) {
            false
        } else {
            evaluateTuck(
                startX = startX,
                vx = vx,
                isRight = targetOnRight,
                isCurrentlyTucked = item.isTucked,
                normalX = if (targetOnRight) normalRightX else normalLeftX,
                hiddenWidthPx = hiddenWidthPx,
                density = density
            )
        }

        item.isTucked = shouldTuck
        if (item.id == 0) isTucked = shouldTuck

        val targetX = if (targetOnRight) {
            if (shouldTuck) tuckRightX else normalRightX
        } else {
            if (shouldTuck) tuckLeftX else normalLeftX
        }

        val curMinY = getMinY().toFloat()
        val curMaxY = getMaxY().toFloat()
        val projectedY = (startY + vy * tau).coerceIn(curMinY, curMaxY)

        var finalItemTargetY = projectedY
        val otherTargets = mutableMapOf<Int, Pair<Float, Float>>()

        if (isMultiBubble && bubbleItems.size > 1) {
            for (other in bubbleItems) {
                if (other.id != item.id) {
                    val otherCenterX = other.windowLayoutParams.x + bubbleWidthPx / 2f
                    val otherRight = otherCenterX >= screenWidth / 2f
                    other.isDockedOnRight = otherRight
                    if (other.id == 0) isDockedOnRight = otherRight
                    val otherNormalX = if (otherRight) normalRightX else normalLeftX
                    val otherTuckX = if (otherRight) tuckRightX else tuckLeftX
                    val otherShouldTuck = if (allowTuck) {
                        evaluateTuck(
                            startX = other.windowLayoutParams.x.toFloat(),
                            vx = 0f,
                            isRight = otherRight,
                            isCurrentlyTucked = other.isTucked,
                            normalX = otherNormalX,
                            hiddenWidthPx = hiddenWidthPx,
                            density = density
                        )
                    } else false
                    other.isTucked = otherShouldTuck
                    if (other.id == 0) isTucked = otherShouldTuck
                    val otherTargetX = if (otherShouldTuck) otherTuckX else otherNormalX
                    otherTargets[other.id] = otherTargetX to other.windowLayoutParams.y.toFloat()
                }
            }

            val rightBubbles = bubbleItems.filter { if (it.id == item.id) targetOnRight else it.isDockedOnRight }
            if (rightBubbles.isNotEmpty()) {
                val positions = rightBubbles.map {
                    val y = if (it.id == item.id) projectedY else it.windowLayoutParams.y.toFloat()
                    BubblePosition(it.id, if (it.isTucked) tuckRightX else normalRightX, y, 30f * density)
                }
                val resolvedYs = BubblePhysics.resolveEdgeStacking(positions, curMinY, curMaxY, 8f * density)
                if (targetOnRight) {
                    finalItemTargetY = resolvedYs[item.id] ?: projectedY
                }
                for (other in rightBubbles) {
                    if (other.id != item.id) {
                        val y = resolvedYs[other.id] ?: other.windowLayoutParams.y.toFloat()
                        val currentX = otherTargets[other.id]?.first ?: (if (other.isTucked) tuckRightX else normalRightX)
                        otherTargets[other.id] = currentX to y
                    }
                }
            }

            val leftBubbles = bubbleItems.filter { if (it.id == item.id) !targetOnRight else !it.isDockedOnRight }
            if (leftBubbles.isNotEmpty()) {
                val positions = leftBubbles.map {
                    val y = if (it.id == item.id) projectedY else it.windowLayoutParams.y.toFloat()
                    BubblePosition(it.id, if (it.isTucked) tuckLeftX else normalLeftX, y, 30f * density)
                }
                val resolvedYs = BubblePhysics.resolveEdgeStacking(positions, curMinY, curMaxY, 8f * density)
                if (!targetOnRight) {
                    finalItemTargetY = resolvedYs[item.id] ?: projectedY
                }
                for (other in leftBubbles) {
                    if (other.id != item.id) {
                        val y = resolvedYs[other.id] ?: other.windowLayoutParams.y.toFloat()
                        val currentX = otherTargets[other.id]?.first ?: (if (other.isTucked) tuckLeftX else normalLeftX)
                        otherTargets[other.id] = currentX to y
                    }
                }
            }

            for (other in bubbleItems) {
                if (other.id != item.id) {
                    val (otherTargetX, otherTargetY) = otherTargets[other.id] ?: continue
                    val otherStartX = other.windowLayoutParams.x
                    val otherStartY = other.windowLayoutParams.y
                    val endOtherX = otherTargetX.roundToInt()
                    val endOtherY = otherTargetY.roundToInt()
                    if (otherStartX != endOtherX || otherStartY != endOtherY) {
                        other.snapAnimator?.cancel()
                        other.isBubbleMoving = true
                        other.snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                            duration = 320L
                            interpolator = OvershootInterpolator(1.1f)
                            addUpdateListener { anim ->
                                if (other.view.isAttachedToWindow) {
                                    val f = anim.animatedFraction
                                    val cx = (otherStartX + (endOtherX - otherStartX) * f).toInt()
                                    val cy = (otherStartY + (endOtherY - otherStartY) * f).toInt()
                                    if (other.windowLayoutParams.x != cx || other.windowLayoutParams.y != cy) {
                                        other.windowLayoutParams.x = cx
                                        other.windowLayoutParams.y = cy
                                        windowManager.updateViewLayout(other.view, other.windowLayoutParams)
                                        if (other.id == (currentAnchorBubble?.id ?: 0)) {
                                            updateXButtonPosition()
                                            updatePreviewPosition()
                                            updatePulsePosition()
                                            if (isOcrModeActive) updateOcrPosition()
                                            if (isScannerModeActive) updateScannerPosition()
                                            if (isPhrasesDrawerActive) updatePhrasesDrawerPosition()
                                        }
                                        if (other.id == 0) {
                                            windowLayoutParams.x = cx
                                            windowLayoutParams.y = cy
                                            updatePulsePosition()
                                        }
                                    }
                                }
                            }
                            addListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: android.animation.Animator) {
                                    other.isBubbleMoving = false
                                    if (other.view.isAttachedToWindow) {
                                        other.windowLayoutParams.x = endOtherX
                                        other.windowLayoutParams.y = endOtherY
                                        windowManager.updateViewLayout(other.view, other.windowLayoutParams)
                                        if (other.id == (currentAnchorBubble?.id ?: 0)) {
                                            updateXButtonPosition()
                                            updatePreviewPosition()
                                            updatePulsePosition()
                                            if (isOcrModeActive) updateOcrPosition()
                                            if (isScannerModeActive) updateScannerPosition()
                                            if (isPhrasesDrawerActive) updatePhrasesDrawerPosition()
                                        }
                                        if (other.id == 0) {
                                            windowLayoutParams.x = endOtherX
                                            windowLayoutParams.y = endOtherY
                                            updatePulsePosition()
                                        }
                                    }
                                }
                            })
                            start()
                        }
                    }
                }
            }
        }

        // 2. Facebook Messenger / AOSP Damped Harmonic Oscillator (Spring) Physics
        val omegaN = 16.0f
        val zeta = 0.82f
        val gamma = zeta * omegaN
        val omegaD = (omegaN * kotlin.math.sqrt(1.0 - (zeta * zeta))).toFloat()

        val c1x = startX - targetX
        val c2x = if (omegaD > 0.001f) (vx + gamma * c1x) / omegaD else 0f

        val c1y = startY - finalItemTargetY
        val c2y = if (omegaD > 0.001f) (vy + gamma * c1y) / omegaD else 0f

        val durationMs = 380L
        val screenHeight = getScreenHeight()
        val minClampX = minOf(normalLeftX, tuckLeftX).toInt()
        val maxClampX = maxOf(normalRightX, tuckRightX).toInt()

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { a ->
                if (item.view.isAttachedToWindow) {
                    val f = a.animatedFraction
                    val t = f * (durationMs / 1000f)
                    val exp = kotlin.math.exp((-gamma * t).toDouble()).toFloat()
                    val cos = kotlin.math.cos((omegaD * t).toDouble()).toFloat()
                    val sin = kotlin.math.sin((omegaD * t).toDouble()).toFloat()

                    val currentX = targetX + exp * (c1x * cos + c2x * sin)
                    val currentY = finalItemTargetY + exp * (c1y * cos + c2y * sin)

                    val newX = currentX.toInt().coerceIn(minClampX, maxClampX)
                    val newY = currentY.toInt().coerceIn(0, screenHeight - bubbleHeightPx)

                    if (t > 0.20f && kotlin.math.abs(currentX - targetX) < 0.75f && kotlin.math.abs(currentY - finalItemTargetY) < 0.75f) {
                        val finalX = targetX.toInt().coerceIn(minClampX, maxClampX)
                        val finalY = finalItemTargetY.toInt().coerceIn(curMinY.toInt(), curMaxY.toInt())
                        if (item.windowLayoutParams.x != finalX || item.windowLayoutParams.y != finalY) {
                            item.windowLayoutParams.x = finalX
                            item.windowLayoutParams.y = finalY
                            windowManager.updateViewLayout(item.view, item.windowLayoutParams)
                        }
                        a.cancel()
                        return@addUpdateListener
                    }

                    if (item.windowLayoutParams.x != newX || item.windowLayoutParams.y != newY) {
                        item.windowLayoutParams.x = newX
                        item.windowLayoutParams.y = newY
                        windowManager.updateViewLayout(item.view, item.windowLayoutParams)
                        if (item.id == (currentAnchorBubble?.id ?: 0)) {
                            updateXButtonPosition()
                            updatePreviewPosition()
                            updatePulsePosition()
                            if (isOcrModeActive) updateOcrPosition()
                            if (isScannerModeActive) updateScannerPosition()
                            if (isPhrasesDrawerActive) updatePhrasesDrawerPosition()
                        }
                        if (item.id == 0) {
                            windowLayoutParams.x = newX
                            windowLayoutParams.y = newY
                            updatePulsePosition()
                        }
                    }
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    item.isBubbleMoving = false
                    if (!item.view.isAttachedToWindow) return
                    item.view.setLayerType(View.LAYER_TYPE_NONE, null)
                    item.isDockedOnRight = targetOnRight
                    val finalX = targetX.toInt().coerceIn(minClampX, maxClampX)
                    val finalY = finalItemTargetY.toInt().coerceIn(curMinY.toInt(), curMaxY.toInt())
                    if (item.windowLayoutParams.x != finalX || item.windowLayoutParams.y != finalY) {
                        item.windowLayoutParams.x = finalX
                        item.windowLayoutParams.y = finalY
                        windowManager.updateViewLayout(item.view, item.windowLayoutParams)
                    }
                    if (item.id == (currentAnchorBubble?.id ?: 0)) {
                        updateXButtonPosition()
                        updatePreviewPosition()
                        updatePulsePosition()
                        if (isOcrModeActive) updateOcrPosition()
                        if (isScannerModeActive) updateScannerPosition()
                        if (isPhrasesDrawerActive) updatePhrasesDrawerPosition()
                    }
                    if (item.id == 0) {
                        isDockedOnRight = targetOnRight
                        windowLayoutParams.x = finalX
                        windowLayoutParams.y = finalY
                        updatePulsePosition()
                        updateSystemGestureExclusion()
                        if (!item.isTucked && (isScannerModeActive || isOcrModeActive || isPhrasesDrawerActive || state == State.PASTED || state == State.RECORDING || state == State.TRANSCRIBING)) {
                            showXButton()
                        }
                    }
                    HapticUtil.click(this@FloatingBubbleService)
                }
            })
            start()
        }
        item.snapAnimator = anim
        if (item.id == 0) snapAnimator = anim
    }

    private fun snapToSafeBoundsWithInertia(vx: Float = 0f, vy: Float = 0f) {
        val bubble0 = bubbleItems.firstOrNull() ?: return
        snapBubbleToSafeBoundsWithInertia(bubble0, vx, vy)
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
            isSlmChatActive -> {
                // 關閉端側 AI 對話視窗
                closeSlmChat()
                hideXButton()
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
                isRecordingForSlm = false
                capturedSlmContext = null
                recordingJob?.cancel()
                slmInferenceEngine.cancelCurrent()
                recorder.stopEarly()
                activeStream?.let { runCatching { it.release() } }
                activeStream = null
                hidePreviewText()
                setState(State.IDLE)
                hideXButton()
            }
            state == State.TRANSCRIBING -> {
                // 中斷模型推論 (Abort)：Cancel 背景轉譯協程與 SLM 推論、設置 isAborted 旗標阻止後續貼上、state → IDLE
                isAborted = true
                isRecordingForSlm = false
                capturedSlmContext = null
                recordingJob?.cancel()
                slmInferenceEngine.cancelCurrent()
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
        val safeBottom = getSafeBottom()
        val screenWidth = getScreenWidth()
        val anchor = currentAnchorBubble
        val anchorX = anchor?.windowLayoutParams?.x ?: windowLayoutParams.x
        val anchorY = anchor?.windowLayoutParams?.y ?: windowLayoutParams.y
        val preferredY = anchorY - (48 * density).toInt()
        val candidateY = if (preferredY < statusBar) {
            // 上方空間不足（接近狀態列/螢幕頂端），智慧翻轉顯示在懸浮球正下方
            anchorY + bubbleWidthPx + (4 * density).toInt()
        } else {
            preferredY
        }
        val maxAllowedY = safeBottom - btnSize - (4 * density).toInt()
        val targetY = candidateY.coerceIn(0, maxOf(0, maxAllowedY))

        val xOffset = anchorX + ((bubbleWidthPx - btnSize) / 2)
        val clampedX = xOffset.coerceIn((4 * density).toInt(), maxOf((4 * density).toInt(), screenWidth - btnSize - (4 * density).toInt()))

        lp.x = clampedX
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
        val anchor = currentAnchorBubble
        if (anchor?.isTucked == true) {
            untuckBubble(anchor, animate = false)
        } else if (isTucked) {
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
        val anchorX = anchor?.windowLayoutParams?.x ?: windowLayoutParams.x
        val anchorY = anchor?.windowLayoutParams?.y ?: windowLayoutParams.y
        val xOffset = anchorX + ((bubbleWidthPx - btnSize) / 2)
        val statusBar = getStatusBarHeight()
        val safeBottom = getSafeBottom()
        val screenWidth = getScreenWidth()

        val clampedX = xOffset.coerceIn((4 * density).toInt(), maxOf((4 * density).toInt(), screenWidth - btnSize - (4 * density).toInt()))

        val preferredY = anchorY - (48 * density).toInt()
        val isFlipped = preferredY < statusBar
        val candidateY = if (isFlipped) {
            anchorY + bubbleWidthPx + (4 * density).toInt()
        } else {
            preferredY
        }
        val maxAllowedY = safeBottom - btnSize - (4 * density).toInt()
        val targetY = candidateY.coerceIn(0, maxOf(0, maxAllowedY))

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
            x = clampedX
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
        val anchor = activeAnchorBubble ?: currentAnchorBubble
        val anchorX = anchor?.windowLayoutParams?.x ?: windowLayoutParams.x
        val anchorY = anchor?.windowLayoutParams?.y ?: windowLayoutParams.y
        lp.x = anchorX + ((bubbleWidthPx - pulseSize) / 2)
        lp.y = anchorY + ((bubbleHeightPx - pulseSize) / 2)
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
        val anchor = activeAnchorBubble ?: currentAnchorBubble
        val anchorX = anchor?.windowLayoutParams?.x ?: windowLayoutParams.x
        val anchorY = anchor?.windowLayoutParams?.y ?: windowLayoutParams.y
        val xOffset = anchorX + ((bubbleWidthPx - pulseSize) / 2)
        val yOffset = anchorY + ((bubbleHeightPx - pulseSize) / 2)

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

        val screenWidth = getScreenWidth()
        val safeBottom = getSafeBottom()

        val anchor = currentAnchorBubble
        val isRight = anchor?.isDockedOnRight ?: isDockedOnRight
        val anchorY = anchor?.windowLayoutParams?.y ?: windowLayoutParams.y

        if (isRight) {
            previewLayoutParams.gravity = Gravity.TOP or Gravity.RIGHT
            previewLayoutParams.x = windowX
        } else {
            previewLayoutParams.gravity = Gravity.TOP or Gravity.LEFT
            previewLayoutParams.x = windowX
        }

        val maxAvailableWidth = screenWidth - bubbleWidthPx - visualGap - padding
        tvBubblePreview.maxWidth = maxOf((120 * density).toInt(), maxAvailableWidth)

        val estHeight = previewView.measuredHeight.takeIf { it > 0 } ?: (48 * density).toInt()
        val targetY = anchorY + (12 * density).toInt() - padding
        val maxAllowedY = safeBottom - estHeight - (8 * density).toInt()
        previewLayoutParams.y = targetY.coerceIn(0, maxOf(0, maxAllowedY))

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
        val anchor = currentAnchorBubble
        if (anchor?.isTucked == true) {
            untuckBubble(anchor, animate = false)
        } else if (isTucked) {
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
        val bubble0 = bubbleItems.firstOrNull()
        if (bubble0 != null) {
            activeAnchorBubble = bubble0
        }
        if (isTucked || bubble0?.isTucked == true) {
            untuckBubble(animate = true)
            return
        }
        if (state == State.PASTED) {
            hideXButton()
            setState(State.IDLE)
        }
        if (isSlmChatActive) {
            closeSlmChat()
            return
        }
        if (currentMode == MODE_SLM) {
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
                    startSlmVoiceAction()
                }
                State.SCANNING -> {
                    stopScannerMode()
                    startSlmVoiceAction()
                }
            }
            return
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
                startRecording(forSlm = false)
            }
            State.SCANNING -> {
                toggleScannerMode()
            }
        }
    }

    /**
     * Triggered when the user clicks the AI (MODE_SLM) floating bubble:
     * Validates that a Qwen3.5 SLM model is downloaded on disk (falling back to any downloaded SLM model if available).
     * If no SLM model is downloaded yet, opens the model setup floating window so the user can download one;
     * otherwise starts ASR voice recording and captures the current focused input box's text context.
     */
    private fun startSlmVoiceAction() {
        val readyModelId = SlmModelConfig.resolveAvailableModelId(this)
        if (readyModelId == null) {
            Toast.makeText(this, R.string.toast_slm_not_downloaded, Toast.LENGTH_SHORT).show()
            openSlmChat()
            return
        }
        startRecording(forSlm = true)
    }

    private fun getInitialListeningPreviewText(): String {
        if (!isRecordingForSlm) {
            return getString(R.string.preview_listening)
        }
        val ctx = capturedSlmContext
        return when {
            ctx != null && ctx.hasSelection -> getString(R.string.preview_slm_listening_selection)
            ctx != null && !ctx.isEmpty -> getString(R.string.preview_slm_listening_context)
            else -> getString(R.string.preview_slm_listening_empty)
        }
    }

    private fun isDualEngineActive(): Boolean = ModelConfig.isDualEngineActive(this)

    private fun startRecording(forSlm: Boolean = false) {
        isAborted = false
        isRecordingForSlm = forSlm
        capturedSlmContext = if (forSlm) {
            VoiceAccessibilityService.instance?.readActiveEditableContext()
        } else {
            null
        }
        val anchor = activeAnchorBubble ?: currentAnchorBubble
        if (anchor?.isTucked == true) {
            untuckBubble(anchor, animate = false)
        } else if (isTucked) {
            untuckBubble(animate = false)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            isRecordingForSlm = false
            capturedSlmContext = null
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
            isRecordingForSlm = false
            capturedSlmContext = null
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
                isRecordingForSlm = false
                capturedSlmContext = null
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
                isRecordingForSlm = false
                capturedSlmContext = null
                setState(State.IDLE)
                showPreviewText(getString(R.string.preview_model_load_failed), autoHide = true)
            }
        }
    }

    // 雙引擎模式底層運作機制
    private fun startDualEngineRecording() {
        val stream = xAsr.createStream() ?: run {
            isRecordingForSlm = false
            capturedSlmContext = null
            showPreviewText(getString(R.string.preview_cannot_create_stream), autoHide = true)
            return
        }
        activeStream = stream
        isRecording = true
        isAborted = false
        setState(State.RECORDING)
        showXButton()
        showPreviewText(getInitialListeningPreviewText(), autoHide = false)

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
                isRecordingForSlm = false
                capturedSlmContext = null
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
                isRecordingForSlm = false
                capturedSlmContext = null
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
                    isRecordingForSlm = false
                    capturedSlmContext = null
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
        showPreviewText(getInitialListeningPreviewText(), autoHide = false)

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
                    isRecordingForSlm = false
                    capturedSlmContext = null
                    setState(State.IDLE)
                    hideXButton()
                    showPreviewText(getString(R.string.preview_transcribe_failed), autoHide = true)
                }
            } else {
                isRecordingForSlm = false
                capturedSlmContext = null
                setState(State.IDLE)
                hideXButton()
                showPreviewText(getString(R.string.preview_no_speech_detected), autoHide = true)
            }
        }
    }

    private fun startStreamingRecording() {
        val engine = xAsr
        val stream = engine.createStream() ?: run {
            isRecordingForSlm = false
            capturedSlmContext = null
            showPreviewText(getString(R.string.preview_cannot_create_stream), autoHide = true)
            return
        }
        activeStream = stream
        isRecording = true
        isAborted = false
        setState(State.RECORDING)
        showXButton()
        showPreviewText(getInitialListeningPreviewText(), autoHide = false)

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
                isRecordingForSlm = false
                capturedSlmContext = null
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
        val forSlm = isRecordingForSlm
        isRecordingForSlm = false

        if (text.isBlank()) {
            capturedSlmContext = null
            setState(State.IDLE)
            hideXButton()
            showPreviewText(getString(R.string.preview_no_text_detected), autoHide = true)
            return
        }

        if (forSlm) {
            onSlmVoiceInstructionDone(text)
            return
        }

        QuickPhrasesManager.addHistory(this, text)

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

    /**
     * Feeds the ASR-recognized voice input together with the active editable field's text (if any)
     * into [SlmInferenceEngine.processInputTask], streams live preview feedback on the bubble tooltip,
     * and directly injects/replaces the final output into the target input field with X-button Undo support.
     */
    private fun onSlmVoiceInstructionDone(voiceText: String) {
        val latestCtx = VoiceAccessibilityService.instance?.readActiveEditableContext()
        val effectiveCtx = if (latestCtx != null && !latestCtx.isEmpty) latestCtx else capturedSlmContext
        capturedSlmContext = null
        val targetText = effectiveCtx?.targetText ?: ""

        setState(State.TRANSCRIBING)
        showXButton()
        showPreviewText(getString(R.string.preview_slm_processing), autoHide = false)

        var lastShown = ""

        slmInferenceEngine.processInputTask(
            targetText = targetText,
            voiceInput = voiceText,
            callback = object : SlmInferenceEngine.StreamCallback {
                override fun onThinkingUpdate(thinking: String) {
                    if (!isAborted && state == State.TRANSCRIBING && thinking.isNotBlank()) {
                        val formatted = thinking.trim().replace(Regex("\\n{2,}"), "\n")
                        val display = if (formatted.startsWith("思考中：") || formatted.startsWith("[思考]")) {
                            formatted
                        } else {
                            "思考中：" + formatted
                        }
                        if (display != lastShown) {
                            lastShown = display
                            Handler(Looper.getMainLooper()).post {
                                if (!isAborted && state == State.TRANSCRIBING) {
                                    showPreviewText(display, autoHide = false)
                                }
                            }
                        }
                    }
                }

                override fun onAnswerUpdate(answer: String) {
                    if (!isAborted && state == State.TRANSCRIBING && answer.isNotBlank()) {
                        val formatted = answer.trim().replace(Regex("\\n{2,}"), "\n")
                        if (formatted != lastShown) {
                            lastShown = formatted
                            Handler(Looper.getMainLooper()).post {
                                if (!isAborted && state == State.TRANSCRIBING) {
                                    showPreviewText(formatted, autoHide = false)
                                }
                            }
                        }
                    }
                }

                override fun onComplete(fullMessage: SlmChatMessage) {
                    if (isAborted) return
                    val rawClean = fullMessage.getCleanAnswer().ifBlank { fullMessage.content }
                    var finalOutput = SlmInferenceEngine.sanitizeInputTaskOutput(
                        rawAnswer = rawClean,
                        sourceText = targetText
                    )
                    if (finalOutput.isBlank() && !fullMessage.thinkingContent.isNullOrBlank()) {
                        val salvaged = SlmInferenceEngine.extractFallbackAnswerFromThinking(fullMessage.thinkingContent!!)
                        if (salvaged.isNotBlank()) {
                            finalOutput = SlmInferenceEngine.sanitizeInputTaskOutput(salvaged, targetText)
                        }
                    }
                    if (finalOutput.isBlank()) {
                        setState(State.IDLE)
                        hideXButton()
                        showPreviewText(getString(R.string.preview_no_text_detected), autoHide = true)
                        return
                    }

                    QuickPhrasesManager.addHistory(this@FloatingBubbleService, finalOutput)

                    val accService = VoiceAccessibilityService.instance
                    val injected = accService?.applySlmResult(finalOutput) ?: false
                    if (injected) {
                        showPreviewText(finalOutput, autoHide = true)
                        enterPastedState()
                    } else {
                        copyToClipboardFallback(finalOutput)
                        setState(State.IDLE)
                        hideXButton()
                        showPreviewText(getString(R.string.preview_copied_with_text, finalOutput), autoHide = true)
                    }
                }

                override fun onError(error: Throwable) {
                    if (isAborted) return
                    setState(State.IDLE)
                    hideXButton()
                    showPreviewText(
                        getString(R.string.preview_slm_failed, error.localizedMessage ?: "未知錯誤"),
                        autoHide = true
                    )
                }
            }
        )
    }

    internal fun setState(s: State) {
        stateMachine.transitionTo(s)
        if (state == State.RECORDING) {
            showPulseView()
        } else {
            hidePulseView()
        }
        if (!::btnBubbleMic.isInitialized) return

        val isMultiBubble = ModelConfig.isMultiBubbleEnabled(this)
        val activeTargetBubble = if (isMultiBubble) {
            activeAnchorBubble?.takeIf { it.mode == MODE_VOICE || it.mode == MODE_SLM }
                ?: bubbleItems.firstOrNull { it.mode == MODE_VOICE || it.mode == MODE_SLM }
                ?: (activeAnchorBubble ?: bubbleItems.firstOrNull())
        } else {
            bubbleItems.firstOrNull()?.apply { mode = currentMode }
        }

        if (bubbleItems.isEmpty()) {
            when (state) {
                State.IDLE, State.PASTED, State.SCANNING -> {
                    btnBubbleMic.setBackgroundResource(R.drawable.bubble_background)
                    val (iconRes, desc) = getIconAndDescForMode(currentMode)
                    ivBubbleIcon.setImageResource(iconRes)
                    ivBubbleIcon.contentDescription = desc
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
            }
            return
        }

        for (item in bubbleItems) {
            if (item == activeTargetBubble) {
                when (state) {
                    State.IDLE, State.PASTED, State.SCANNING -> {
                        item.btnBubble.setBackgroundResource(R.drawable.bubble_background)
                        val (iconRes, desc) = getIconAndDescForMode(item.mode)
                        item.ivIcon.setImageResource(iconRes)
                        item.ivIcon.contentDescription = desc
                        item.ivIcon.visibility = View.VISIBLE
                        item.progress.visibility = View.GONE
                    }
                    State.LOADING -> {
                        item.btnBubble.setBackgroundResource(R.drawable.bubble_background)
                        item.ivIcon.visibility = View.GONE
                        item.progress.visibility = View.VISIBLE
                    }
                    State.RECORDING -> {
                        item.btnBubble.setBackgroundResource(R.drawable.bubble_background_active)
                        item.ivIcon.setImageResource(R.drawable.ic_mic_active)
                        item.ivIcon.visibility = View.VISIBLE
                        item.progress.visibility = View.GONE
                    }
                    State.TRANSCRIBING -> {
                        item.btnBubble.setBackgroundResource(R.drawable.bubble_background_active)
                        item.ivIcon.visibility = View.GONE
                        item.progress.visibility = View.VISIBLE
                    }
                }
            } else {
                item.btnBubble.setBackgroundResource(R.drawable.bubble_background)
                val (iconRes, desc) = getIconAndDescForMode(item.mode)
                item.ivIcon.setImageResource(iconRes)
                item.ivIcon.contentDescription = desc
                item.ivIcon.visibility = View.VISIBLE
                item.progress.visibility = View.GONE
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
            MODE_SLM -> R.drawable.ic_ai_sparkle to "AI 語音改寫/生成"
            else -> R.drawable.ic_mic to "懸浮語音輸入"
        }
    }

    private fun getBubbleMode(id: Int, defaultMode: Int): Int {
        val sp = getSharedPreferences(PREF_BUBBLE_MODE, Context.MODE_PRIVATE)
        return sp.getInt("bubble_mode_$id", defaultMode)
    }

    private fun setBubbleMode(id: Int, mode: Int) {
        val sp = getSharedPreferences(PREF_BUBBLE_MODE, Context.MODE_PRIVATE)
        sp.edit().putInt("bubble_mode_$id", mode).apply()
    }

    private fun updateBubbleIconForMode(mode: Int) {
        val isMultiBubble = ModelConfig.isMultiBubbleEnabled(this)
        val target = if (isMultiBubble) {
            currentAnchorBubble ?: bubbleItems.firstOrNull()
        } else {
            bubbleItems.firstOrNull()
        }
        if (target != null) {
            target.mode = mode
            val (iconRes, desc) = getIconAndDescForMode(mode)
            target.ivIcon.setImageResource(iconRes)
            target.ivIcon.contentDescription = desc
        } else if (::ivBubbleIcon.isInitialized) {
            val (iconRes, desc) = getIconAndDescForMode(mode)
            ivBubbleIcon.setImageResource(iconRes)
            ivBubbleIcon.contentDescription = desc
        }
    }

    private fun animateModeIconIntoBubble(mode: Int) {
        val anchor = currentAnchorBubble
        val targetIv = anchor?.ivIcon ?: if (::ivBubbleIcon.isInitialized) ivBubbleIcon else return
        val (iconRes, desc) = getIconAndDescForMode(mode)
        targetIv.setImageResource(iconRes)
        targetIv.contentDescription = desc
        targetIv.scaleX = 0.3f
        targetIv.scaleY = 0.3f
        targetIv.alpha = 0.6f
        targetIv.animate()
            .scaleX(1.2f)
            .scaleY(1.2f)
            .alpha(1f)
            .setDuration(160)
            .withEndAction {
                targetIv.animate()
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
        val postReplaced = if (userReplaced == converted) {
            converted
        } else {
            ModelConfig.applyPostRegexReplacements(userReplaced)
        }
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
    private fun showCapsuleMenu(item: FloatingBubbleItem? = null, startRawY: Float = 0f) {
        if (isCapsuleMenuShowing) return
        val anchor = item ?: currentAnchorBubble ?: return
        if (anchor.isTucked) {
            untuckBubble(anchor, animate = false)
        }
        hideXButtonImmediately()
        anchor.btnBubble.alpha = 0f
        val density = resources.displayMetrics.density
        capsuleMenuController.show(
            bubbleX = anchor.windowLayoutParams.x,
            bubbleY = anchor.windowLayoutParams.y,
            bubbleWidthPx = (60 * density).toInt(),
            bubbleHeightPx = (60 * density).toInt(),
            mode = anchor.mode,
            startRawY = startRawY,
            minY = 0,
            maxY = getSafeBottom(),
            screenWidth = getScreenWidth()
        )
    }

    private fun applyCapsuleSelection(mode: Int) {
        hidePreviewText()
        val isMultiBubble = ModelConfig.isMultiBubbleEnabled(this)
        val anchor = currentAnchorBubble
        if (anchor != null) {
            anchor.mode = mode
            val (iconRes, desc) = getIconAndDescForMode(mode)
            anchor.ivIcon.setImageResource(iconRes)
            anchor.ivIcon.contentDescription = desc
            if (isMultiBubble) {
                setBubbleMode(anchor.id, mode)
            }
        }
        if (!isMultiBubble) {
            currentMode = mode
            getSharedPreferences(PREF_BUBBLE_MODE, Context.MODE_PRIVATE).edit().putInt(KEY_MODE, mode).apply()
            setBubbleMode(0, mode)
        }
        HapticUtil.click(this)
        when (mode) {
            MODE_VOICE -> {
                setState(State.IDLE)
                showPreviewText(getString(R.string.preview_mode_voice), autoHide = true)
                stopScannerMode()
                stopOcrMode()
                closePhrasesDrawer()
                closeSlmChat()
            }
            MODE_OCR -> {
                setState(State.IDLE)
                showPreviewText(getString(R.string.preview_mode_ocr), autoHide = true)
                stopScannerMode()
                closePhrasesDrawer()
                closeSlmChat()
                startOcrMode()
            }
            MODE_BARCODE -> {
                setState(State.IDLE)
                showPreviewText(getString(R.string.preview_mode_barcode), autoHide = true)
                stopOcrMode()
                closePhrasesDrawer()
                closeSlmChat()
                startScannerMode()
            }
            MODE_PHRASES -> {
                setState(State.IDLE)
                showPreviewText(getString(R.string.preview_mode_phrases), autoHide = true)
                stopScannerMode()
                stopOcrMode()
                closeSlmChat()
                openPhrasesDrawer()
            }
            MODE_SLM -> {
                setState(State.IDLE)
                showPreviewText(getString(R.string.preview_mode_slm), autoHide = true)
                stopScannerMode()
                stopOcrMode()
                closePhrasesDrawer()
                closeSlmChat()
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
        if (isScannerModeActive) return
        hidePreviewText()
        val anchor = currentAnchorBubble
        if (anchor?.isTucked == true) {
            untuckBubble(anchor, animate = false)
        } else if (isTucked) {
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
        val safeBottom = getSafeBottom()

        isScannerEnlarged = ModelConfig.isCameraEnlarged(this, MODE_BARCODE)
        val maxAvailableWidth = screenWidth - bubbleWidthPx - gap - (12 * density).toInt()
        val scannerWidth = if (isScannerEnlarged) {
            (340 * density).toInt().coerceIn((200 * density).toInt(), maxAvailableWidth)
        } else {
            (250 * density).toInt().coerceAtMost(maxAvailableWidth)
        }
        val cardSize = scannerWidth - (16 * density).toInt()

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val estimatedHeight = cardSize + (120 * density).toInt()
        val isRight = anchor?.isDockedOnRight ?: isDockedOnRight
        val anchorY = anchor?.windowLayoutParams?.y ?: windowLayoutParams.y
        val micY = anchorY + (30 * density).toInt()
        val maxAllowedY = safeBottom - estimatedHeight - (8 * density).toInt()
        val targetY = (micY - (estimatedHeight / 2)).coerceIn(
            0,
            maxOf(0, maxAllowedY)
        )

        scannerLayoutParams = WindowManager.LayoutParams(
            scannerWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (isRight) {
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
        val cardPreview = scanner.findViewById<MaterialCardView>(R.id.card_scanner_preview)

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
        if (isRight) {
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
        val safeBottom = getSafeBottom()
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
        val anchor = currentAnchorBubble
        val isRight = anchor?.isDockedOnRight ?: isDockedOnRight
        val anchorY = anchor?.windowLayoutParams?.y ?: windowLayoutParams.y
        val micY = anchorY + (30 * density).toInt()
        val maxAllowedY = safeBottom - estimatedHeight - (8 * density).toInt()
        val targetY = (micY - (estimatedHeight / 2)).coerceIn(
            0,
            maxOf(0, maxAllowedY)
        )
        lp.y = targetY
        lp.gravity = if (isRight) (Gravity.TOP or Gravity.RIGHT) else (Gravity.TOP or Gravity.LEFT)
        lp.x = bubbleWidthPx + gap

        if (scanner.isAttachedToWindow) {
            windowManager.updateViewLayout(scanner, lp)
        }
    }

    private fun updateScannerPosition() {
        val scanner = scannerView ?: return
        val lp = scannerLayoutParams ?: return
        if (!isScannerModeActive || !scanner.isAttachedToWindow) return
        val density = resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()
        val gap = (10 * density).toInt()
        val screenWidth = getScreenWidth()
        val safeBottom = getSafeBottom()
        val anchor = currentAnchorBubble
        val anchorY = anchor?.windowLayoutParams?.y ?: windowLayoutParams.y
        val isRight = anchor?.isDockedOnRight ?: isDockedOnRight

        val maxAvailableWidth = screenWidth - bubbleWidthPx - gap - (12 * density).toInt()
        val targetWidth = if (isScannerEnlarged) {
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
        lp.y = (micY - (estimatedHeight / 2)).coerceIn(
            0,
            maxOf(0, maxAllowedY)
        )
        lp.gravity = if (isRight) (Gravity.TOP or Gravity.RIGHT) else (Gravity.TOP or Gravity.LEFT)
        lp.x = bubbleWidthPx + gap
        try {
            windowManager.updateViewLayout(scanner, lp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update scanner window layout", e)
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
        val isRight = currentAnchorBubble?.isDockedOnRight ?: isDockedOnRight
        val targetTranslationX = if (isRight) (40 * density) else -(40 * density)
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
        if (isOcrModeActive) return
        hidePreviewText()
        val anchor = currentAnchorBubble
        if (anchor?.isTucked == true) {
            untuckBubble(anchor, animate = false)
        } else if (isTucked) {
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
        val safeBottom = getSafeBottom()

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        isOcrEnlarged = ModelConfig.isCameraEnlarged(this, MODE_OCR)
        val maxAvailableWidth = screenWidth - bubbleWidthPx - gap - (12 * density).toInt()
        val ocrWidth = if (isOcrEnlarged) {
            (340 * density).toInt().coerceIn((200 * density).toInt(), maxAvailableWidth)
        } else {
            (250 * density).toInt().coerceAtMost(maxAvailableWidth)
        }

        val cardSize = ocrWidth - (16 * density).toInt()
        val estimatedHeight = cardSize + (120 * density).toInt()
        val isRight = anchor?.isDockedOnRight ?: isDockedOnRight
        val anchorY = anchor?.windowLayoutParams?.y ?: windowLayoutParams.y
        val micY = anchorY + (30 * density).toInt()
        val maxAllowedY = safeBottom - estimatedHeight - (8 * density).toInt()
        val targetY = (micY - (estimatedHeight / 2)).coerceIn(
            0,
            maxOf(0, maxAllowedY)
        )

        ocrWindowLayoutParams = WindowManager.LayoutParams(
            ocrWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (isRight) {
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
        val cardPreview = ocr.findViewById<MaterialCardView>(R.id.card_ocr_preview)

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

        if (isRight) {
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
        val safeBottom = getSafeBottom()
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
        val anchor = currentAnchorBubble
        val isRight = anchor?.isDockedOnRight ?: isDockedOnRight
        val anchorY = anchor?.windowLayoutParams?.y ?: windowLayoutParams.y
        val micY = anchorY + (30 * density).toInt()
        val maxAllowedY = safeBottom - estimatedHeight - (8 * density).toInt()
        val targetY = (micY - (estimatedHeight / 2)).coerceIn(
            0,
            maxOf(0, maxAllowedY)
        )
        lp.y = targetY
        lp.gravity = if (isRight) (Gravity.TOP or Gravity.RIGHT) else (Gravity.TOP or Gravity.LEFT)
        lp.x = bubbleWidthPx + gap

        if (ocr.isAttachedToWindow) {
            windowManager.updateViewLayout(ocr, lp)
        }
    }

    private fun updateOcrPosition() {
        val ocr = ocrWindowView ?: return
        val lp = ocrWindowLayoutParams ?: return
        if (!isOcrModeActive || !ocr.isAttachedToWindow) return
        val density = resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()
        val gap = (10 * density).toInt()
        val screenWidth = getScreenWidth()
        val safeBottom = getSafeBottom()
        val anchor = currentAnchorBubble
        val anchorY = anchor?.windowLayoutParams?.y ?: windowLayoutParams.y
        val isRight = anchor?.isDockedOnRight ?: isDockedOnRight

        val maxAvailableWidth = screenWidth - bubbleWidthPx - gap - (12 * density).toInt()
        val targetWidth = if (isOcrEnlarged) {
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
        lp.y = (micY - (estimatedHeight / 2)).coerceIn(
            0,
            maxOf(0, maxAllowedY)
        )
        lp.gravity = if (isRight) (Gravity.TOP or Gravity.RIGHT) else (Gravity.TOP or Gravity.LEFT)
        lp.x = bubbleWidthPx + gap
        try {
            windowManager.updateViewLayout(ocr, lp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update OCR window layout", e)
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
        val isRight = currentAnchorBubble?.isDockedOnRight ?: isDockedOnRight
        val targetTranslationX = if (isRight) (40 * density) else -(40 * density)
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
        if (code.isNotBlank()) {
            QuickPhrasesManager.addHistory(this, code)
        }
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
                tvStatus.text = "滑動拉框圈選或點選欲輸入之文字區塊"
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
                    val sep = ModelConfig.ocrSeparator(this@FloatingBubbleService)
                    val processed = if (ModelConfig.isOcrSmartStitchEnabled(this@FloatingBubbleService)) {
                        TextStitcher.stitch(
                            recognizedBlocks,
                            fallbackSep = sep,
                            panguSpacing = ModelConfig.isOcrPanguSpacingEnabled(this@FloatingBubbleService)
                        )
                    } else {
                        recognizedBlocks.joinToString(sep) { it.text }
                    }
                    HapticUtil.heavyClick(this@FloatingBubbleService)
                    if (processed.isNotBlank()) {
                        QuickPhrasesManager.addHistory(this@FloatingBubbleService, processed)
                    }

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
        if (isPhrasesDrawerActive) return
        hidePreviewText()
        val anchor = currentAnchorBubble
        if (anchor?.isTucked == true) {
            untuckBubble(anchor, animate = false)
        } else if (isTucked) {
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
        val screenWidth = getScreenWidth()
        val safeBottom = getSafeBottom()
        val maxDrawerWidth = screenWidth - bubbleWidthPx - gap - (12 * density).toInt()
        val drawerWidth = (260 * density).toInt().coerceAtMost(maxDrawerWidth)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
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

        quickPhrasesAdapter = QuickPhrasesAdapter(
            items = drawerItems,
            onItemClick = { text ->
                insertQuickPhrase(text)
            },
            onEditPhrase = { phrase ->
                HapticUtil.click(this)
                showEditPhraseDialog(phrase)
            },
            onDeletePhrase = { phrase ->
                HapticUtil.click(this)
                showDeletePhraseDialog(phrase)
            },
            onSaveHistoryAsPhrase = { text ->
                HapticUtil.click(this)
                QuickPhrasesManager.add(this, text)
                Toast.makeText(this, R.string.toast_phrase_saved_from_history, Toast.LENGTH_SHORT).show()
                refreshPhrasesList()
            },
            onDeleteHistory = { historyIndex ->
                HapticUtil.click(this)
                QuickPhrasesManager.removeHistoryAt(this, historyIndex)
                refreshPhrasesList()
            },
            onClearHistory = {
                HapticUtil.click(this)
                QuickPhrasesManager.clearHistory(this)
                Toast.makeText(this, R.string.toast_history_cleared, Toast.LENGTH_SHORT).show()
                refreshPhrasesList()
            },
            onStartDrag = { viewHolder ->
                phrasesItemTouchHelper?.startDrag(viewHolder)
            }
        )
        rvPhrases.layoutManager = LinearLayoutManager(themedCtx)
        rvPhrases.adapter = quickPhrasesAdapter

        setupDragAndDrop(rvPhrases)
        refreshPhrasesList()

        // 測量抽屜實際高度以計算貼齊懸浮球的精確 Y 座標與縮放軸心
        drawer.measure(
            View.MeasureSpec.makeMeasureSpec(drawerWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val maxAllowedHeight = maxOf((120 * density).toInt(), safeBottom - (16 * density).toInt())
        val actualHeight = drawer.measuredHeight.coerceAtLeast((100 * density).toInt())
        val finalHeight = actualHeight.coerceAtMost(maxAllowedHeight)

        val isRight = anchor?.isDockedOnRight ?: isDockedOnRight
        val anchorY = anchor?.windowLayoutParams?.y ?: windowLayoutParams.y
        val maxAllowedY = safeBottom - finalHeight - (8 * density).toInt()
        val targetY = anchorY.coerceIn(0, maxOf(0, maxAllowedY))

        phrasesDrawerLayoutParams = WindowManager.LayoutParams(
            drawerWidth,
            if (actualHeight > maxAllowedHeight) maxAllowedHeight else WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (isRight) {
                gravity = Gravity.TOP or Gravity.RIGHT
                x = bubbleWidthPx + gap
            } else {
                gravity = Gravity.TOP or Gravity.LEFT
                x = bubbleWidthPx + gap
            }
            y = targetY
        }

        // 以懸浮球垂直中心為軸心展開動畫，營造從懸浮球直接呼出的視覺感受
        val bubbleCenterY = anchorY + (30 * density)
        val relativePivotY = (bubbleCenterY - targetY).coerceIn(0f, finalHeight.toFloat())

        if (isRight) {
            drawer.pivotX = drawerWidth.toFloat()
            drawer.translationX = (24 * density)
        } else {
            drawer.pivotX = 0f
            drawer.translationX = -(24 * density)
        }
        drawer.pivotY = relativePivotY
        drawer.alpha = 0f
        drawer.scaleX = 0.6f
        drawer.scaleY = 0.6f

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

    private fun setupDragAndDrop(recyclerView: RecyclerView) {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled(): Boolean = true
            override fun isItemViewSwipeEnabled(): Boolean = false

            override fun getMovementFlags(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                return if (viewHolder is QuickPhrasesAdapter.PhraseViewHolder) {
                    makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                } else {
                    0
                }
            }

            override fun canDropOver(
                rv: RecyclerView,
                current: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return target is QuickPhrasesAdapter.PhraseViewHolder
            }

            override fun onMove(
                rv: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false
                if (viewHolder !is QuickPhrasesAdapter.PhraseViewHolder || target !is QuickPhrasesAdapter.PhraseViewHolder) return false

                quickPhrasesAdapter?.moveItem(fromPos, toPos)
                HapticUtil.tick(this@FloatingBubbleService)
                return true
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    HapticUtil.heavyClick(this@FloatingBubbleService)
                    viewHolder.itemView.animate()
                        .scaleX(1.03f)
                        .scaleY(1.03f)
                        .setDuration(120)
                        .start()
                    viewHolder.itemView.translationZ = 12f * resources.displayMetrics.density
                    viewHolder.itemView.setBackgroundResource(R.drawable.bg_phrase_item_dragging)
                }
            }

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                viewHolder.itemView.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(120)
                    .start()
                viewHolder.itemView.translationZ = 0f
                viewHolder.itemView.setBackgroundResource(R.drawable.bg_phrase_item)
                HapticUtil.click(this@FloatingBubbleService)
                persistPhrasesFromAdapter()
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        }

        phrasesItemTouchHelper = ItemTouchHelper(callback).also {
            it.attachToRecyclerView(recyclerView)
        }
    }

    private fun persistPhrasesFromAdapter() {
        val updatedPhrases = drawerItems
            .filterIsInstance<DrawerItem.Phrase>()
            .map { it.text }
        QuickPhrasesManager.save(this, updatedPhrases)
    }

    private fun closePhrasesDrawer(hideX: Boolean = true) {
        if (!isPhrasesDrawerActive) return
        isPhrasesDrawerActive = false
        if (hideX) {
            hideXButton()
        }
        val drawer = phrasesDrawerView ?: return
        val density = resources.displayMetrics.density
        val anchor = currentAnchorBubble
        val isRight = anchor?.isDockedOnRight ?: isDockedOnRight
        val anchorY = anchor?.windowLayoutParams?.y ?: windowLayoutParams.y
        val targetTranslationX = if (isRight) (24 * density) else -(24 * density)
        val bubbleCenterY = anchorY + (30 * density)
        val currentTargetY = phrasesDrawerLayoutParams?.y ?: anchorY
        val drawerHeight = drawer.height.toFloat().takeIf { it > 0f } ?: (100 * density)
        drawer.pivotY = (bubbleCenterY - currentTargetY).coerceIn(0f, drawerHeight)
        drawer.pivotX = if (isRight) (drawer.width.toFloat().takeIf { it > 0f } ?: (260 * density)) else 0f

        drawer.animate()
            .alpha(0f)
            .translationX(targetTranslationX)
            .scaleX(0.6f)
            .scaleY(0.6f)
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
                phrasesItemTouchHelper = null
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
        val screenWidth = getScreenWidth()
        val safeBottom = getSafeBottom()
        val maxDrawerWidth = screenWidth - bubbleWidthPx - gap - (12 * density).toInt()
        val drawerWidth = (260 * density).toInt().coerceAtMost(maxDrawerWidth)

        drawer.measure(
            View.MeasureSpec.makeMeasureSpec(drawerWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val maxAllowedHeight = maxOf((120 * density).toInt(), safeBottom - (16 * density).toInt())
        val actualHeight = drawer.measuredHeight.coerceAtLeast((100 * density).toInt())
        val finalHeight = actualHeight.coerceAtMost(maxAllowedHeight)

        val anchor = currentAnchorBubble
        val isRight = anchor?.isDockedOnRight ?: isDockedOnRight
        val anchorY = anchor?.windowLayoutParams?.y ?: windowLayoutParams.y

        if (isRight) {
            lp.gravity = Gravity.TOP or Gravity.RIGHT
            lp.x = bubbleWidthPx + gap
        } else {
            lp.gravity = Gravity.TOP or Gravity.LEFT
            lp.x = bubbleWidthPx + gap
        }
        val maxAllowedY = safeBottom - finalHeight - (8 * density).toInt()
        lp.width = drawerWidth
        lp.height = if (actualHeight > maxAllowedHeight) maxAllowedHeight else WindowManager.LayoutParams.WRAP_CONTENT
        lp.y = anchorY.coerceIn(0, maxOf(0, maxAllowedY))
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

        val history = QuickPhrasesManager.loadHistory(this)
        val phrases = QuickPhrasesManager.load(this)

        drawerItems.clear()

        // 歷史紀錄置頂（依設定保留筆數）
        if (history.isNotEmpty()) {
            val historyLimit = ModelConfig.getHistoryLimit(this)
            drawerItems.add(DrawerItem.Header(getString(R.string.header_recent_history, historyLimit), canClear = true))
            history.forEachIndexed { i, text ->
                drawerItems.add(DrawerItem.History(text, i))
            }
        }

        // 常用片語區段
        if (phrases.isNotEmpty()) {
            drawerItems.add(DrawerItem.Header(getString(R.string.header_quick_phrases), canClear = false))
            phrases.forEachIndexed { i, text ->
                drawerItems.add(DrawerItem.Phrase(text, i))
            }
        }

        if (drawerItems.isEmpty()) {
            emptyView?.visibility = View.VISIBLE
            rvPhrases.visibility = View.GONE
        } else {
            emptyView?.visibility = View.GONE
            rvPhrases.visibility = View.VISIBLE
            val density = resources.displayMetrics.density
            rvPhrases.layoutParams = rvPhrases.layoutParams.apply {
                height = if (drawerItems.size >= 5) {
                    (280 * density).toInt()
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

    private fun showEditPhraseDialog(currentPhrase: String) {
        val dialogView = LayoutInflater.from(themedCtx).inflate(R.layout.dialog_phrase_entry, null)
        val etPhrase = dialogView.findViewById<EditText>(R.id.et_phrase)
        etPhrase.setText(currentPhrase)
        etPhrase.setSelection(currentPhrase.length)

        val dialog = MaterialAlertDialogBuilder(themedCtx)
            .setTitle(R.string.dialog_phrase_edit_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                HapticUtil.click(this)
                val newText = etPhrase.text.toString().trim()
                if (newText.isNotBlank()) {
                    val currentPhrases = QuickPhrasesManager.load(this).toMutableList()
                    val idx = currentPhrases.indexOf(currentPhrase)
                    if (idx >= 0) {
                        currentPhrases[idx] = newText
                        QuickPhrasesManager.save(this, currentPhrases)
                    }
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

    private fun showDeletePhraseDialog(phrase: String) {
        val dialog = MaterialAlertDialogBuilder(themedCtx)
            .setTitle(R.string.dialog_phrase_delete_title)
            .setMessage(getString(R.string.dialog_phrase_delete_confirm, phrase))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                HapticUtil.click(this)
                val currentPhrases = QuickPhrasesManager.load(this).toMutableList()
                currentPhrases.remove(phrase)
                QuickPhrasesManager.save(this, currentPhrases)
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

    private sealed class DrawerItem {
        data class Header(val title: String, val canClear: Boolean = false) : DrawerItem()
        data class History(val text: String, val originalIndex: Int) : DrawerItem()
        data class Phrase(val text: String, val originalIndex: Int) : DrawerItem()
    }

    private class QuickPhrasesAdapter(
        private val items: MutableList<DrawerItem>,
        private val onItemClick: (String) -> Unit,
        private val onEditPhrase: (String) -> Unit,
        private val onDeletePhrase: (String) -> Unit,
        private val onSaveHistoryAsPhrase: (String) -> Unit,
        private val onDeleteHistory: (Int) -> Unit,
        private val onClearHistory: () -> Unit,
        private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_HEADER = 0
            private const val TYPE_HISTORY = 1
            private const val TYPE_PHRASE = 2
        }

        class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tv_header_title)
            val btnAction: TextView = view.findViewById(R.id.btn_header_action)
        }

        class HistoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val root: View = view.findViewById(R.id.layout_history_item)
            val tvText: TextView = view.findViewById(R.id.tv_history_text)
            val btnSave: ImageButton = view.findViewById(R.id.btn_save_as_phrase)
            val btnDelete: ImageButton = view.findViewById(R.id.btn_delete_history)
        }

        class PhraseViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val root: View = view.findViewById(R.id.layout_phrase_item)
            val tvText: TextView = view.findViewById(R.id.tv_phrase_text)
            val btnEdit: ImageButton = view.findViewById(R.id.btn_edit_phrase)
            val btnDelete: ImageButton = view.findViewById(R.id.btn_delete_phrase)
            val ivDragHandle: ImageView = view.findViewById(R.id.iv_drag_handle)
        }

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is DrawerItem.Header -> TYPE_HEADER
            is DrawerItem.History -> TYPE_HISTORY
            is DrawerItem.Phrase -> TYPE_PHRASE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_drawer_header, parent, false))
                TYPE_HISTORY -> HistoryViewHolder(inflater.inflate(R.layout.item_quick_history, parent, false))
                else -> PhraseViewHolder(inflater.inflate(R.layout.item_quick_phrase, parent, false))
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is DrawerItem.Header -> {
                    val h = holder as HeaderViewHolder
                    h.tvTitle.text = item.title
                    if (item.canClear) {
                        h.btnAction.visibility = View.VISIBLE
                        h.btnAction.setOnClickListener { onClearHistory() }
                    } else {
                        h.btnAction.visibility = View.GONE
                    }
                }
                is DrawerItem.History -> {
                    val h = holder as HistoryViewHolder
                    h.tvText.text = item.text
                    h.root.setOnClickListener { onItemClick(item.text) }
                    h.btnSave.setOnClickListener { onSaveHistoryAsPhrase(item.text) }
                    h.btnDelete.setOnClickListener { onDeleteHistory(item.originalIndex) }
                }
                is DrawerItem.Phrase -> {
                    val h = holder as PhraseViewHolder
                    h.tvText.text = item.text
                    h.root.setOnClickListener { onItemClick(item.text) }
                    h.btnEdit.setOnClickListener { onEditPhrase(item.text) }
                    h.btnDelete.setOnClickListener { onDeletePhrase(item.text) }
                    h.ivDragHandle.setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            onStartDrag(holder)
                        }
                        false
                    }
                }
            }
        }

        override fun getItemCount(): Int = items.size

        fun moveItem(fromPos: Int, toPos: Int) {
            if (fromPos == toPos) return
            val item = items.removeAt(fromPos)
            items.add(toPos, item)
            notifyItemMoved(fromPos, toPos)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // SLM (Small Language Model) Floating Chat Methods
    // ══════════════════════════════════════════════════════════════════
    private fun toggleSlmChat() {
        if (isSlmChatActive) {
            closeSlmChat()
        } else {
            openSlmChat()
        }
    }

    private fun openSlmChat() {
        if (isSlmChatActive) return
        hidePreviewText()
        val anchor = currentAnchorBubble
        if (anchor?.isTucked == true) {
            untuckBubble(anchor, animate = false)
        } else if (isTucked) {
            untuckBubble(animate = false)
        }
        if (isScannerModeActive) stopScannerMode()
        if (isOcrModeActive) stopOcrMode()
        if (isPhrasesDrawerActive) closePhrasesDrawer()

        if (slmChatView == null) {
            slmChatView = LayoutInflater.from(themedCtx).inflate(R.layout.layout_floating_slm_chat, null)
        }
        val chatView = slmChatView ?: return

        val density = resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()
        val gap = (10 * density).toInt()
        val screenHeight = getScreenHeight()
        val screenWidth = getScreenWidth()
        val safeBottom = getSafeBottom()
        val maxChatWidth = screenWidth - bubbleWidthPx - gap - (12 * density).toInt()
        val chatWidth = (320 * density).toInt().coerceAtMost(maxChatWidth)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val btnClose = chatView.findViewById<ImageButton>(R.id.btn_close_slm_chat)
        val tvAccelBadge = chatView.findViewById<TextView>(R.id.tv_slm_accel_badge)
        val btnModelToggle = chatView.findViewById<TextView>(R.id.btn_slm_model_toggle)
        val btnThinkingToggle = chatView.findViewById<LinearLayout>(R.id.btn_slm_thinking_toggle)
        val ivThinkingIcon = chatView.findViewById<ImageView>(R.id.iv_slm_thinking_icon)
        val tvThinkingLabel = chatView.findViewById<TextView>(R.id.tv_slm_thinking_label)
        val rvChat = chatView.findViewById<RecyclerView>(R.id.rv_slm_chat)
        val layoutEmpty = chatView.findViewById<View>(R.id.layout_slm_empty)
        val btnMic = chatView.findViewById<ImageButton>(R.id.btn_slm_mic)
        val etPrompt = chatView.findViewById<EditText>(R.id.et_slm_prompt)
        val btnSend = chatView.findViewById<ImageButton>(R.id.btn_slm_send)
        val btnModeFast = chatView.findViewById<TextView>(R.id.btn_mode_fast)
        val btnModeBullet = chatView.findViewById<TextView>(R.id.btn_mode_bullet)
        val btnModeRefine = chatView.findViewById<TextView>(R.id.btn_mode_refine)

        tvAccelBadge?.setOnClickListener {
            HapticUtil.click(this)
            val desc = SlmModelConfig.getHardwareAccelDescription(this)
            Toast.makeText(this, desc, Toast.LENGTH_SHORT).show()
        }

        btnClose.setOnClickListener {
            HapticUtil.click(this)
            closeSlmChat()
        }

        val tvEmptyTitle = chatView.findViewById<TextView>(R.id.tv_slm_empty_title)
        val tvEmptyDesc = chatView.findViewById<TextView>(R.id.tv_slm_empty_desc)
        val btnEmptyDownload = chatView.findViewById<MaterialButton>(R.id.btn_slm_empty_download)
        val btnEmptyLoad = chatView.findViewById<MaterialButton>(R.id.btn_slm_empty_load)
        val progressEmpty = chatView.findViewById<LinearProgressIndicator>(R.id.progress_slm_empty)
        val tvEmptyProgress = chatView.findViewById<TextView>(R.id.tv_slm_empty_progress)

        // Refresh Model UI & Empty State Actions
        fun updateModelUi() {
            val selectedModel = SlmModelConfig.getSelectedModel(this)
            val meta = SlmModelConfig.getModelMetadata(selectedModel)
            val isDownloaded = SlmModelConfig.isModelDownloaded(this, selectedModel)
            val isLoaded = slmInferenceEngine.isLoaded(selectedModel)

            val statusTag = when {
                isLoaded -> "· 已載入"
                isDownloaded -> "· 已就緒"
                else -> "· 未下載"
            }
            btnModelToggle.text = "${meta.chipLabel} $statusTag"

            val active = ModelDownloadState.active.value
            if (active != null && active.engine == selectedModel) {
                btnEmptyDownload.visibility = View.VISIBLE
                btnEmptyDownload.text = "取消下載"
                btnEmptyLoad.visibility = View.GONE
                progressEmpty.visibility = View.VISIBLE
                progressEmpty.isIndeterminate = active.progress.percent < 0
                if (active.progress.percent >= 0) progressEmpty.progress = active.progress.percent
                tvEmptyProgress.visibility = View.VISIBLE
                tvEmptyProgress.text = "${active.progress.label} (${active.progress.percent}%)"
                tvEmptyTitle.text = "正在下載 ${meta.displayName}…"
                tvEmptyDesc.text = "檔案大小約 ${meta.quantizedSize}，下載將於背景進行"
            } else {
                progressEmpty.visibility = View.GONE
                tvEmptyProgress.visibility = View.GONE

                if (isDownloaded) {
                    btnEmptyDownload.visibility = View.GONE
                    if (isLoaded) {
                        btnEmptyLoad.visibility = View.GONE
                        tvEmptyTitle.text = "端側 ${meta.displayName} 已載入 · GPU/NPU 加速中"
                        tvEmptyDesc.text = "• 運算由 GPU (Vulkan) / NPU (NNAPI) 全層卸載加速\n• 點擊麥克風可用離線語音下達指令\n• 預設簡短扼要回覆，支援思考推導\n• 回覆後可一鍵複製或直接貼入輸入框"
                    } else {
                        btnEmptyLoad.visibility = View.VISIBLE
                        btnEmptyLoad.text = "載入至記憶體"
                        tvEmptyTitle.text = "端側 ${meta.displayName} 已就緒 (未載入)"
                        tvEmptyDesc.text = "模型檔案已下載完成。點擊下方按鈕預熱載入至記憶體，或直接發送問題自動載入。"
                    }
                } else {
                    btnEmptyLoad.visibility = View.GONE
                    btnEmptyDownload.visibility = View.VISIBLE
                    btnEmptyDownload.text = "下載此模型 (${meta.quantizedSize})"
                    tvEmptyTitle.text = "端側 ${meta.displayName} 尚未下載 (${meta.parameterCount})"
                    tvEmptyDesc.text = "${meta.description}\n\n檔案大小：${meta.quantizedSize}，點擊下方按鈕即可下載至手機本機運行。"
                }
            }
        }
        updateModelUi()

        btnEmptyDownload.setOnClickListener {
            HapticUtil.click(this)
            val current = SlmModelConfig.getSelectedModel(this)
            val active = ModelDownloadState.active.value
            if (active != null && active.engine == current) {
                ModelDownloadService.cancel(this)
                Toast.makeText(this, R.string.toast_slm_download_cancelled, Toast.LENGTH_SHORT).show()
            } else {
                ModelDownloadService.start(this, current)
                val m = SlmModelConfig.getModelMetadata(current)
                Toast.makeText(this, getString(R.string.toast_slm_download_started, m.displayName), Toast.LENGTH_SHORT).show()
            }
            updateModelUi()
        }

        btnEmptyLoad.setOnClickListener {
            HapticUtil.click(this)
            val current = SlmModelConfig.getSelectedModel(this)
            scope.launch {
                btnEmptyLoad.isEnabled = false
                btnEmptyLoad.text = "載入中…"
                val res = slmInferenceEngine.loadModel(current)
                btnEmptyLoad.isEnabled = true
                if (res.isSuccess) {
                    val m = SlmModelConfig.getModelMetadata(current)
                    Toast.makeText(this@FloatingBubbleService, getString(R.string.toast_slm_loaded_memory, m.displayName), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@FloatingBubbleService, getString(R.string.toast_slm_load_failed, res.exceptionOrNull()?.message ?: "未知錯誤"), Toast.LENGTH_SHORT).show()
                }
                updateModelUi()
            }
        }

        // Listen for download progress updates while SLM chat is open
        scope.launch {
            ModelDownloadState.active.collect {
                if (isSlmChatActive && slmChatView != null) {
                    updateModelUi()
                }
            }
        }
        scope.launch {
            ModelDownloadState.results.collect { (engine, _) ->
                if (isSlmChatActive && slmChatView != null && SlmModelConfig.AVAILABLE_MODELS.any { it.id == engine }) {
                    updateModelUi()
                }
            }
        }

        btnModelToggle.setOnClickListener {
            HapticUtil.click(this)
            val current = SlmModelConfig.getSelectedModel(this)
            val nextMeta = SlmModelConfig.getNextModel(current)
            SlmModelConfig.setSelectedModel(this, nextMeta.id)
            if (SlmModelConfig.isModelDownloaded(this, nextMeta.id) && !slmInferenceEngine.isLoaded(nextMeta.id)) {
                scope.launch {
                    slmInferenceEngine.loadModel(nextMeta.id)
                    updateModelUi()
                }
            }
            updateModelUi()
            Toast.makeText(this, getString(R.string.toast_slm_switched_model, "${nextMeta.displayName} (${nextMeta.shortTag})"), Toast.LENGTH_SHORT).show()
        }

        btnModelToggle.setOnLongClickListener {
            HapticUtil.heavyClick(this)
            val popup = android.widget.PopupMenu(themedCtx, btnModelToggle)
            SlmModelConfig.AVAILABLE_MODELS.forEachIndexed { index, meta ->
                val current = SlmModelConfig.getSelectedModel(this)
                val checkMark = if (meta.id == current) "● " else "   "
                val isDownloaded = SlmModelConfig.isModelDownloaded(this, meta.id)
                val isLoaded = slmInferenceEngine.isLoaded(meta.id)
                val statusTag = when {
                    isLoaded -> "[記憶體已載入]"
                    isDownloaded -> "[已下載就緒]"
                    else -> "[未下載 · ${meta.quantizedSize}]"
                }
                popup.menu.add(0, index, index, "$checkMark${meta.displayName} (${meta.chipLabel}) · $statusTag")
            }
            popup.setOnMenuItemClickListener { menuItem ->
                HapticUtil.click(this)
                val selected = SlmModelConfig.AVAILABLE_MODELS.getOrNull(menuItem.itemId)
                if (selected != null) {
                    SlmModelConfig.setSelectedModel(this, selected.id)
                    updateModelUi()
                    if (SlmModelConfig.isModelDownloaded(this, selected.id)) {
                        scope.launch {
                            slmInferenceEngine.loadModel(selected.id)
                            updateModelUi()
                        }
                    }
                    Toast.makeText(this, getString(R.string.toast_slm_switched_model, "${selected.displayName} (${selected.shortTag})"), Toast.LENGTH_SHORT).show()
                }
                true
            }
            popup.show()
            true
        }

        // Refresh Thinking UI
        fun updateThinkingUi() {
            val thinkingEnabled = SlmModelConfig.isThinkingEnabled(this)
            if (thinkingEnabled) {
                btnThinkingToggle.setBackgroundResource(R.drawable.bg_slm_chip_active)
                ivThinkingIcon.setColorFilter(android.graphics.Color.parseColor("#FBBF24"))
                tvThinkingLabel.setTextColor(android.graphics.Color.parseColor("#FEF3C7"))
            } else {
                btnThinkingToggle.setBackgroundResource(R.drawable.bg_slm_chip_inactive)
                ivThinkingIcon.setColorFilter(android.graphics.Color.parseColor("#94A3B8"))
                tvThinkingLabel.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            }
        }
        updateThinkingUi()

        btnThinkingToggle.setOnClickListener {
            HapticUtil.click(this)
            val next = !SlmModelConfig.isThinkingEnabled(this)
            SlmModelConfig.setThinkingEnabled(this, next)
            updateThinkingUi()
            val msgRes = if (next) R.string.toast_slm_thinking_on else R.string.toast_slm_thinking_off
            Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show()
        }

        // Refresh Output Mode UI
        fun updateModeUi() {
            val currentMode = SlmModelConfig.getOutputMode(this)
            fun styleModeBtn(btn: TextView, isActive: Boolean) {
                if (isActive) {
                    btn.setBackgroundResource(R.drawable.bg_slm_chip_active)
                    btn.setTextColor(android.graphics.Color.parseColor("#E0F2FE"))
                } else {
                    btn.setBackgroundResource(R.drawable.bg_slm_chip_inactive)
                    btn.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
                }
            }
            styleModeBtn(btnModeFast, currentMode == SlmModelConfig.OUTPUT_MODE_FAST)
            styleModeBtn(btnModeBullet, currentMode == SlmModelConfig.OUTPUT_MODE_BULLET)
            styleModeBtn(btnModeRefine, currentMode == SlmModelConfig.OUTPUT_MODE_REFINE)
        }
        updateModeUi()

        btnModeFast.setOnClickListener {
            HapticUtil.click(this)
            SlmModelConfig.setOutputMode(this, SlmModelConfig.OUTPUT_MODE_FAST)
            updateModeUi()
            Toast.makeText(this, getString(R.string.toast_slm_mode_switched, getString(R.string.slm_mode_fast)), Toast.LENGTH_SHORT).show()
        }

        btnModeBullet.setOnClickListener {
            HapticUtil.click(this)
            SlmModelConfig.setOutputMode(this, SlmModelConfig.OUTPUT_MODE_BULLET)
            updateModeUi()
            Toast.makeText(this, getString(R.string.toast_slm_mode_switched, getString(R.string.slm_mode_bullet)), Toast.LENGTH_SHORT).show()
        }

        btnModeRefine.setOnClickListener {
            HapticUtil.click(this)
            SlmModelConfig.setOutputMode(this, SlmModelConfig.OUTPUT_MODE_REFINE)
            updateModeUi()
            Toast.makeText(this, getString(R.string.toast_slm_mode_switched, getString(R.string.slm_mode_refine)), Toast.LENGTH_SHORT).show()
        }

        // Chat RecyclerView & Adapter
        slmChatAdapter = SlmChatAdapter(
            messages = slmMessages,
            onCopyClick = { msg -> copySlmAnswer(msg) },
            onPasteClick = { msg -> pasteSlmAnswer(msg) }
        )
        rvChat.layoutManager = LinearLayoutManager(themedCtx).apply {
            stackFromEnd = true
        }
        rvChat.adapter = slmChatAdapter
        layoutEmpty.visibility = if (slmMessages.isEmpty()) View.VISIBLE else View.GONE

        // Mic & Send Buttons
        btnMic.setOnClickListener {
            HapticUtil.click(this)
            startOrStopVoiceForSlm(btnMic, etPrompt)
        }

        btnSend.setOnClickListener {
            val text = etPrompt.text.toString().trim()
            if (text.isNotEmpty()) {
                HapticUtil.click(this)
                submitSlmPrompt(text, etPrompt, rvChat, layoutEmpty)
            }
        }

        etPrompt.setOnEditorActionListener { _, _, _ ->
            val text = etPrompt.text.toString().trim()
            if (text.isNotEmpty()) {
                HapticUtil.click(this)
                submitSlmPrompt(text, etPrompt, rvChat, layoutEmpty)
                true
            } else {
                false
            }
        }

        // Window Positioning
        val maxAllowedHeight = maxOf((240 * density).toInt(), safeBottom - (16 * density).toInt())
        val defaultChatHeight = (440 * density).toInt().coerceAtMost(maxAllowedHeight)

        val isRight = anchor?.isDockedOnRight ?: isDockedOnRight
        val anchorY = anchor?.windowLayoutParams?.y ?: windowLayoutParams.y
        val maxAllowedY = safeBottom - defaultChatHeight - (8 * density).toInt()
        val targetY = anchorY.coerceIn(0, maxOf(0, maxAllowedY))

        slmChatLayoutParams = WindowManager.LayoutParams(
            chatWidth,
            defaultChatHeight,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (isRight) {
                gravity = Gravity.TOP or Gravity.RIGHT
                x = bubbleWidthPx + gap
            } else {
                gravity = Gravity.TOP or Gravity.LEFT
                x = bubbleWidthPx + gap
            }
            y = targetY
        }

        val bubbleCenterY = anchorY + (30 * density)
        val relativePivotY = (bubbleCenterY - targetY).coerceIn(0f, defaultChatHeight.toFloat())

        chatView.pivotX = if (isRight) chatWidth.toFloat() else 0f
        chatView.pivotY = relativePivotY
        chatView.alpha = 0f
        chatView.scaleX = 0.5f
        chatView.scaleY = 0.5f
        chatView.translationX = if (isRight) (20 * density) else -(20 * density)

        try {
            if (chatView.isAttachedToWindow) {
                windowManager.removeView(chatView)
            }
            windowManager.addView(chatView, slmChatLayoutParams)
            isSlmChatActive = true
            showXButton()

            chatView.animate()
                .alpha(1f)
                .translationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(240)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .start()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add slmChatView", e)
        }
    }

    private fun closeSlmChat(hideX: Boolean = true) {
        if (!isSlmChatActive) return
        isSlmChatActive = false
        if (isSlmVoiceRecording) {
            val btnMic = slmChatView?.findViewById<ImageButton>(R.id.btn_slm_mic)
            if (btnMic != null) stopRecordingForSlm(btnMic)
        }
        slmInferenceEngine.cancelCurrent()

        if (hideX) {
            hideXButton()
        }
        val chat = slmChatView ?: return
        val density = resources.displayMetrics.density
        val anchor = currentAnchorBubble
        val isRight = anchor?.isDockedOnRight ?: isDockedOnRight
        val targetTranslationX = if (isRight) (24 * density) else -(24 * density)

        chat.animate()
            .alpha(0f)
            .translationX(targetTranslationX)
            .scaleX(0.6f)
            .scaleY(0.6f)
            .setDuration(180)
            .withEndAction {
                if (chat.isAttachedToWindow) {
                    try {
                        windowManager.removeView(chat)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to remove slmChatView", e)
                    }
                }
                slmChatView = null
                slmChatAdapter = null
                checkAndHideBubbleIfKeyboardClosed(animate = true)
            }
            .start()
    }

    private fun updateSlmChatPosition() {
        val chat = slmChatView ?: return
        val lp = slmChatLayoutParams ?: return
        val density = resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()
        val gap = (10 * density).toInt()
        val screenWidth = getScreenWidth()
        val safeBottom = getSafeBottom()
        val maxChatWidth = screenWidth - bubbleWidthPx - gap - (12 * density).toInt()
        val chatWidth = (320 * density).toInt().coerceAtMost(maxChatWidth)
        val defaultChatHeight = (440 * density).toInt().coerceAtMost(safeBottom - (16 * density).toInt())

        val anchor = currentAnchorBubble
        val isRight = anchor?.isDockedOnRight ?: isDockedOnRight
        val anchorY = anchor?.windowLayoutParams?.y ?: windowLayoutParams.y

        if (isRight) {
            lp.gravity = Gravity.TOP or Gravity.RIGHT
            lp.x = bubbleWidthPx + gap
        } else {
            lp.gravity = Gravity.TOP or Gravity.LEFT
            lp.x = bubbleWidthPx + gap
        }
        lp.width = chatWidth
        lp.height = defaultChatHeight
        val maxAllowedY = safeBottom - defaultChatHeight - (8 * density).toInt()
        lp.y = anchorY.coerceIn(0, maxOf(0, maxAllowedY))

        if (chat.isAttachedToWindow) {
            try {
                windowManager.updateViewLayout(chat, lp)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update slmChatView layout", e)
            }
        }
    }

    private fun startOrStopVoiceForSlm(btnMic: ImageButton, etPrompt: EditText) {
        if (isSlmVoiceRecording) {
            stopRecordingForSlm(btnMic)
            return
        }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.toast_mic_permission_voice, Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            return
        }

        val isDual = isDualEngineActive()
        val currentEngine = ModelConfig.selectedEngine(this)

        // Validate model readiness per user's active engine preference
        if (isDual) {
            val xReady = ModelConfig.isXAsrReady(this)
            val qReady = ModelConfig.isQwen3Ready(this)
            if (!xReady || !qReady) {
                val missing = if (!xReady && !qReady) "X-ASR 與 Qwen3-ASR" else if (!xReady) "X-ASR" else "Qwen3-ASR"
                Toast.makeText(this, getString(R.string.toast_asr_model_not_ready, missing), Toast.LENGTH_SHORT).show()
                return
            }
        } else if (currentEngine == ModelConfig.ENGINE_X_ASR) {
            if (!ModelConfig.isXAsrReady(this)) {
                Toast.makeText(this, getString(R.string.toast_asr_model_not_ready, "X-ASR"), Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            if (!ModelConfig.isQwen3Ready(this)) {
                Toast.makeText(this, getString(R.string.toast_asr_model_not_ready, "Qwen3-ASR"), Toast.LENGTH_SHORT).show()
                return
            }
        }

        slmVoiceJob = scope.launch {
            // 1. Ensure required engine models are loaded into memory
            val needXAsr = isDual || currentEngine == ModelConfig.ENGINE_X_ASR
            val needQwen3 = isDual || currentEngine == ModelConfig.ENGINE_QWEN3

            if ((needXAsr && !xAsr.isLoaded()) || (needQwen3 && (!qwen3Asr.isLoaded() || qwen3NeedsReload()))) {
                etPrompt.hint = "載入語音引擎中…"
                withContext(Dispatchers.IO) {
                    if (needXAsr && !xAsr.isLoaded()) xAsr.load()
                    if (needQwen3 && (!qwen3Asr.isLoaded() || qwen3NeedsReload())) qwen3Asr.load(buildHotwords())
                }
            }

            // 2. Set recording UI state
            isSlmVoiceRecording = true
            btnMic.setBackgroundResource(R.drawable.bg_slm_mic_recording)
            btnMic.setColorFilter(android.graphics.Color.WHITE)
            HapticUtil.click(this@FloatingBubbleService)

            val vadSilence = ModelConfig.vadSilenceSeconds(this@FloatingBubbleService)

            try {
                if (isDual) {
                    // Pipeline: Dual Engine (X-ASR streaming preview + Qwen3-ASR high-accuracy batch finalize)
                    etPrompt.hint = "聆聽中 (雙引擎)…"
                    val stream = xAsr.createStream() ?: return@launch
                    val audioBuffer = mutableListOf<FloatArray>()
                    var accumulated = ""

                    withContext(Dispatchers.IO) {
                        recorder.recordStreaming(
                            silenceSeconds = vadSilence,
                            onChunk = { chunk ->
                                if (!isSlmVoiceRecording) return@recordStreaming
                                audioBuffer.add(chunk.copyOf())
                                xAsr.acceptWaveform(stream, chunk)
                                while (xAsr.isReady(stream)) {
                                    xAsr.decode(stream)
                                }
                                val partial = xAsr.getResult(stream)
                                if (partial.isNotBlank()) {
                                    val fullPreview = postProcess(accumulated + partial)
                                    mainHandler.post {
                                        etPrompt.setText(fullPreview)
                                        etPrompt.setSelection(fullPreview.length)
                                    }
                                }
                                if (xAsr.isEndpoint(stream)) {
                                    accumulated += partial
                                    xAsr.reset(stream)
                                }
                            }
                        )
                    }

                    runCatching { xAsr.inputFinished(stream) }
                    runCatching {
                        while (xAsr.isReady(stream)) {
                            xAsr.decode(stream)
                        }
                    }
                    val finalStreamPartial = xAsr.getResult(stream).trim()
                    runCatching { stream.release() }

                    val totalSamples = audioBuffer.sumOf { it.size }
                    if (totalSamples > 0 && qwen3Asr.isLoaded()) {
                        withContext(Dispatchers.Main) { etPrompt.hint = "高精轉譯中…" }
                        val combinedAudio = FloatArray(totalSamples)
                        var offset = 0
                        for (c in audioBuffer) {
                            System.arraycopy(c, 0, combinedAudio, offset, c.size)
                            offset += c.size
                        }
                        val qwen3Result = runCatching { qwen3Asr.transcribe(combinedAudio) }.getOrNull()
                        val finalText = if (!qwen3Result.isNullOrBlank()) {
                            postProcess(qwen3Result)
                        } else {
                            postProcess(accumulated + finalStreamPartial)
                        }
                        if (finalText.isNotBlank()) {
                            withContext(Dispatchers.Main) {
                                etPrompt.setText(finalText)
                                etPrompt.setSelection(finalText.length)
                            }
                        }
                    }
                } else if (currentEngine == ModelConfig.ENGINE_X_ASR) {
                    // Pipeline: X-ASR Streaming Transducer
                    etPrompt.hint = "聆聽中 (X-ASR 串流)…"
                    val stream = xAsr.createStream() ?: return@launch
                    var accumulated = ""

                    withContext(Dispatchers.IO) {
                        recorder.recordStreaming(
                            silenceSeconds = vadSilence,
                            onChunk = { chunk ->
                                if (!isSlmVoiceRecording) return@recordStreaming
                                xAsr.acceptWaveform(stream, chunk)
                                while (xAsr.isReady(stream)) {
                                    xAsr.decode(stream)
                                }
                                val partial = xAsr.getResult(stream)
                                if (partial.isNotBlank()) {
                                    val fullPreview = postProcess(accumulated + partial)
                                    mainHandler.post {
                                        etPrompt.setText(fullPreview)
                                        etPrompt.setSelection(fullPreview.length)
                                    }
                                }
                                if (xAsr.isEndpoint(stream)) {
                                    accumulated += partial
                                    xAsr.reset(stream)
                                }
                            }
                        )
                    }

                    runCatching { xAsr.inputFinished(stream) }
                    runCatching {
                        while (xAsr.isReady(stream)) {
                            xAsr.decode(stream)
                        }
                    }
                    val finalStreamPartial = xAsr.getResult(stream).trim()
                    runCatching { stream.release() }

                    val finalText = postProcess(accumulated + finalStreamPartial)
                    if (finalText.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            etPrompt.setText(finalText)
                            etPrompt.setSelection(finalText.length)
                        }
                    }
                } else {
                    // Pipeline: Qwen3-ASR Offline Speech Recognition
                    etPrompt.hint = "聆聽中 (Qwen3-ASR 離線)…"
                    val audioBuffer = mutableListOf<FloatArray>()

                    withContext(Dispatchers.IO) {
                        recorder.recordStreaming(
                            silenceSeconds = vadSilence,
                            onChunk = { chunk ->
                                if (!isSlmVoiceRecording) return@recordStreaming
                                audioBuffer.add(chunk.copyOf())
                            }
                        )
                    }

                    val totalSamples = audioBuffer.sumOf { it.size }
                    if (totalSamples > 0 && qwen3Asr.isLoaded()) {
                        withContext(Dispatchers.Main) { etPrompt.hint = "離線高精辨識中…" }
                        val combinedAudio = FloatArray(totalSamples)
                        var offset = 0
                        for (c in audioBuffer) {
                            System.arraycopy(c, 0, combinedAudio, offset, c.size)
                            offset += c.size
                        }
                        val qwen3Result = runCatching { qwen3Asr.transcribe(combinedAudio) }.getOrNull()
                        if (!qwen3Result.isNullOrBlank()) {
                            val finalText = postProcess(qwen3Result)
                            withContext(Dispatchers.Main) {
                                etPrompt.setText(finalText)
                                etPrompt.setSelection(finalText.length)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "SLM voice pipeline error", e)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    stopRecordingForSlm(btnMic)
                    etPrompt.hint = "語音或輸入指令..."
                }
            }
        }
    }

    private fun stopRecordingForSlm(btnMic: ImageButton) {
        if (!isSlmVoiceRecording) return
        isSlmVoiceRecording = false
        recorder.stopEarly()
        slmVoiceJob?.cancel()
        slmVoiceJob = null
        btnMic.setBackgroundResource(R.drawable.bg_scanner_btn_circle)
        btnMic.setColorFilter(android.graphics.Color.parseColor("#38BDF8"))
        HapticUtil.click(this)
    }

    private fun submitSlmPrompt(
        prompt: String,
        etPrompt: EditText,
        rvChat: RecyclerView,
        emptyView: View
    ) {
        emptyView.visibility = View.GONE
        etPrompt.text.clear()

        // 1. Add user message
        val userMsg = SlmChatMessage(
            role = SlmChatMessage.Role.USER,
            content = prompt
        )
        slmMessages.add(userMsg)
        slmChatAdapter?.notifyItemInserted(slmMessages.size - 1)
        rvChat.scrollToPosition(slmMessages.size - 1)

        // 2. Add assistant placeholder
        val selectedModel = SlmModelConfig.getSelectedModel(this)
        val meta = SlmModelConfig.getModelMetadata(selectedModel)
        val accelTag = SlmModelConfig.getHardwareAccelTag(this)
        val modeLabel = SlmModelConfig.getOutputModeLabel(SlmModelConfig.getOutputMode(this))
        val assistantMsg = SlmChatMessage(
            role = SlmChatMessage.Role.ASSISTANT,
            content = "",
            thinkingContent = null,
            isStreaming = true,
            modelTag = "${meta.displayName} · $modeLabel · $accelTag"
        )
        slmMessages.add(assistantMsg)
        val assistantPos = slmMessages.size - 1
        slmChatAdapter?.notifyItemInserted(assistantPos)
        rvChat.scrollToPosition(assistantPos)

        // 3. Trigger generation
        slmInferenceEngine.generate(
            userPrompt = prompt,
            history = slmMessages.dropLast(2),
            callback = object : SlmInferenceEngine.StreamCallback {
                override fun onThinkingUpdate(thinking: String) {
                    assistantMsg.thinkingContent = thinking
                    slmChatAdapter?.notifyItemChanged(assistantPos)
                    rvChat.scrollToPosition(assistantPos)
                }

                override fun onAnswerUpdate(answer: String) {
                    assistantMsg.content = answer
                    slmChatAdapter?.notifyItemChanged(assistantPos)
                    rvChat.scrollToPosition(assistantPos)
                }

                override fun onComplete(fullMessage: SlmChatMessage) {
                    assistantMsg.isStreaming = false
                    assistantMsg.content = fullMessage.content
                    assistantMsg.thinkingContent = fullMessage.thinkingContent
                    slmChatAdapter?.notifyItemChanged(assistantPos)
                    rvChat.scrollToPosition(assistantPos)
                }

                override fun onError(error: Throwable) {
                    assistantMsg.isStreaming = false
                    assistantMsg.content = "推論失敗：${error.localizedMessage ?: "未知錯誤"}"
                    slmChatAdapter?.notifyItemChanged(assistantPos)
                }
            }
        )
    }

    private fun copySlmAnswer(message: SlmChatMessage) {
        val clean = message.getCleanAnswer()
        if (clean.isBlank()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("SLM Answer", clean))
        Toast.makeText(this, R.string.toast_slm_copied, Toast.LENGTH_SHORT).show()
    }

    private fun pasteSlmAnswer(message: SlmChatMessage) {
        val clean = message.getCleanAnswer()
        if (clean.isBlank()) return
        val injected = VoiceAccessibilityService.instance?.inputText(clean) ?: false
        if (injected) {
            showPreviewText(getString(R.string.toast_slm_pasted), autoHide = true)
            Toast.makeText(this, R.string.toast_slm_pasted, Toast.LENGTH_SHORT).show()
        } else {
            copyToClipboardFallback(clean)
            showPreviewText(getString(R.string.toast_slm_pasted_fallback), autoHide = true)
            Toast.makeText(this, R.string.toast_slm_pasted_fallback, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::bubbleView.isInitialized && bubbleView.isAttachedToWindow) {
            val screenWidth = getScreenWidth()
            val bubbleWidthPx = (60 * resources.displayMetrics.density).toInt()
            for (item in bubbleItems) {
                if (item.view.isAttachedToWindow) {
                    val finalX = if (item.isTucked) {
                        if (item.isDockedOnRight) getTuckRightX() else getTuckLeftX()
                    } else {
                        if (item.isDockedOnRight) (screenWidth - bubbleWidthPx) else 0
                    }
                    val minY = getMinY()
                    val maxY = getMaxY()
                    val finalY = item.windowLayoutParams.y.coerceIn(minY, maxY)
                    if (item.windowLayoutParams.x != finalX || item.windowLayoutParams.y != finalY) {
                        item.windowLayoutParams.x = finalX
                        item.windowLayoutParams.y = finalY
                        windowManager.updateViewLayout(item.view, item.windowLayoutParams)
                    }
                }
            }
            if (ModelConfig.isMultiBubbleEnabled(this)) {
                resolveAndApplyEdgeStacking()
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
        if (isSlmChatActive) {
            updateSlmChatPosition()
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
            safely("slmChat") { closeSlmChat(hideX = false) }
            safely("capsule") { if (::capsuleMenuController.isInitialized) capsuleMenuController.destroy() }
            safely("deleteZone") { if (::bubbleDeleteZoneController.isInitialized) bubbleDeleteZoneController.hide(animated = false) }
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

            for (item in bubbleItems) {
                if (item.id != 0 && item.view.isAttachedToWindow) {
                    removeOverlay("bubble_${item.id}", item.view)
                }
            }
            bubbleItems.clear()

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
