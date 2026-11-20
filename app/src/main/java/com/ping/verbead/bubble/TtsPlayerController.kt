package com.ping.verbead.bubble

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import com.ping.verbead.R
import com.ping.verbead.VoiceAccessibilityService
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.engine.TtsCatalog
import com.ping.verbead.tts.HybridMerger
import com.ping.verbead.tts.OcrFallbackPolicy
import com.ping.verbead.tts.OcrTextSource
import com.ping.verbead.tts.PageContinuation
import com.ping.verbead.tts.ReadingOrder
import com.ping.verbead.tts.ReadingPlan
import com.ping.verbead.tts.ScreenScroller
import com.ping.verbead.tts.ScreenTextSource
import com.ping.verbead.tts.SherpaTtsEngine
import com.ping.verbead.tts.SpeechAudioFocus
import com.ping.verbead.tts.SystemTtsEngine
import com.ping.verbead.tts.TtsEngine
import com.ping.verbead.tts.TtsSession
import com.ping.verbead.util.HapticUtil
import com.ping.verbead.util.allowDisplayCutout
import com.ping.verbead.util.isViewAdded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The read-aloud player: a panel beside the bubble that reads the selection, or the text on the screen
 * (recognized from a screenshot when the screen does not expose its text), with the chosen voice. It
 * shows the sentence being spoken, marks its line on the screen, and offers play/pause, previous/next
 * sentence, speed, reload, and "start from here" (tap a line of the screen).
 */
