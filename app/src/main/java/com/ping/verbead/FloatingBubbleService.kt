package com.ping.verbead

import com.ping.verbead.util.isViewAdded
import android.animation.ValueAnimator
import com.ping.verbead.util.allowDisplayCutout
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
import android.os.SystemClock
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
import com.ping.verbead.bubble.BubbleState as State
import com.ping.verbead.bubble.BubbleChainFollower
import com.ping.verbead.bubble.BubbleStateMachine
import com.ping.verbead.bubble.PreviewKind
import com.ping.verbead.util.ToastUtil
import com.ping.verbead.bubble.OverlayCoordinator
import com.ping.verbead.bubble.VoiceInputController
import com.ping.verbead.bubble.CapsuleMenuController
import com.ping.verbead.bubble.PhrasesDrawerController
import com.ping.verbead.bubble.ScannerOverlayController
import com.ping.verbead.bubble.OcrOverlayController
import com.ping.verbead.bubble.BubblePulseController
import com.ping.verbead.bubble.BubbleXButtonController
import com.ping.verbead.bubble.BubblePreviewController
import com.ping.verbead.bubble.BubblePhysics
import com.ping.verbead.bubble.BubblePosition
import com.ping.verbead.bubble.BubbleDeleteZoneController
import com.ping.verbead.bubble.BubbleShakeDetector
import com.ping.verbead.bubble.FloatingBubbleItem
import com.ping.verbead.engine.ModelConfig

import com.ping.verbead.engine.ModelDownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.view.ScaleGestureDetector
import android.widget.ImageButton
import android.widget.SeekBar
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

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.ping.verbead.util.AppLocale.wrap(newBase))
    }


    companion object {
        private const val TAG = "FloatingBubbleService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "floating_bubble_channel"
        private const val ACTION_STOP = "com.ping.verbead.ACTION_STOP_BUBBLE"
        const val MODE_VOICE = 0
        const val MODE_OCR = 1
        const val MODE_BARCODE = 2
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
    private val voice by lazy {
        VoiceInputController(this, scope, mainHandler, object : VoiceInputController.Host {
            override fun setState(state: State) = this@FloatingBubbleService.setState(state)
            override fun showXButton() = this@FloatingBubbleService.showXButton()
            override fun hideXButton() = this@FloatingBubbleService.hideXButton()
            override fun showStatus(msg: String) = showPreviewText(msg, PreviewKind.STATUS)
            override fun showLive(text: String) = showPreviewText(text, PreviewKind.LIVE)
            override fun hidePreview() = hidePreviewText()
            override fun hidePulse() = hidePulseView()
            override fun onRms(rms: Float) = onAudioRmsUpdate(rms)
            override fun onTranscribed(text: String) = onTranscriptionDone(text)
            override fun untuckAnchor() = untuckAnchorBubble()
        })
    }
    private val routingManager by lazy { AudioRoutingManager.getInstance(this) }

    private lateinit var windowManager: WindowManager
    private lateinit var trackingWindowManager: com.ping.verbead.util.TrackingWindowManager
    private lateinit var windowLayoutParams: WindowManager.LayoutParams
    private lateinit var bubbleView: View
    val bubbleItems = mutableListOf<FloatingBubbleItem>()
    private var activeAnchorBubble: FloatingBubbleItem? = null
    private val currentAnchorBubble: FloatingBubbleItem?
        get() = (activeAnchorBubble?.takeIf { bubbleItems.contains(it) }) ?: bubbleItems.firstOrNull()

    private lateinit var btnBubbleMic: FrameLayout
    private lateinit var ivBubbleIcon: ImageView
    private lateinit var progressBubble: ProgressBar

    // Auxiliary overlay controllers
    private lateinit var bubblePreviewController: BubblePreviewController
    private lateinit var bubblePulseController: BubblePulseController
    private lateinit var bubbleXButtonController: BubbleXButtonController
    private var isRestoringSnapshot = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 預先載入好的懸浮球版面。搖晃複製發生在拖曳手勢當中，若當場才載入版面，
     * 會和 addView、刪除區視窗、Toast 擠在同一個畫面幀內造成卡頓，所以在主執行緒閒置時先備好一份。
     */
    /**
     * 手指剛按下、球還沒開始動時，先把搖晃複製會用到的東西備好：
     * 新球的版面、刪除區視窗。這時做不會影響正在進行的手勢。
     */
    private fun prewarmForShake() {
        if (ModelConfig.getMaxMultiBubbleCountForEnabledModes(this) <= 1) return
        scheduleSpareBubbleView()
        if (!bubbleDeleteZoneController.isShowing) {
            val density = resources.displayMetrics.density
            bubbleDeleteZoneController.prewarm(getScreenWidth(), getMinY() + (24 * density).toInt())
        }
    }

    /** 搖晃複製後母球回彈動畫進行中，這段時間拖曳不可把縮放強制重設。 */
    private var shakeRecoilUntilUptime = 0L

    /**
     * 搖晃複製出的球會串在被拖曳的球後面，直到手勢結束（見 [BubbleChainFollower]）。
     */
    private val shakeChain by lazy {
        BubbleChainFollower(
            windowManager = windowManager,
            density = resources.displayMetrics.density,
            bounds = {
                BubbleChainFollower.Bounds(
                    maxX = (getScreenWidth() - (60 * resources.displayMetrics.density).toInt()).toFloat(),
                    minY = getMinY().toFloat(),
                    maxY = getMaxY().toFloat()
                )
            }
        )
    }
    private lateinit var overlays: OverlayCoordinator

    private fun repositionOverlays() {
        if (::overlays.isInitialized) overlays.reposition()
    }

    private var dragOriginX = 0f
    private var dragOriginY = 0f
    private var dragRawX = 0f
    private var dragRawY = 0f

    private var spareBubbleView: View? = null
    private var spareBubbleScheduled = false

    private fun scheduleSpareBubbleView() {
        if (spareBubbleView != null || spareBubbleScheduled) return
        if (bubbleItems.size >= ModelConfig.getMaxMultiBubbleCountForEnabledModes(this)) return
        spareBubbleScheduled = true
        Looper.myQueue().addIdleHandler {
            spareBubbleScheduled = false
            if (spareBubbleView == null) {
                spareBubbleView = runCatching {
                    LayoutInflater.from(themedCtx).inflate(R.layout.layout_floating_bubble, null)
                }.getOrNull()
            }
            false
        }
    }

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
    private lateinit var scannerOverlayController: ScannerOverlayController
    private val isScannerModeActive: Boolean
        get() = ::scannerOverlayController.isInitialized && scannerOverlayController.isActive
    private val isScannerEnlarged: Boolean
        get() = ::scannerOverlayController.isInitialized && scannerOverlayController.isEnlarged
        // OCR Window & Snapshot
    private lateinit var ocrOverlayController: OcrOverlayController
    private val isOcrModeActive: Boolean
        get() = ::ocrOverlayController.isInitialized && ocrOverlayController.isModeActive
    private val isOcrSnapshotActive: Boolean
        get() = ::ocrOverlayController.isInitialized && ocrOverlayController.isSnapshotActive
    private val isOcrEnlarged: Boolean
        get() = ::ocrOverlayController.isInitialized && ocrOverlayController.isEnlarged

        // Quick Phrases Drawer
    private lateinit var phrasesDrawerController: PhrasesDrawerController
    private val isPhrasesDrawerActive: Boolean
        get() = ::phrasesDrawerController.isInitialized && phrasesDrawerController.isActive

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

    private var autoHidePreviewJob: Job? = null
    private var xButtonAutoHideJob: Job? = null
    private var snapAnimator: ValueAnimator? = null
    private var dynamicKeyboardTop: Int = 0
    private var isDockedOnRight = true
    private var isBubbleMoving = false

    // 邊緣收納隱藏狀態 (~30% 可視，~70% 位於畫面外)
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
        val enabledModes = ModelConfig.getEnabledBubbleModes(this)
        currentMode = getSharedPreferences(PREF_BUBBLE_MODE, Context.MODE_PRIVATE).getInt(KEY_MODE, enabledModes.firstOrNull() ?: MODE_VOICE)
        coerceCurrentModeToEnabled(enabledModes, save = false)
        trackingWindowManager = com.ping.verbead.util.TrackingWindowManager(getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        windowManager = trackingWindowManager
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
        phrasesDrawerController = PhrasesDrawerController(
            context = themedCtx,
            windowManager = windowManager,
            listener = object : PhrasesDrawerController.Listener {
                override fun onPhraseSelected(phrase: String) {
                    insertQuickPhrase(phrase)
                }
                override fun onDrawerClosed(hideX: Boolean) {
                    // 常用語球體在關閉抽屜後保留，不再自動消失
                    checkAndHideBubbleIfKeyboardClosed(animate = true)
                }
                override fun onShowXButton() {
                    showXButton()
                }
                override fun onHideXButton() {
                    hideXButton()
                }
            }
        )
        scannerOverlayController = ScannerOverlayController(
            context = themedCtx,
            lifecycleOwner = this,
            windowManager = windowManager,
            listener = object : ScannerOverlayController.Listener {
                override fun onBarcodeDetected(code: String) {
                    handleBarcodeDetected(code)
                }
                override fun onScannerClosed(hideX: Boolean) {
                    checkAndHideBubbleIfKeyboardClosed(animate = true)
                }
                override fun onShowXButton() {
                    showXButton()
                }
                override fun onHideXButton() {
                    if (state != State.PASTED) hideXButton()
                }
                override fun onRequestCameraPermission() {
                    ToastUtil.show(this@FloatingBubbleService, R.string.toast_camera_permission_scan)
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                }
            }
        )

        bubblePulseController = BubblePulseController(
            context = this,
            windowManager = windowManager,
            themedCtx = themedCtx
        )
        bubbleXButtonController = BubbleXButtonController(
            context = this,
            windowManager = windowManager,
            themedCtx = themedCtx,
            onXButtonClick = ::onXButtonClick
        )
        bubblePreviewController = BubblePreviewController(
            context = this,
            windowManager = windowManager,
            themedCtx = themedCtx,
            scope = scope
        )

        ocrOverlayController = OcrOverlayController(
            context = themedCtx,
            lifecycleOwner = this,
            windowManager = windowManager,
            scope = scope,
            fallbackPreviewProvider = { scannerOverlayController.previewView },
            listener = object : OcrOverlayController.Listener {
                override fun onOcrTextConfirmed(text: String) {
                    handleOcrTextConfirmed(text)
                }
                override fun onOcrClosed(hideX: Boolean) {
                    checkAndHideBubbleIfKeyboardClosed(animate = true)
                }
                override fun onSnapshotDismissed(hideX: Boolean) {
                    checkAndHideBubbleIfKeyboardClosed(animate = true)
                }
                override fun onShowXButton() {
                    showXButton()
                }
                override fun onHideXButton() {
                    if (state != State.PASTED) hideXButton()
                }
                override fun onRequestScreenCapture() {
                    captureScreenForOcr()
                }
                override fun onRequestCameraPermission() {
                    ToastUtil.show(this@FloatingBubbleService, R.string.toast_camera_permission_ocr)
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                }
            }
        )

        overlays = OverlayCoordinator(
            scanner = scannerOverlayController,
            ocr = ocrOverlayController,
            phrases = phrasesDrawerController,
            host = object : OverlayCoordinator.Host {
                override val anchorY: Int get() = currentAnchorBubble?.windowLayoutParams?.y ?: windowLayoutParams.y
                override val isDockedOnRight: Boolean get() = currentAnchorBubble?.isDockedOnRight ?: this@FloatingBubbleService.isDockedOnRight
                override val screenWidth: Int get() = getScreenWidth()
                override val safeBottom: Int get() = getSafeBottom()
                override fun hidePreview() = hidePreviewText()
                override fun untuckAnchor() = untuckAnchorBubble()
                override fun onScannerStopped() {
                    if (state != State.PASTED) setState(State.IDLE)
                }
            }
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
                            item.btnBubble.scaleX = 1f
                            item.btnBubble.scaleY = 1f - squish
                        } else {
                            item.btnBubble.scaleX = 1f - squish
                            item.btnBubble.scaleY = 1f
                        }
                    }
                }
            },
            onShakeTriggered = {
                currentlyDraggedBubble?.let { item ->
                    if (!handleShakeToDuplicate(item)) {
                        com.ping.verbead.ui.GlassMotion.springBack(item.btnBubble)
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

        val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

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
            allowDisplayCutout()
            gravity = Gravity.TOP or Gravity.LEFT
            x = rightX
            y = initialY
        }

                bubblePreviewController.setup(bubbleWidthPx, initialY, density)

        isDockedOnRight = true

        val enabledModes = ModelConfig.getEnabledBubbleModes(this)
        val isMulti = ModelConfig.isMultiBubbleEnabled(this) && enabledModes.size > 1
        val rawInitialMode0 = if (isMulti) {
            getBubbleMode(0, enabledModes.firstOrNull() ?: MODE_VOICE)
        } else {
            currentMode
        }
        val initialMode0 = if (rawInitialMode0 in enabledModes) rawInitialMode0 else (enabledModes.firstOrNull() ?: MODE_VOICE)
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
                                            repositionOverlays()
                                            updatePreviewPosition()
                                        }
                                    }
                                }
                                start()
                            }
                        } else {
                            repositionOverlays()
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
            // 橫屏且導覽列在側邊（三鍵導覽）時底部本來就是 0；不能再退回直向的導覽列高度資源值
            return insets.bottom
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
        if (!visible && (state == State.RECORDING || state == State.TRANSCRIBING || state == State.PASTED || isScannerModeActive || isOcrModeActive || isOcrSnapshotActive || isPhrasesDrawerActive || isCapsuleMenuShowing)) {
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
                        if (state != State.RECORDING && state != State.TRANSCRIBING && !isScannerModeActive && !isOcrModeActive && !isOcrSnapshotActive && !isPhrasesDrawerActive) {
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
        if (!isKeyboardOpen && !isScannerModeActive && !isOcrModeActive && !isOcrSnapshotActive && !isPhrasesDrawerActive && state != State.PASTED && state != State.RECORDING && state != State.TRANSCRIBING && !isCapsuleMenuShowing) {
            setBubbleVisible(false, animate = animate)
        }
    }

    /** 目前操作中的球若縮在螢幕邊緣就展開（要在它旁邊開視窗或開始錄音前呼叫）。 */
    private fun untuckAnchorBubble(animate: Boolean = false) {
        currentAnchorBubble?.takeIf { it.isTucked }?.let { untuckBubble(it, animate) }
    }

    private fun untuckBubble(item: FloatingBubbleItem, animate: Boolean = true, onComplete: (() -> Unit)? = null) {
        if (!item.isTucked || !item.view.isAttachedToWindow) {
            item.isTucked = false
            onComplete?.invoke()
            return
        }
        item.isTucked = false
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
                repositionOverlays()
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
                            repositionOverlays()
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
                            repositionOverlays()
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
                    // press in: the bubble gives a little under the finger
                    item.btnBubble.animate().cancel()
                    item.btnBubble.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90L).start()
                    shakeChain.end()
                    shakeDetector?.reset()
                    Looper.myQueue().addIdleHandler {
                        if (currentlyDraggedBubble != null) prewarmForShake()
                        false
                    }
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
                    dragOriginX = event.rawX
                    dragOriginY = event.rawY
                    dragRawX = event.rawX
                    dragRawY = event.rawY
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
                        if (bubbleItems.size > 1) {
                            val deleteZoneTargetY = getMinY() + (24 * density).toInt()
                            bubbleDeleteZoneController.show(getScreenWidth(), deleteZoneTargetY)
                        }
                    }
                    if (isDragging) {
                        dragRawX = event.rawX
                        dragRawY = event.rawY
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
                            item.btnBubble.scaleX = if (mag.isLocked) 0.82f else 0.90f
                            item.btnBubble.scaleY = if (mag.isLocked) 0.82f else 0.90f
                        } else {
                            item.windowLayoutParams.x = newX
                            item.windowLayoutParams.y = newY
                            if (shakeDetector?.isShaking == false &&
                                SystemClock.uptimeMillis() >= shakeRecoilUntilUptime
                            ) {
                                item.btnBubble.scaleX = 1f
                                item.btnBubble.scaleY = 1f
                            }
                        }

                        if (mag?.isAttracted != true && bubbleItems.size > 1) {
                            val tuckLeftX = getTuckLeftX().toFloat()
                            val tuckRightX = getTuckRightX().toFloat()
                            val minDistancePx = (60 * density) + (8 * density)
                            // 被擠到牆邊的球可以稍微被擠出畫面這麼多，之後會滑開並回到貼邊位置
                            val squeezeOvershootPx = 20f * density

                            val boundsMap = mutableMapOf<Int, Pair<Float, Float>>()
                            // bubbles linked by shaking neither push nor get pushed until the drag ends and they snap to the edge
                            val collidable = bubbleItems.filter { it !in shakeChain }
                            val positions = collidable.map {
                                val posX = if (it.isTucked) {
                                    if (it.isDockedOnRight) tuckRightX else tuckLeftX
                                } else {
                                    it.windowLayoutParams.x.toFloat()
                                }
                                val (bMinX, bMaxX) = if (it.isTucked) {
                                    if (it.isDockedOnRight) (tuckRightX to tuckRightX) else (tuckLeftX to tuckLeftX)
                                } else {
                                    ((normalLeftX - squeezeOvershootPx) to (normalRightX + squeezeOvershootPx))
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
                            // 已經滑開、回到貼邊位置也不會和被拖的球重疊的球，滑回畫面邊緣；
                            // 還被擠著的就維持稍微超出畫面的狀態，等它沿著邊滑開
                            val dragCx = newX + bubbleWidthPx / 2f
                            val dragCy = newY + bubbleHeightPx / 2f
                            for (other in bubbleItems) {
                                if (other.id == item.id || other in shakeChain || other.isTucked || !other.view.isAttachedToWindow) continue
                                val ox = other.windowLayoutParams.x
                                val edgeX = when {
                                    ox > normalRightX -> normalRightX
                                    ox < normalLeftX -> normalLeftX
                                    else -> continue
                                }
                                val cxAtEdge = edgeX + bubbleWidthPx / 2f
                                val cy = other.windowLayoutParams.y + bubbleHeightPx / 2f
                                if (hypot(cxAtEdge - dragCx, cy - dragCy) >= minDistancePx &&
                                    other.snapAnimator?.isRunning != true
                                ) {
                                    animateBackToEdge(other, edgeX.toInt())
                                }
                            }

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
                                                px.coerceIn(
                                                    (normalLeftX - squeezeOvershootPx).toInt(),
                                                    (normalRightX + squeezeOvershootPx).toInt()
                                                )
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
                            repositionOverlays()
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
                    bubbleDeleteZoneController.discardPrewarm()
                    if (isLongPressTriggered || isCapsuleMenuShowing) {
                        capsuleMenuController.finishDrag(event.rawX, event.rawY)
                        isLongPressTriggered = false
                        item.isBubbleMoving = false
                        item.view.setLayerType(View.LAYER_TYPE_NONE, null)
                    } else {
                        currentlyDraggedBubble = null
                        shakeChain.end()
                        shakeDetector?.reset()

                        if (isDragging && bubbleDeleteZoneController.isShowing && bubbleDeleteZoneController.isAttracted) {
                            item.isBubbleMoving = false
                            item.view.setLayerType(View.LAYER_TYPE_NONE, null)
                            handleDeleteBubble(item)
                        } else {
                            com.ping.verbead.ui.GlassMotion.springBack(item.btnBubble)
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
                                    onBubbleClickForMode(item)
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
                    bubbleDeleteZoneController.discardPrewarm()
                    com.ping.verbead.ui.GlassMotion.springBack(item.btnBubble)
                    currentlyDraggedBubble = null
                    shakeChain.end()
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

    /** 單球模式記住的模式若已不在啟用清單裡，改成清單的第一個（[save] 時一併寫回設定）。 */
    private fun coerceCurrentModeToEnabled(enabledModes: List<Int>, save: Boolean = true) {
        if (currentMode in enabledModes) return
        currentMode = enabledModes.firstOrNull() ?: MODE_VOICE
        if (save) {
            getSharedPreferences(PREF_BUBBLE_MODE, Context.MODE_PRIVATE).edit().putInt(KEY_MODE, currentMode).apply()
        }
    }

    fun updateBubbleModesConfig() {
        if (!::bubbleView.isInitialized) return
        if (isCapsuleMenuShowing) {
            capsuleMenuController.dismiss()
        }
        val enabledModes = ModelConfig.getEnabledBubbleModes(this)
        val fallbackMode = enabledModes.firstOrNull() ?: MODE_VOICE

        if (MODE_OCR !in enabledModes && isOcrModeActive) {
            overlays.stopOcr()
        }
        if (MODE_BARCODE !in enabledModes && isScannerModeActive) {
            overlays.stopScanner()
        }
        if (MODE_PHRASES !in enabledModes && isPhrasesDrawerActive) {
            overlays.closePhrases()
        }
        coerceCurrentModeToEnabled(enabledModes)

        val usedModes = mutableSetOf<Int>()
        for (item in bubbleItems) {
            if (item.mode in enabledModes) {
                usedModes.add(item.mode)
            }
        }
        for (item in bubbleItems) {
            if (item.mode !in enabledModes) {
                val replacement = enabledModes.firstOrNull { it !in usedModes } ?: fallbackMode
                item.mode = replacement
                usedModes.add(replacement)
                setBubbleMode(item.id, replacement)
                val (iconRes, desc) = getIconAndDescForMode(replacement)
                item.ivIcon.setImageResource(iconRes)
                item.ivIcon.contentDescription = desc
            }
        }

        updateMultiBubbleMode()
    }

    fun updateMultiBubbleMode() {
        if (!::bubbleView.isInitialized) return
        val enabledModes = ModelConfig.getEnabledBubbleModes(this)
        val fallbackMode = enabledModes.firstOrNull() ?: MODE_VOICE
        val enabled = ModelConfig.isMultiBubbleEnabled(this) && enabledModes.size > 1
        val targetCount = if (enabled) {
            ModelConfig.getMultiBubbleCount(this).coerceIn(1, enabledModes.size)
        } else {
            1
        }

        if (enabled && targetCount > 1) {
            val bubble0 = bubbleItems.firstOrNull()
            val usedModes = mutableSetOf<Int>()
            if (bubble0 != null) {
                val saved0 = getBubbleMode(0, enabledModes.getOrElse(0) { fallbackMode })
                val valid0 = if (saved0 in enabledModes) saved0 else fallbackMode
                bubble0.mode = valid0
                usedModes.add(valid0)
                setBubbleMode(0, valid0)
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
                } else if (item.id != 0) {
                    if (item.mode !in enabledModes) {
                        val replacement = enabledModes.firstOrNull { m -> m !in usedModes } ?: fallbackMode
                        item.mode = replacement
                        setBubbleMode(item.id, replacement)
                    }
                    usedModes.add(item.mode)
                    val (iconRes, desc) = getIconAndDescForMode(item.mode)
                    item.ivIcon.setImageResource(iconRes)
                    item.ivIcon.contentDescription = desc
                }
            }

            val density = resources.displayMetrics.density
            val bubbleWidthPx = (60 * density).toInt()
            val bubbleHeightPx = (60 * density).toInt()
            val screenWidth = getScreenWidth()
            val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

            for (id in bubbleItems.size until targetCount) {
                val defaultForId = enabledModes.getOrElse(id) {
                    enabledModes.firstOrNull { m -> m !in usedModes } ?: fallbackMode
                }
                val savedMode = getBubbleMode(id, defaultForId)
                val mode = if (savedMode in enabledModes && savedMode !in usedModes) {
                    savedMode
                } else {
                    enabledModes.firstOrNull { m -> m !in usedModes } ?: (if (savedMode in enabledModes) savedMode else fallbackMode)
                }
                usedModes.add(mode)
                setBubbleMode(id, mode)
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
                    allowDisplayCutout()
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
            coerceCurrentModeToEnabled(enabledModes)
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

    /** @return 是否真的複製出新球（達到上限時回傳 false）。 */
    private fun handleShakeToDuplicate(parent: FloatingBubbleItem): Boolean {
        val tStart = SystemClock.elapsedRealtime()
        val enabledModes = ModelConfig.getEnabledBubbleModes(this)
        val maxAllowed = ModelConfig.getMaxMultiBubbleCountForEnabledModes(this)
        if (bubbleItems.size >= maxAllowed) {
            HapticUtil.tick(this)
            if (maxAllowed <= 1) {
                ToastUtil.show(this, getString(R.string.bubble_toast_one_mode_only))
            } else {
                ToastUtil.show(this, getString(R.string.bubble_toast_limit_reached, maxAllowed))
            }
            return false
        }

        val newCount = bubbleItems.size + 1
        ModelConfig.setMultiBubbleEnabled(this, true)
        ModelConfig.setMultiBubbleCount(this, newCount)

        val usedModes = bubbleItems.map { it.mode }.toSet()
        val newMode = enabledModes.firstOrNull { it !in usedModes } ?: (enabledModes.firstOrNull() ?: MODE_VOICE)
        val newId = bubbleItems.size
        setBubbleMode(newId, newMode)

        val density = resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()
        val bubbleHeightPx = (60 * density).toInt()
        val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val usedSpare = spareBubbleView != null
        val v = spareBubbleView?.also { spareBubbleView = null }
            ?: LayoutInflater.from(themedCtx).inflate(R.layout.layout_floating_bubble, null)
        val tInflated = SystemClock.elapsedRealtime()
        val btn = v.findViewById<FrameLayout>(R.id.btn_bubble_mic)
        val iv = v.findViewById<ImageView>(R.id.iv_bubble_icon)
        val prog = v.findViewById<ProgressBar>(R.id.progress_bubble)
        val (iconRes, desc) = getIconAndDescForMode(newMode)
        iv.setImageResource(iconRes)
        iv.contentDescription = desc

        // the new bubble links in behind the last bubble of the chain (or behind the dragged bubble itself)
        val tail = shakeChain.attachedTail() ?: parent
        val initialX = tail.windowLayoutParams.x
        val initialY = tail.windowLayoutParams.y

        val lp = WindowManager.LayoutParams(
            bubbleWidthPx,
            bubbleHeightPx,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            allowDisplayCutout()
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

        v.scaleX = 0.55f
        v.scaleY = 0.55f
        v.alpha = 0.6f

        val tBeforeAdd = SystemClock.elapsedRealtime()
        windowManager.addView(v, lp)
        val tAdded = SystemClock.elapsedRealtime()

        // Haptic feedback pop
        HapticUtil.heavyClick(this)

        // 母球：搖晃時累積的擠壓直接順勢回彈到原大小，不另外再縮一次。
        // 縮放不超過 1（視窗大小等於球，超出會被裁切）；回彈期間拖曳不能強制重設縮放。
        shakeRecoilUntilUptime = SystemClock.uptimeMillis() + 260L
        parent.btnBubble.animate().cancel()
        parent.btnBubble.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()

        // New bubble blooms smoothly to full size (capped at 1.0f so edges are never clipped by the window)
        v.animate().cancel()
        v.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(240L)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()

        // 新球串接在被搖晃的球後面：方向與被拖曳的球前進的方向相反，距離固定，之後拖曳時像鏈條一樣跟著走。
        val safeMin = getMinY().toFloat()
        val safeMax = getMaxY().toFloat()
        val screenWidth = getScreenWidth()
        val maxX = (screenWidth - bubbleWidthPx).toFloat()
        val spacing = BubbleChainFollower.SPACING_DP * density
        val x0 = initialX.toFloat()
        val y0 = initialY.toFloat()

        // 被拖曳的球正往哪裡走：先看放手前的速度，搖晃時速度會來回抵銷，就改看從按下到現在的位移，再沒有就當作往上
        val (vx0, vy0) = computeScreenVelocity()
        var fx = vx0
        var fy = vy0
        if (hypot(fx, fy) < 400f) {
            fx = dragRawX - dragOriginX
            fy = dragRawY - dragOriginY
            if (hypot(fx, fy) < 24 * density) {
                fx = 0f
                fy = -1f
            }
        }
        val fl = hypot(fx, fy)
        val fdx = fx / fl
        val fdy = fy / fl

        fun candidate(dx: Float, dy: Float) = Pair(
            (x0 + dx * spacing).coerceIn(0f, maxX),
            (y0 + dy * spacing).coerceIn(safeMin, safeMax)
        )
        fun reach(t: Pair<Float, Float>) = hypot(t.first - x0, t.second - y0)

        // 後方被螢幕邊緣擋住時，依序改放側邊、前方
        var target = candidate(-fdx, -fdy)
        if (reach(target) < spacing * 0.6f) {
            target = listOf(candidate(-fdy, fdx), candidate(fdy, -fdx), candidate(fdx, fdy))
                .firstOrNull { reach(it) >= spacing * 0.6f } ?: target
        }
        val x1 = target.first
        val y1 = target.second

        newItem.isDockedOnRight = (x1 + bubbleWidthPx / 2f) >= screenWidth / 2f
        newItem.snapAnimator?.cancel()
        // 一開始藏在前一顆球正下方，只朝「後方」偏 1px 讓方向有定義，之後由跟隨迴圈帶著它慢慢滑出到固定間距
        val bx = x1 - x0
        val by = y1 - y0
        val bl = hypot(bx, by).coerceAtLeast(1f)
        lp.x = (x0 + bx / bl).toInt()
        lp.y = (y0 + by / bl).toInt()
        windowManager.updateViewLayout(v, lp)
        shakeChain.link(parent, newItem)

        // 刪除區視窗已在按下時預先建好，這裡只播進場動畫；沒備好時才會當場建立
        if (bubbleItems.size > 1 && !bubbleDeleteZoneController.isShowing) {
            val deleteZoneTargetY = getMinY() + (24 * density).toInt()
            bubbleDeleteZoneController.show(getScreenWidth(), deleteZoneTargetY)
        }
        // 設定頁的多球開關由設定值變更的監聽自行同步，不必在這裡通知

        Log.d(
            TAG,
            "shake duplicate: spare=$usedSpare inflate=${tInflated - tStart}ms " +
                "addView=${tAdded - tBeforeAdd}ms mainThread=${SystemClock.elapsedRealtime() - tStart}ms"
        )
        return true
    }

    private fun handleDeleteBubble(bubbleToDelete: FloatingBubbleItem) {
        if (bubbleItems.size <= 1) {
            if (bubbleDeleteZoneController.isShowing) {
                bubbleDeleteZoneController.hide(animated = true)
            }
            return
        }

        // 若被刪除的球正好是當前開啟視窗的錨點球，或者處於語音狀態，立即關閉該球所開啟的視窗/狀態
        val isDeletingActiveAnchor = activeAnchorBubble?.id == bubbleToDelete.id
        if (isDeletingActiveAnchor) {
            if (isScannerModeActive) {
                overlays.stopScanner()
            }
            if (isOcrModeActive || isOcrSnapshotActive) {
                dismissOcrSnapshot()
                overlays.stopOcr()
            }
            if (isPhrasesDrawerActive) {
                overlays.closePhrases()
            }
            if (state == State.RECORDING) {
                voice.abortRecording()
                hidePreviewText()
                setState(State.IDLE)
            } else if (state == State.TRANSCRIBING) {
                voice.abortTranscribing()
                hidePreviewText()
                setState(State.IDLE)
            }
            hideXButton()
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
                    setBubbleMode(0, survivor.mode)

                    bubbleToDelete.view.scaleX = 1f
                    bubbleToDelete.view.scaleY = 1f
                    bubbleToDelete.btnBubble.scaleX = 1f
                    bubbleToDelete.btnBubble.scaleY = 1f
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
            ImeSettingsActivity.instance?.syncMultiBubbleState()
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
            repositionOverlays()
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

    /**
     * 所有球（單球、多球、後來長出來的常用語球）共用的點擊入口：依這顆球自己的模式動作。
     * 單球模式下主球的模式存在 [currentMode]，先同步過來再判斷。
     */
    private fun onBubbleClickForMode(item: FloatingBubbleItem) {
        if (item.id == 0 && !ModelConfig.isMultiBubbleEnabled(this)) item.mode = currentMode
        activeAnchorBubble = item
        if (state == State.PASTED) {
            hideXButton()
            setState(State.IDLE)
        }
        when (item.mode) {
            MODE_VOICE -> {
                overlays.closeAll()
                when (state) {
                    State.RECORDING -> {
                        voice.stopEarly()
                        hidePreviewText()
                        setState(State.TRANSCRIBING)
                    }
                    State.IDLE, State.PASTED -> {
                        voice.start()
                    }
                    State.LOADING, State.TRANSCRIBING -> {
                        // In progress, ignore
                    }
                    State.SCANNING -> {
                        overlays.stopScanner()
                        voice.start()
                    }
                }
            }
            MODE_OCR -> {
                if (state == State.RECORDING) {
                    // the dictation still finishes and is filled in, so the state must say TRANSCRIBING (not IDLE)
                    voice.stopEarly()
                    hidePreviewText()
                    setState(State.TRANSCRIBING)
                }
                overlays.toggleOcr()
            }
            MODE_BARCODE -> {
                if (state == State.RECORDING) {
                    // the dictation still finishes and is filled in, so the state must say TRANSCRIBING (not IDLE)
                    voice.stopEarly()
                    hidePreviewText()
                    setState(State.TRANSCRIBING)
                }
                overlays.toggleScanner()
            }
            MODE_PHRASES -> {
                if (state == State.RECORDING) {
                    // the dictation still finishes and is filled in, so the state must say TRANSCRIBING (not IDLE)
                    voice.stopEarly()
                    hidePreviewText()
                    setState(State.TRANSCRIBING)
                }
                overlays.togglePhrases()
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

    /** 被擠出畫面一點的球，擠壓解除後滑回貼邊位置（只動 X，Y 由碰撞的沿邊滑開決定）。 */
    private fun animateBackToEdge(other: FloatingBubbleItem, targetX: Int) {
        val startX = other.windowLayoutParams.x
        if (startX == targetX || !other.view.isAttachedToWindow) return
        other.snapAnimator?.cancel()
        other.snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200L
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { va ->
                if (other.view.isAttachedToWindow) {
                    val x = (startX + (targetX - startX) * va.animatedFraction).toInt()
                    other.windowLayoutParams.x = x
                    if (other.id == 0) windowLayoutParams.x = x
                    windowManager.updateViewLayout(other.view, other.windowLayoutParams)
                }
            }
            start()
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

        val startOnRight = currentCenterX >= screenWidth / 2f
        // Target edge is determined strictly by whether the projected rest position crosses the screen midpoint
        val targetOnRight = projectedCenterX >= screenWidth / 2f
        val isChangingSide = startOnRight != targetOnRight
        if (!isChangingSide) {
            item.isDockedOnRight = targetOnRight
            if (item.id == 0) isDockedOnRight = targetOnRight
        }

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
                    // 其他球不會因為別的球的碰撞或放開而被收進隱藏；使用者自己收起來的維持原狀
                    val otherShouldTuck = allowTuck && other.isTucked
                    other.isTucked = otherShouldTuck
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
                                            repositionOverlays()
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
                                            repositionOverlays()
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
        val isOverlayOpen = isScannerModeActive || isOcrModeActive || isPhrasesDrawerActive
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
                    // When an overlay window is open, use a critically damped Y trajectory (no cosine/sine oscillation)
                    // so the attached overlay window glides smoothly to finalItemTargetY without vertical bounce/jitter.
                    val currentY = if (isOverlayOpen) {
                        val expCrit = kotlin.math.exp((-omegaN * t).toDouble()).toFloat()
                        finalItemTargetY + c1y * (1f + omegaN * t) * expCrit
                    } else {
                        finalItemTargetY + exp * (c1y * cos + c2y * sin)
                    }

                    val newX = currentX.toInt().coerceIn(minClampX, maxClampX)
                    val newY = currentY.toInt().coerceIn(0, screenHeight - bubbleHeightPx)

                    if (isChangingSide) {
                        val crossedMidpoint = if (targetOnRight) {
                            (newX + bubbleWidthPx / 2f) >= screenWidth / 2f
                        } else {
                            (newX + bubbleWidthPx / 2f) < screenWidth / 2f
                        }
                        if (crossedMidpoint && item.isDockedOnRight != targetOnRight) {
                            item.isDockedOnRight = targetOnRight
                            if (item.id == 0) isDockedOnRight = targetOnRight
                        }
                    }

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
                            repositionOverlays()
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
                        repositionOverlays()
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


    // 懸浮按鈕 X 鍵點擊事件
    internal fun onXButtonClick() {
        if (isRestoringSnapshot) {
            Log.w(TAG, "onXButtonClick: snapshot restore already in progress, debouncing")
            return
        }
        HapticUtil.click(this)
        // Undo only when nothing else is going on. A snapshot stays valid for 15 s after a fill, so
        // without this check a ✕ pressed during the next recording undid the old text and left that
        // recording running in the background.
        when {
            isUndoMode() -> {
                isRestoringSnapshot = true
                try {
                    // 復原文字框 (Undo)：透過無障礙快照還原至貼上前之內容與游標位置、state → IDLE
                    VoiceAccessibilityService.instance?.restoreLastSnapshot()
                    setState(State.IDLE)
                    hideXButton()
                    checkAndHideBubbleIfKeyboardClosed(animate = true)
                } finally {
                    mainHandler.postDelayed({ isRestoringSnapshot = false }, 500)
                }
            }
            isPhrasesDrawerActive -> {
                // 關閉常用語抽屜視窗
                overlays.closePhrases()
                hideXButton()
            }
            isOcrSnapshotActive -> {
                // 關閉文字辨識快照與視窗
                dismissOcrSnapshot()
                overlays.stopOcr()
                hideXButton()
            }
            isOcrModeActive -> {
                // 關閉文字辨識相機視窗
                overlays.stopOcr()
                hideXButton()
            }
            isScannerModeActive -> {
                // 關閉條碼掃描相機視窗
                overlays.stopScanner()
                hideXButton()
            }
            state == State.RECORDING -> {
                // 中止錄音：停止 AudioRecord、丟棄音訊 buffer、關閉預覽氣泡、state → IDLE
                voice.abortRecording()
                hidePreviewText()
                setState(State.IDLE)
                hideXButton()
            }
            state == State.LOADING -> {
                // the models are still loading: drop the pending start so it does not begin recording later
                voice.abortLoading()
                hidePreviewText()
                setState(State.IDLE)
                hideXButton()
            }
            state == State.TRANSCRIBING -> {
                // 中斷模型推論 (Abort)：Cancel 背景轉譯協程、設置 isAborted 旗標阻止後續貼上、state → IDLE
                voice.abortTranscribing()
                hidePreviewText()
                setState(State.IDLE)
                hideXButton()
            }
            else -> {
                VoiceAccessibilityService.instance?.restoreLastSnapshot()
                setState(State.IDLE)
                hideXButton()
            }
        }
    }

    private fun updateXButtonPosition() {
        if (!::bubbleXButtonController.isInitialized) return
        val anchor = currentAnchorBubble
        val anchorX = anchor?.windowLayoutParams?.x ?: windowLayoutParams.x
        val anchorY = anchor?.windowLayoutParams?.y ?: windowLayoutParams.y
        bubbleXButtonController.updatePosition(
            anchorX = anchorX,
            anchorY = anchorY,
            screenWidth = getScreenWidth(),
            safeBottom = getSafeBottom(),
            statusBarHeight = getStatusBarHeight()
        )
    }

    /**
     * True when the ✕ button would undo the last fill (the text is in, nothing else is running or open).
     * While something is recording, loading or open it cancels that instead.
     */
    private fun isUndoMode(): Boolean {
        val nothingActive = !isPhrasesDrawerActive && !isOcrSnapshotActive && !isOcrModeActive &&
            !isScannerModeActive && state != State.RECORDING && state != State.TRANSCRIBING && state != State.LOADING
        return state == State.PASTED || (nothingActive && VoiceAccessibilityService.instance?.hasValidSnapshot() == true)
    }

    /** Keeps the button's icon (cancel or undo) in step with what a press would do. */
    internal fun refreshXButtonIcon() {
        if (!::bubbleXButtonController.isInitialized) return
        bubbleXButtonController.setUndo(isUndoMode())
    }

    internal fun showXButton() {
        if (!::bubbleXButtonController.isInitialized) return
        bubbleXButtonController.setUndo(isUndoMode())
        val anchor = currentAnchorBubble
        val anchorX = anchor?.windowLayoutParams?.x ?: windowLayoutParams.x
        val anchorY = anchor?.windowLayoutParams?.y ?: windowLayoutParams.y
        bubbleXButtonController.show(
            anchorX = anchorX,
            anchorY = anchorY,
            screenWidth = getScreenWidth(),
            safeBottom = getSafeBottom(),
            statusBarHeight = getStatusBarHeight(),
            onUntuck = { untuckAnchorBubble() }
        )
    }

    private fun hideXButton() {
        if (!::bubbleXButtonController.isInitialized) return
        bubbleXButtonController.hide()
    }

    private fun hideXButtonImmediately() {
        if (!::bubbleXButtonController.isInitialized) return
        bubbleXButtonController.hideImmediately()
    }

    private fun updatePulsePosition() {
        if (!::bubblePulseController.isInitialized) return
        val anchor = activeAnchorBubble ?: currentAnchorBubble
        val anchorX = anchor?.windowLayoutParams?.x ?: windowLayoutParams.x
        val anchorY = anchor?.windowLayoutParams?.y ?: windowLayoutParams.y
        bubblePulseController.updatePosition(anchorX, anchorY)
    }

    private fun showPulseView() {
        if (!::bubblePulseController.isInitialized) return
        val anchor = activeAnchorBubble ?: currentAnchorBubble
        val anchorX = anchor?.windowLayoutParams?.x ?: windowLayoutParams.x
        val anchorY = anchor?.windowLayoutParams?.y ?: windowLayoutParams.y
        bubblePulseController.show(anchorX, anchorY)
    }

    private fun hidePulseView() {
        if (!::bubblePulseController.isInitialized) return
        bubblePulseController.hide()
    }

    private fun onAudioRmsUpdate(rms: Float) {
        if (!::bubblePulseController.isInitialized) return
        bubblePulseController.onAudioRmsUpdate(rms, voice.isRecording)
    }

    private fun updatePreviewPosition() {
        if (!::bubblePreviewController.isInitialized) return
        val anchor = currentAnchorBubble
        val isRight = anchor?.isDockedOnRight ?: isDockedOnRight
        val anchorY = anchor?.windowLayoutParams?.y ?: windowLayoutParams.y
        bubblePreviewController.updatePosition(
            anchorY = anchorY,
            isDockedOnRight = isRight,
            screenWidth = getScreenWidth(),
            safeBottom = getSafeBottom()
        )
    }

    private fun hidePreviewText() {
        if (!::bubblePreviewController.isInitialized) return
        bubblePreviewController.hide()
    }

    /**
     * The card next to the bubble reports the bubble's state only. While the scanner, OCR or phrases window is open it
     * would sit on top of that window (they open on the same side), and the window already says what is going on, so
     * nothing is drawn then.
     */
    private fun showPreviewText(msg: String, kind: PreviewKind) {
        if (!::bubblePreviewController.isInitialized) return
        if (::overlays.isInitialized && (overlays.isScannerActive || overlays.isOcrActive || overlays.isPhrasesActive)) return
        val anchor = currentAnchorBubble
        val isRight = anchor?.isDockedOnRight ?: isDockedOnRight
        val anchorY = anchor?.windowLayoutParams?.y ?: windowLayoutParams.y
        bubblePreviewController.show(
            msg = msg,
            kind = kind,
            anchorY = anchorY,
            isDockedOnRight = isRight,
            screenWidth = getScreenWidth(),
            safeBottom = getSafeBottom(),
            onUntuck = { untuckAnchorBubble() }
        )
    }

    private fun getInitialListeningPreviewText(): String = getString(R.string.preview_listening)

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

    /**
     * Test hook for the debug build: shakes the first bubble [clones] times (as if the user had wiggled it), then drags it
     * along a short path and lets go, so the linked bubbles can be inspected without touch input.
     */
    fun debugShakeChainDemo(clones: Int, dragDx: Float, dragDy: Float, durationMs: Long = 1500L) {
        mainHandler.post {
            val leader = bubbleItems.firstOrNull() ?: return@post
            ModelConfig.setMultiBubbleEnabled(this, true)
            repeat(clones) { handleShakeToDuplicate(leader) }
            val x0 = leader.windowLayoutParams.x.toFloat()
            val y0 = leader.windowLayoutParams.y.toFloat()
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs
                addUpdateListener { va ->
                    val u = va.animatedFraction
                    leader.windowLayoutParams.x = (x0 + dragDx * u).toInt()
                    leader.windowLayoutParams.y = (y0 + dragDy * u).toInt()
                    if (leader.view.isAttachedToWindow) windowManager.updateViewLayout(leader.view, leader.windowLayoutParams)
                    if (leader.id == 0) { windowLayoutParams.x = leader.windowLayoutParams.x; windowLayoutParams.y = leader.windowLayoutParams.y }
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        shakeChain.end()
                        snapBubbleToSafeBoundsWithInertia(leader, 0f, 0f)
                    }
                })
                start()
            }
        }
    }

    /** Test hook for the debug build: sets how many bubbles exist. */
    fun debugSetBubbleCount(count: Int) {
        ModelConfig.setMultiBubbleEnabled(this, true)
        ModelConfig.setMultiBubbleCount(this, count)
        mainHandler.post { updateMultiBubbleMode() }
    }

    /**
     * [text] could not be filled in anywhere. It is kept in the quick-phrases history and the phrases drawer is opened
     * (from a phrases bubble that pops out of the bubble if there is none yet), so the user can see where it went and tap
     * it once a text field has focus. The clipboard is only written in clipboard-paste mode.
     * @return true (the drawer itself is the notice, so callers show nothing else).
     */
    private fun keepUnfilledText(text: String): Boolean {
        if (MODE_PHRASES !in ModelConfig.getEnabledBubbleModes(this)) {
            // 常用語功能已關閉：不彈出清單，改為複製到剪貼簿，由呼叫端顯示「已複製」提示
            copyToClipboardFallback(text)
            return false
        }
        QuickPhrasesManager.addHistory(this, text, force = true)
        if (ModelConfig.isPasteModeEnabled(this)) copyToClipboardFallback(text)
        mainHandler.post { revealPhrasesDrawer() }
        return true
    }

    /** Test hook for the debug build: shows [msg] on the card next to the bubble ([live] = live text style, otherwise status style); "-" hides it. */
    fun debugShowPreview(msg: String, live: Boolean) {
        mainHandler.post { if (msg.isEmpty() || msg == "-") hidePreviewText() else showPreviewText(msg, if (live) PreviewKind.LIVE else PreviewKind.STATUS) }
    }

    /** Test hook (used by the debug build's receiver): behaves like a recognized result that found no text field. */
    fun debugKeepUnfilledText(text: String) { keepUnfilledText(text) }

    /** Opens the phrases drawer next to a phrases bubble, adding one (a regular, saved bubble) when none exists and there is room. */
    private fun revealPhrasesDrawer() {
        if (isPhrasesDrawerActive) {
            phrasesDrawerController.refreshPhrasesList()
            return
        }
        val bubble = bubbleItems.firstOrNull { it.mode == MODE_PHRASES } ?: spawnPhrasesBubble()
        if (bubble != null) activeAnchorBubble = bubble
        overlays.openPhrases()
    }

    /**
     * A phrases bubble pops out above the current bubble and becomes a regular bubble: it is saved in the multi-bubble
     * settings like any other, so shaking, deleting and the settings screen all treat it the same way.
     * @return null when there is no room or the phrases mode is off.
     */
    private fun spawnPhrasesBubble(): FloatingBubbleItem? {
        if (MODE_PHRASES !in ModelConfig.getEnabledBubbleModes(this)) return null
        if (bubbleItems.size >= ModelConfig.getMaxMultiBubbleCountForEnabledModes(this)) return null
        val anchor = currentAnchorBubble ?: return null
        if (anchor.isTucked) untuckBubble(anchor, animate = false)

        val density = resources.displayMetrics.density
        val sizePx = (60 * density).toInt()
        val v = LayoutInflater.from(themedCtx).inflate(R.layout.layout_floating_bubble, null)
        val iv = v.findViewById<ImageView>(R.id.iv_bubble_icon)
        val (iconRes, desc) = getIconAndDescForMode(MODE_PHRASES)
        iv.setImageResource(iconRes)
        iv.contentDescription = desc

        val step = (68 * density).toInt()
        // 預設出現在當前球體上方；上方空間不足時才改放下方
        val above = anchor.windowLayoutParams.y - step
        val y = (if (above >= getMinY()) above else anchor.windowLayoutParams.y + step).coerceIn(getMinY(), getMaxY())
        val lp = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            allowDisplayCutout()
            gravity = Gravity.TOP or Gravity.LEFT
            x = anchor.windowLayoutParams.x
            this.y = y
        }
        val item = FloatingBubbleItem(
            id = bubbleItems.size,
            mode = MODE_PHRASES,
            view = v,
            windowLayoutParams = lp,
            btnBubble = v.findViewById(R.id.btn_bubble_mic),
            ivIcon = iv,
            progress = v.findViewById(R.id.progress_bubble)
        ).apply { isDockedOnRight = anchor.isDockedOnRight }

        attachBubbleTouchListener(item)
        v.scaleX = 0.55f
        v.scaleY = 0.55f
        v.alpha = 0.6f
        try {
            windowManager.addView(v, lp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add phrases bubble", e)
            return null
        }
        bubbleItems.add(item)
        persistBubbleLayout()
        HapticUtil.heavyClick(this)
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(240L)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
        return item
    }

    /** 目前的球數與各球模式寫回設定（多球模式開啟），並通知設定頁同步。 */
    private fun persistBubbleLayout() {
        ModelConfig.setMultiBubbleEnabled(this, true)
        ModelConfig.setMultiBubbleCount(this, bubbleItems.size)
        bubbleItems.forEachIndexed { index, b ->
            b.id = index
            setBubbleMode(index, b.mode)
        }
        ImeSettingsActivity.instance?.syncMultiBubbleState()
    }

    /** 截圖前暫時隱藏本程式所有懸浮畫面，截完立即還原，再開啟框選辨識。 */
    private fun captureScreenForOcr() {
        val acc = VoiceAccessibilityService.instance
        if (acc == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            ToastUtil.show(this, R.string.toast_screen_capture_failed)
            return
        }
        trackingWindowManager.hideAll()
        mainHandler.postDelayed({
            acc.captureScreen { bmp ->
                trackingWindowManager.showAll()
                if (bmp == null) {
                    ToastUtil.show(this, R.string.toast_screen_capture_failed)
                } else {
                    ocrOverlayController.showScreenSnapshot(bmp)
                }
            }
        }, 250)
    }

    private fun onTranscriptionDone(text: String) {

        if (text.isBlank()) {
            setState(State.IDLE)
            hideXButton()
            ToastUtil.show(this, R.string.preview_no_text_detected)
            return
        }

        QuickPhrasesManager.addHistory(this, text)

        deliverText(text) { ToastUtil.show(this, R.string.preview_copied) }
    }

    /**
     * 把 [text] 透過無障礙服務填入焦點欄位（不動剪貼簿）。
     * 成功：（可選）送出 Enter，並進入 PASTED 狀態顯示 ✕ 供 10 秒內還原。
     * 失敗（沒有焦點輸入框或服務不可用）：保留文字到常用語清單；若沒有彈出清單則呼叫 [onCopiedNotice] 顯示「已複製」類提示。
     * @return 是否填入成功
     */
    private var pendingEnter: Runnable? = null

    private fun flushPendingEnter() {
        pendingEnter?.let {
            mainHandler.removeCallbacks(it)
            it.run()
        }
    }

    private fun deliverText(text: String, autoEnter: Boolean = false, onCopiedNotice: () -> Unit): Boolean {
        // An Enter still waiting from the previous fill goes first, so two quick fills (e.g. two barcodes)
        // end up as "text, Enter, text, Enter" and not "text, text, Enter, Enter".
        flushPendingEnter()
        val injected = VoiceAccessibilityService.instance?.inputText(text) ?: false
        if (injected) {
            if (autoEnter) {
                val enter = Runnable {
                    pendingEnter = null
                    VoiceAccessibilityService.instance?.sendEnterKey()
                }
                pendingEnter = enter
                mainHandler.postDelayed(enter, 100)
            }
            enterPastedState()
        } else {
            setState(State.IDLE)
            hideXButton()
            if (!keepUnfilledText(text)) onCopiedNotice()
        }
        return injected
    }

    internal fun setState(s: State) {
        stateMachine.transitionTo(s)
        refreshXButtonIcon()
        if (state == State.RECORDING) {
            showPulseView()
        } else {
            hidePulseView()
        }
        if (!::btnBubbleMic.isInitialized) return

        val isMultiBubble = ModelConfig.isMultiBubbleEnabled(this)
        val activeTargetBubble = if (isMultiBubble) {
            activeAnchorBubble?.takeIf { it.mode == MODE_VOICE }
                ?: bubbleItems.firstOrNull { it.mode == MODE_VOICE }
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
            MODE_OCR -> R.drawable.ic_ocr to getString(R.string.floating_ocr_desc)
            MODE_BARCODE -> R.drawable.ic_barcode to getString(R.string.floating_barcode_desc)
            MODE_PHRASES -> R.drawable.ic_quick_phrases to getString(R.string.capsule_phrases_drawer_desc)
            else -> R.drawable.ic_mic to getString(R.string.floating_voice_desc)
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
            val oldMode = anchor.mode
            if (isMultiBubble && oldMode != mode) {
                val conflictingBubble = bubbleItems.find { it.id != anchor.id && it.mode == mode }
                if (conflictingBubble != null) {
                    conflictingBubble.mode = oldMode
                    val (cIconRes, cDesc) = getIconAndDescForMode(oldMode)
                    conflictingBubble.ivIcon.setImageResource(cIconRes)
                    conflictingBubble.ivIcon.contentDescription = cDesc
                    setBubbleMode(conflictingBubble.id, oldMode)
                    conflictingBubble.ivIcon.scaleX = 0.5f
                    conflictingBubble.ivIcon.scaleY = 0.5f
                    conflictingBubble.ivIcon.animate()
                        .scaleX(1.15f)
                        .scaleY(1.15f)
                        .setDuration(160)
                        .withEndAction {
                            conflictingBubble.ivIcon.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                        }
                        .start()
                }
            }
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
                overlays.closeAll()
            }
            MODE_OCR -> {
                setState(State.IDLE)
                overlays.startOcr()
            }
            MODE_BARCODE -> {
                setState(State.IDLE)
                overlays.startScanner()
            }
            MODE_PHRASES -> {
                setState(State.IDLE)
                overlays.openPhrases()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Scanner Mode Methods
    // ══════════════════════════════════════════════════════════════════
    // ══════════════════════════════════════════════════════════════════
    // Dedicated OCR Mode Window Methods
    // ══════════════════════════════════════════════════════════════════
    private fun dismissOcrSnapshot(hideX: Boolean = true) {
        ocrOverlayController.dismissSnapshot(hideX = hideX)
    }

    private fun handleOcrTextConfirmed(text: String) {
        if (text.isNotBlank()) {
            QuickPhrasesManager.addHistory(this, text)
        }
        dismissOcrSnapshot(hideX = false)
        overlays.stopOcr(hideX = false)
        overlays.stopScanner(hideX = false)

        deliverText(text, ModelConfig.isOcrAutoEnterEnabled(this)) {
            val previewMsg = if (text.length > 25) "${text.take(25)}…" else text
            ToastUtil.show(this, getString(R.string.preview_copied_with_text, previewMsg))
        }
    }

    private fun handleBarcodeDetected(code: String) {
        HapticUtil.heavyClick(this)
        if (code.isNotBlank()) {
            QuickPhrasesManager.addHistory(this, code)
        }
        val injected = deliverText(code, ModelConfig.isOcrAutoEnterEnabled(this)) {
            ToastUtil.show(this, R.string.preview_copied_barcode)
        }
        overlays.stopScanner(hideX = !injected)
    }

    // ══════════════════════════════════════════════════════════════════
    // Quick Phrases Drawer Methods
    // ══════════════════════════════════════════════════════════════════
    private fun insertQuickPhrase(phrase: String) {
        HapticUtil.click(this)
        flushPendingEnter()
        val injected = VoiceAccessibilityService.instance?.inputText(phrase) ?: false
        if (injected) {
            enterPastedState()
            overlays.closePhrases(hideX = false)
        } else {
            // nothing has focus: the drawer stays open so the user can focus a field and tap again
            setState(State.IDLE)
            if (ModelConfig.isPasteModeEnabled(this)) copyToClipboardFallback(phrase)
            ToastUtil.show(this, R.string.preview_phrase_no_focus)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        spareBubbleView = null // 主題或螢幕設定改變後，預載的版面可能過期
        // After a rotation the glass is rebuilt and any squish / magnet transform left over from an interrupted
        // animation is cleared, so no bubble stays deformed.
        for (item in bubbleItems) {
            (item.btnBubble as? com.ping.verbead.ui.GlassBubbleLayout)?.rebuildGlass()
            item.btnBubble.animate().cancel()
            item.btnBubble.scaleX = 1f
            item.btnBubble.scaleY = 1f
            item.btnBubble.rotation = 0f
        }
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
            overlays.setScannerEnlarged(isScannerEnlarged)
        }
        if (isOcrModeActive) {
            overlays.setOcrEnlarged(isOcrEnlarged)
        }
        repositionOverlays()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (instance === this) instance = null
            isRunning = false
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED

            safely("routingManager") { routingManager.stop() }
            safely("scanner") { if (::scannerOverlayController.isInitialized) scannerOverlayController.destroy() }
            safely("ocr") { if (::ocrOverlayController.isInitialized) ocrOverlayController.destroy() }
            safely("phrasesDrawer") { if (::phrasesDrawerController.isInitialized) phrasesDrawerController.destroy() }
            safely("capsule") { if (::capsuleMenuController.isInitialized) capsuleMenuController.destroy() }
            safely("deleteZone") { if (::bubbleDeleteZoneController.isInitialized) bubbleDeleteZoneController.hide(animated = false) }
                                    


            spareBubbleView = null
            safely("jobs") {
                snapAnimator?.cancel()
                autoHidePreviewJob?.cancel()
                xButtonAutoHideJob?.cancel()
            }
            safely("voice") { voice.release() }

            for (item in bubbleItems) {
                if (item.id != 0 && item.view.isAttachedToWindow) {
                    removeOverlay("bubble_${item.id}", item.view)
                }
            }
            bubbleItems.clear()

            safely("xButton") { if (::bubbleXButtonController.isInitialized) bubbleXButtonController.destroy() }
            safely("pulse") { if (::bubblePulseController.isInitialized) bubblePulseController.destroy() }
            safely("preview") { if (::bubblePreviewController.isInitialized) bubblePreviewController.destroy() }
            if (::bubbleView.isInitialized) removeOverlay("bubble", bubbleView)
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
        if (view == null || !windowManager.isViewAdded(view)) return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove $tag in onDestroy", e)
        }
    }
}