class TtsPlayerController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val listener: Listener
) {

    interface Listener {
        fun onPlayerClosed(hideX: Boolean)
        fun onShowXButton()
        fun onHideXButton()

        /** The voice is speaking (true) or not (false); the bubble shows it. */
        fun onSpeakingChanged(speaking: Boolean) = Unit
    }

    var isActive: Boolean = false
        private set

    /** True between the tap on the bubble and the panel appearing (a screenshot may be on its way). */
    private var opening = false

    private var view: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var engine: TtsEngine? = null
    private var session: TtsSession? = null
    private var focus: SpeechAudioFocus? = null
    private var engineReady = false
    private var plan: ReadingPlan = ReadingPlan.EMPTY
    private val source = ScreenTextSource(context.packageName)
    private val highlight = TtsHighlightOverlay(context, windowManager)
    private val scroller = ScreenScroller(context.packageName)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** What the plan was made from; only a page of the screen (not a selection) can go on to the next page. */
    private var planKind = ScreenTextSource.Kind.EMPTY
    private var pagesRead = 0

    /** Set while the next page is being read: the sentences of the page before, to skip what the new one repeats. */
    private var continuationFrom: List<String>? = null
    private var speakingShown = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ocrJob: Job? = null

    private var tvSource: TextView? = null
    private var tvText: TextView? = null
    private var tvStatus: TextView? = null
    private var btnPlay: ImageView? = null
    private var btnSpeed: TextView? = null
    private var btnPick: View? = null

    // ── Open / close ────────────────────────────────────────────────────

    fun open(anchorY: Int, isDockedOnRight: Boolean, screenWidth: Int, safeBottom: Int) {
        if (isActive || opening) return
        opening = true
        // Read the screen before the panel exists: the panel's own text must never end up in what is read.
        val result = readSource()
        val service = VoiceAccessibilityService.instance
        if (service != null && wantsOcr(result)) {
            service.captureScreen { shot ->
                opening = false
                show(anchorY, isDockedOnRight, screenWidth, safeBottom, result, shot)
            }
        } else {
            opening = false
            show(anchorY, isDockedOnRight, screenWidth, safeBottom, result, null)
        }
    }

    private fun show(
        anchorY: Int, isDockedOnRight: Boolean, screenWidth: Int, safeBottom: Int,
        result: ScreenTextSource.Result, shot: Bitmap?
    ) {
        if (isActive) { shot?.recycle(); return }
        val root = view ?: LayoutInflater.from(context).inflate(R.layout.layout_floating_tts_player, null).also {
            com.ping.verbead.ui.GlassStage.skin(it)
            view = it
        }
        (root as? com.ping.verbead.ui.GlassLinearLayout)?.refreshPanelStyle()
        bindViews(root)

        val density = context.resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()
        val gap = (10 * density).toInt()
        val width = (280 * density).toInt().coerceAtMost(screenWidth - bubbleWidthPx - gap - (12 * density).toInt())

        layoutParams = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            allowDisplayCutout()
            gravity = if (isDockedOnRight) Gravity.TOP or Gravity.RIGHT else Gravity.TOP or Gravity.LEFT
            x = bubbleWidthPx + gap
            y = anchorY.coerceAtLeast(0)
        }
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        layoutParams?.let { it.y = anchorY.coerceIn(0, maxOf(0, safeBottom - root.measuredHeight - (8 * density).toInt())) }

        val bubbleCenterY = anchorY + 30 * density
        root.pivotX = if (isDockedOnRight) width.toFloat() else 0f
        root.pivotY = (bubbleCenterY - (layoutParams?.y ?: 0)).coerceIn(0f, root.measuredHeight.toFloat())
        root.translationX = if (isDockedOnRight) 24 * density else -24 * density
        root.alpha = 0f
        root.scaleX = 0.6f
        root.scaleY = 0.6f

        highlight.attach() // before the panel, so the panel is above it
        try {
            windowManager.addView(root, layoutParams)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add TTS player view", e)
            highlight.detach()
            shot?.recycle()
            return
        }
        isActive = true
        listener.onShowXButton()
        root.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
            .setDuration(240).setInterpolator(DecelerateInterpolator(1.5f)).start()

        onSource(result, shot)
    }

    fun close(hideX: Boolean = true, anchorY: Int = 0, isDockedOnRight: Boolean = false) {
        if (!isActive) return
        isActive = false
        ocrJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        highlight.detach()
        releaseSpeech()
        setSpeaking(false)
        if (hideX) listener.onHideXButton()
        val root = view ?: return
        val density = context.resources.displayMetrics.density
        root.animate().alpha(0f)
            .translationX(if (isDockedOnRight) 24 * density else -24 * density)
            .scaleX(0.6f).scaleY(0.6f).setDuration(180)
            .withEndAction {
                if (windowManager.isViewAdded(root)) {
                    try { windowManager.removeView(root) } catch (e: Exception) { Log.w(TAG, "Failed to remove TTS player view", e) }
                }
                view = null
                listener.onPlayerClosed(hideX)
            }
            .start()
    }

    /** Removes the windows at once, without animation (service shutdown). */
    fun destroy() {
        isActive = false
        opening = false
        scope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        highlight.detach()
        releaseSpeech()
        setSpeaking(false)
        view?.let { v ->
            if (windowManager.isViewAdded(v)) runCatching { windowManager.removeView(v) }
        }
        view = null
    }

    fun updatePosition(anchorY: Int, isDockedOnRight: Boolean, screenWidth: Int, safeBottom: Int) {
        val root = view ?: return
        val lp = layoutParams ?: return
        val density = context.resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()
        val gap = (10 * density).toInt()
        lp.gravity = if (isDockedOnRight) Gravity.TOP or Gravity.RIGHT else Gravity.TOP or Gravity.LEFT
        lp.x = bubbleWidthPx + gap
        lp.width = (280 * density).toInt().coerceAtMost(screenWidth - bubbleWidthPx - gap - (12 * density).toInt())
        val h = root.height.takeIf { it > 0 } ?: root.measuredHeight
        lp.y = anchorY.coerceIn(0, maxOf(0, safeBottom - h - (8 * density).toInt()))
        if (windowManager.isViewAdded(root)) runCatching { windowManager.updateViewLayout(root, lp) }
    }

    // ── Source ──────────────────────────────────────────────────────────

    private fun readSource(): ScreenTextSource.Result {
        val service = VoiceAccessibilityService.instance
            ?: return ScreenTextSource.Result(ScreenTextSource.Kind.EMPTY, "")
        return source.read(service)
    }

    private fun ocrModelReady(): Boolean = ModelConfig.isOcrTinyReady(context) || ModelConfig.isOcrSmallReady(context)

    /**
     * A selection is always read as it is. Otherwise the screen is recognized when the hybrid source is on
     * (always, to combine it with the tree), or when the tree gave too little.
     */
    private fun wantsOcr(result: ScreenTextSource.Result): Boolean =
        result.kind != ScreenTextSource.Kind.SELECTION && ocrModelReady() &&
            (ModelConfig.isTtsHybridEnabled(context) || OcrFallbackPolicy.needsOcr(result.chars, result.coverage))

    /**
     * Takes what [result] holds into the panel. When [shot] is there, the screen is recognized in the
     * background and its text replaces the tree's if it holds more.
     */
    private fun onSource(result: ScreenTextSource.Result, shot: Bitmap?) {
        planKind = result.kind
        val treePlan = when (result.kind) {
            ScreenTextSource.Kind.SELECTION -> ReadingPlan.fromText(result.text)
            ScreenTextSource.Kind.SCREEN -> ReadingPlan.fromLines(result.lines)
            ScreenTextSource.Kind.EMPTY -> ReadingPlan.EMPTY
        }
        val treeLabel = when (result.kind) {
            ScreenTextSource.Kind.SELECTION -> R.string.tts_source_selection
            ScreenTextSource.Kind.SCREEN -> R.string.tts_source_screen
            ScreenTextSource.Kind.EMPTY -> R.string.tts_mode_title
        }
        if (shot == null) {
            use(treePlan, treeLabel, emptyStatus(treePlan))
            return
        }

        // keep the tree's text on show while the recognizer works, but do not start speaking yet
        showPlan(treePlan, treeLabel)
        setStatus(R.string.tts_ocr_running)
        ocrJob?.cancel()
        ocrJob = scope.launch {
            val outcome = OcrTextSource(context).read(
                shot,
                ignoreTop = systemBarHeight("status_bar_height"), ignoreBottom = systemBarHeight("navigation_bar_height")
            )
            shot.recycle()
            if (!this@TtsPlayerController.isActive) return@launch
            when (outcome) {
                is OcrTextSource.Outcome.Lines -> {
                    if (ModelConfig.isTtsHybridEnabled(context)) {
                        // tree text where the tree has it, recognized text only where it does not
                        val merged = HybridMerger.merge(result.items, outcome.items)
                        Log.i(TAG, "hybrid: tree=${result.items.size} ocr=${outcome.items.size} matched=${merged.matched} inserted=${merged.inserted}")
                        val plan = ReadingPlan.fromLines(ReadingOrder.toLines(merged.items))
                        planKind = ScreenTextSource.Kind.SCREEN
                        use(plan, if (merged.inserted > 0) R.string.tts_source_hybrid else treeLabel, emptyStatus(plan))
                    } else {
                        val ocrPlan = ReadingPlan.fromLines(outcome.lines)
                        val better = ocrPlan.text.count { !it.isWhitespace() } > result.chars
                        if (better) {
                            planKind = ScreenTextSource.Kind.SCREEN
                            use(ocrPlan, R.string.tts_source_ocr, null)
                        } else {
                            use(treePlan, treeLabel, emptyStatus(treePlan))
                        }
                    }
                }
                OcrTextSource.Outcome.NoModel ->
                    use(treePlan, treeLabel, if (treePlan.isEmpty) R.string.tts_ocr_no_model else null)
                OcrTextSource.Outcome.Empty, OcrTextSource.Outcome.Failed ->
                    use(treePlan, treeLabel, emptyStatus(treePlan))
            }
        }
    }

    /** Height of a system bar in pixels, from the framework's own dimension; 0 when the device does not say. */
    private fun systemBarHeight(name: String): Int {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    private fun emptyStatus(p: ReadingPlan): Int? = when {
        VoiceAccessibilityService.instance == null -> R.string.tts_no_service
        p.isEmpty -> R.string.tts_no_text
        else -> null
    }

    /** Shows [p] and starts reading it. [status] replaces the status line (null: leave it to the player). */
    private fun use(p: ReadingPlan, labelRes: Int, status: Int?) {
        showPlan(p, labelRes)
        if (status != null) setStatus(status) else setStatus(null)
        val previous = continuationFrom
        continuationFrom = null
        if (p.isEmpty) {
            setSpeaking(false)
            return
        }
        if (previous == null) {
            startSpeaking()
            return
        }
        // reached by scrolling on: start after what the top of this page repeats from the last one
        val from = PageContinuation.firstNew(previous, p.sentences)
        if (from == null) {
            setStatus(R.string.tts_end_of_page)
            setSpeaking(false)
        } else {
            startSpeaking(from)
        }
    }

    private fun setSpeaking(speaking: Boolean) {
        if (speakingShown == speaking) return
        speakingShown = speaking
        listener.onSpeakingChanged(speaking)
    }

    /** Continuous reading goes on only when asked for, from a page of the screen, and within a sane number of pages. */
    private fun shouldContinue(): Boolean =
        isActive && ModelConfig.isTtsContinuousEnabled(context) && planKind == ScreenTextSource.Kind.SCREEN &&
            pagesRead < MAX_PAGES && VoiceAccessibilityService.instance != null

    /** Scrolls the screen on by a page and reads what comes into view (what repeats from this page is skipped). */
    private fun nextPage() {
        val service = VoiceAccessibilityService.instance
        val previous = plan.sentences.map { it.text }
        highlight.highlight(emptyList())
        if (service == null || !scroller.scrollForward(service)) {
            setStatus(R.string.tts_end_of_page)
            setSpeaking(false)
            return
        }
        pagesRead++
        setStatus(R.string.tts_next_page)
        // let the scroll come to rest before the screen is read
        mainHandler.postDelayed({ if (isActive) refresh(previous) }, SETTLE_MS)
    }

    private fun showPlan(p: ReadingPlan, labelRes: Int) {
        plan = p
        renderWindow(0)
        tvSource?.setText(labelRes)
        btnPick?.visibility = if (p.hasPositions) View.VISIBLE else View.GONE
        highlight.setLines(p.lines)
        highlight.highlight(emptyList())
        updatePlayIcon(TtsSession.State.IDLE)
    }

    // ── Speech ──────────────────────────────────────────────────────────

    private fun startSpeaking(from: Int = 0) {
        val list = plan.sentences
        if (list.isEmpty()) return
        if (focus == null) focus = SpeechAudioFocus(context) { session?.pause() }
        focus?.acquire()

        val existing = session
        if (existing != null && engineReady) {
            existing.rate = ModelConfig.getTtsRate(context)
            existing.start(list, from)
            return
        }
        setStatus(R.string.tts_preparing)
        startEngine(preferModel = true, from = from)
    }

    /**
     * Starts the engine the user chose: a downloaded model, or the system voice. When the model cannot be
     * loaded (damaged files, not enough memory) the system voice takes over so the reading still happens.
     */
    private fun startEngine(preferModel: Boolean, from: Int) {
        val model = if (preferModel) TtsCatalog.byId(ModelConfig.selectedTtsEngine(context)) else null
        val eng: TtsEngine = if (model != null) {
            SherpaTtsEngine(model, ModelConfig.ttsModelDir(context, model), sidFor = { lang ->
                ModelConfig.ttsVoiceSid(context, model.id) ?: model.defaultSidFor(lang.code)
            }, pauseScale = { ModelConfig.ttsPauseScale(context) })
        } else {
            SystemTtsEngine(context)
        }
        engine = eng
        val s = TtsSession(eng, sessionListener)
        session = s
        eng.init(s) { ok ->
            if (!isActive || session !== s) return@init
            if (!ok) {
                eng.shutdown()
                engine = null
                session = null
                if (model != null) {
                    setStatus(R.string.tts_model_failed)
                    startEngine(preferModel = false, from = from)
                } else {
                    setStatus(R.string.tts_unavailable)
                }
                return@init
            }
            engineReady = true
            setStatus(null)
            s.rate = ModelConfig.getTtsRate(context)
            s.start(plan.sentences, from)
        }
    }

    private fun releaseSpeech() {
        session?.stop()
        session = null
        engine?.shutdown()
        engine = null
        engineReady = false
        focus?.release()
        focus = null
    }

    private val sessionListener = object : TtsSession.Listener {
        override fun onStateChanged(state: TtsSession.State) {
            updatePlayIcon(state)
            when (state) {
                TtsSession.State.FINISHED -> {
                    focus?.release()
                    if (shouldContinue()) {
                        nextPage() // the bubble keeps showing that it is reading
                    } else {
                        setStatus(R.string.tts_finished)
                        highlight.highlight(emptyList())
                        setSpeaking(false)
                    }
                }
                TtsSession.State.ERROR -> {
                    setStatus(R.string.tts_unavailable); focus?.release(); highlight.highlight(emptyList()); setSpeaking(false)
                }
                TtsSession.State.SPEAKING -> { showProgress(session?.index ?: 0); focus?.acquire(); setSpeaking(true) }
                TtsSession.State.PAUSED, TtsSession.State.IDLE -> setSpeaking(false)
            }
        }

        override fun onSentenceChanged(index: Int) {
            renderWindow(index)
            showProgress(index)
            highlight.highlight(plan.linesOf(index))
        }
    }

    // ── View helpers ────────────────────────────────────────────────────

    private fun bindViews(root: View) {
        tvSource = root.findViewById(R.id.tv_tts_source)
        tvText = root.findViewById(R.id.tv_tts_text)
        tvStatus = root.findViewById(R.id.tv_tts_status)
        btnPlay = root.findViewById(R.id.btn_tts_play)
        btnSpeed = root.findViewById(R.id.btn_tts_speed)
        btnPick = root.findViewById(R.id.btn_tts_pick)
        btnSpeed?.text = formatRate(ModelConfig.getTtsRate(context))

        root.findViewById<View>(R.id.btn_tts_close).setOnClickListener {
            HapticUtil.click(context)
            close()
        }
        root.findViewById<View>(R.id.btn_tts_reload).setOnClickListener {
            HapticUtil.click(context)
            reload()
        }
        btnPick?.setOnClickListener {
            HapticUtil.click(context)
            togglePick()
        }
        btnPlay?.setOnClickListener {
            HapticUtil.click(context)
            val s = session
            if (s == null || !engineReady) startSpeaking() else s.toggle()
        }
        root.findViewById<View>(R.id.btn_tts_prev).setOnClickListener {
            HapticUtil.click(context)
            session?.previous()
        }
        root.findViewById<View>(R.id.btn_tts_next).setOnClickListener {
            HapticUtil.click(context)
            session?.next()
        }
        btnSpeed?.setOnClickListener {
            HapticUtil.click(context)
            val next = ModelConfig.nextTtsRate(ModelConfig.getTtsRate(context))
            ModelConfig.setTtsRate(context, next)
            btnSpeed?.text = formatRate(next)
            session?.rate = next
        }
    }

    private fun reload() {
        pagesRead = 0
        refresh(null)
    }

    /**
     * Reads the screen again, for when the selection or the page changed. The panel stays in place. With
     * [previous] (the sentences of the page before) it is the next page of a continuous reading.
     */
    private fun refresh(previous: List<String>?) {
        ocrJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        continuationFrom = previous
        if (previous == null) session?.stop()
        highlight.stopPick()
        highlight.setLines(emptyList())
        val result = readSource()
        val service = VoiceAccessibilityService.instance
        if (service != null && wantsOcr(result)) {
            // keep the panel out of the screenshot; the cleared highlight needs a frame to disappear
            view?.visibility = View.INVISIBLE
            view?.postDelayed({
                service.captureScreen { shot ->
                    view?.visibility = View.VISIBLE
                    if (isActive) onSource(result, shot) else shot?.recycle()
                }
            }, 120)
        } else {
            onSource(result, null)
        }
    }

    /** Outlines the lines of the screen; a tap on one starts the reading there. */
    private fun togglePick() {
        if (highlight.isPicking) {
            highlight.stopPick()
            setStatus(null)
            session?.let { showProgress(it.index) }
            return
        }
        if (!plan.hasPositions) return
        setStatus(R.string.tts_pick_hint)
        highlight.startPick(
            onPick = { lineIndex ->
                plan.firstSentenceOfLine(lineIndex)?.let { startSpeaking(it) }
            },
            onCancel = {
                setStatus(null)
                session?.let { showProgress(it.index) }
            }
        )
    }

    /**
     * Shows the sentence being read with the one before it and the next two. The current sentence is
     * highlighted, its neighbours dimmed. A short window reads better than the whole page, and the
     * panel stays a fixed small size however long the text is.
     */
    private fun renderWindow(current: Int) {
        val tv = tvText ?: return
        val sentences = plan.sentences
        if (sentences.isEmpty()) { tv.text = ""; return }
        val cur = current.coerceIn(0, sentences.lastIndex)
        val from = (cur - 1).coerceAtLeast(0)
        val to = (cur + 2).coerceAtMost(sentences.lastIndex)
        val sb = StringBuilder()
        var hlStart = 0
        var hlEnd = 0
        for (i in from..to) {
            if (sb.isNotEmpty()) sb.append('\n')
            if (i == cur) hlStart = sb.length
            sb.append(sentences[i].text)
            if (i == cur) hlEnd = sb.length
        }
        val spannable = SpannableString(sb.toString())
        spannable.setSpan(ForegroundColorSpan(Color.argb(140, 255, 255, 255)), 0, spannable.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(Color.WHITE), hlStart, hlEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(BackgroundColorSpan(Color.argb(110, 255, 255, 255)), hlStart, hlEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        tv.text = spannable
    }

    private fun updatePlayIcon(state: TtsSession.State) {
        val speaking = state == TtsSession.State.SPEAKING
        btnPlay?.setImageResource(if (speaking) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlay?.contentDescription = context.getString(if (speaking) R.string.tts_pause_desc else R.string.tts_play_desc)
    }

    /** "3 / 12" under the text; nothing when there is only one sentence. */
    private fun showProgress(index: Int) {
        val total = plan.sentences.size
        tvStatus?.text = if (total > 1) context.getString(R.string.tts_progress, index + 1, total) else ""
    }

    private fun setStatus(resId: Int?) {
        tvStatus?.text = if (resId == null) "" else context.getString(resId)
    }

    private fun formatRate(rate: Float): String = "${rate}x" // 0.75x, 1.0x, 1.25x, 1.5x, 2.0x

    private companion object {
        const val TAG = "TtsPlayerController"

        /** How long a scroll is given to come to rest before the new page is read. */
        const val SETTLE_MS = 900L

        /** A stop for a list that never ends (a feed): this many pages in one reading. */
        const val MAX_PAGES = 40
    }
}
