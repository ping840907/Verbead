package com.ping.verbead.bubble

import android.graphics.Color
import android.graphics.PixelFormat
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.content.Context
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
import com.ping.verbead.tts.ScreenTextSource
import com.ping.verbead.tts.Sentence
import com.ping.verbead.tts.SentenceSplitter
import com.ping.verbead.tts.SpeechAudioFocus
import com.ping.verbead.tts.SystemTtsEngine
import com.ping.verbead.tts.TtsEngine
import com.ping.verbead.tts.TtsSession
import com.ping.verbead.tts.TtsTextCleaner
import com.ping.verbead.util.HapticUtil
import com.ping.verbead.util.allowDisplayCutout
import com.ping.verbead.util.isViewAdded

/**
 * The read-aloud player: a panel beside the bubble that reads the selection (or the visible screen
 * text) with the system speech engine, highlights the sentence being spoken, and offers
 * play/pause, previous/next sentence, speed and reload.
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
    }

    var isActive: Boolean = false
        private set

    private var view: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var engine: TtsEngine? = null
    private var session: TtsSession? = null
    private var focus: SpeechAudioFocus? = null
    private var engineReady = false
    private var fullText = ""
    private var sentences: List<Sentence> = emptyList()
    private val source = ScreenTextSource(context.packageName)

    private var tvSource: TextView? = null
    private var tvText: TextView? = null
    private var tvStatus: TextView? = null
    private var btnPlay: ImageView? = null
    private var btnSpeed: TextView? = null

    // ── Open / close ────────────────────────────────────────────────────

    fun open(anchorY: Int, isDockedOnRight: Boolean, screenWidth: Int, safeBottom: Int) {
        if (isActive) return
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

        // Read the screen before the panel exists: the panel's own text must never end up in what is read.
        val result = readSource()

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
        showText(result)
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

        try {
            windowManager.addView(root, layoutParams)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add TTS player view", e)
            return
        }
        isActive = true
        listener.onShowXButton()
        root.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
            .setDuration(240).setInterpolator(DecelerateInterpolator(1.5f)).start()

        if (sentences.isNotEmpty()) startSpeaking()
    }

    fun close(hideX: Boolean = true, anchorY: Int = 0, isDockedOnRight: Boolean = false) {
        if (!isActive) return
        isActive = false
        releaseSpeech()
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

    /** Removes the window at once, without animation (service shutdown). */
    fun destroy() {
        isActive = false
        releaseSpeech()
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

    // ── Source and speech ───────────────────────────────────────────────

    private fun readSource(): ScreenTextSource.Result {
        val service = VoiceAccessibilityService.instance
            ?: return ScreenTextSource.Result(ScreenTextSource.Kind.EMPTY, "")
        return source.read(service)
    }

    /** Puts [result] into the panel and prepares the sentence list; speaking starts separately. */
    private fun showText(result: ScreenTextSource.Result) {
        fullText = TtsTextCleaner.clean(result.text)
        sentences = SentenceSplitter.split(fullText)
        renderWindow(0)
        when {
            VoiceAccessibilityService.instance == null -> setStatus(R.string.tts_no_service)
            sentences.isEmpty() -> setStatus(R.string.tts_no_text)
            else -> setStatus(null)
        }
        tvSource?.setText(
            when (result.kind) {
                ScreenTextSource.Kind.SELECTION -> R.string.tts_source_selection
                ScreenTextSource.Kind.SCREEN -> R.string.tts_source_screen
                ScreenTextSource.Kind.EMPTY -> R.string.tts_mode_title
            }
        )
        updatePlayIcon(TtsSession.State.IDLE)
    }

    private fun startSpeaking() {
        val list = sentences
        if (list.isEmpty()) return
        if (focus == null) focus = SpeechAudioFocus(context) { session?.pause() }
        focus?.acquire()

        val existing = session
        if (existing != null && engineReady) {
            existing.rate = ModelConfig.getTtsRate(context)
            existing.start(list)
            return
        }
        setStatus(R.string.tts_preparing)
        val eng = SystemTtsEngine(context)
        engine = eng
        val s = TtsSession(eng, sessionListener)
        session = s
        eng.init(s) { ok ->
            if (!isActive) return@init
            if (!ok) {
                setStatus(R.string.tts_unavailable)
                return@init
            }
            engineReady = true
            setStatus(null)
            s.rate = ModelConfig.getTtsRate(context)
            s.start(sentences)
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
                TtsSession.State.FINISHED -> { setStatus(R.string.tts_finished); focus?.release() }
                TtsSession.State.ERROR -> { setStatus(R.string.tts_unavailable); focus?.release() }
                TtsSession.State.SPEAKING -> { showProgress(session?.index ?: 0); focus?.acquire() }
                else -> Unit
            }
        }

        override fun onSentenceChanged(index: Int) {
            renderWindow(index)
            showProgress(index)
        }
    }

    // ── View helpers ────────────────────────────────────────────────────

    private fun bindViews(root: View) {
        tvSource = root.findViewById(R.id.tv_tts_source)
        tvText = root.findViewById(R.id.tv_tts_text)
        tvStatus = root.findViewById(R.id.tv_tts_status)
        btnPlay = root.findViewById(R.id.btn_tts_play)
        btnSpeed = root.findViewById(R.id.btn_tts_speed)
        btnSpeed?.text = formatRate(ModelConfig.getTtsRate(context))

        root.findViewById<View>(R.id.btn_tts_close).setOnClickListener {
            HapticUtil.click(context)
            close()
        }
        root.findViewById<View>(R.id.btn_tts_reload).setOnClickListener {
            HapticUtil.click(context)
            reload()
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

    /** Reads the screen again, for when the selection or the page changed. The panel stays in place. */
    private fun reload() {
        session?.stop()
        // the panel is an overlay of this app, so it is skipped by the source and does not need to be hidden
        showText(readSource())
        if (sentences.isNotEmpty()) startSpeaking()
    }

    /**
     * Shows the sentence being read with the one before it and the next two. The current sentence is
     * highlighted, its neighbours dimmed. A short window reads better than the whole page, and the
     * panel stays a fixed small size however long the text is.
     */
    private fun renderWindow(current: Int) {
        val tv = tvText ?: return
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
        val dim = ForegroundColorSpan(Color.argb(140, 255, 255, 255))
        spannable.setSpan(dim, 0, spannable.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
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
        val total = sentences.size
        tvStatus?.text = if (total > 1) context.getString(R.string.tts_progress, index + 1, total) else ""
    }

    private fun setStatus(resId: Int?) {
        tvStatus?.text = if (resId == null) "" else context.getString(resId)
    }

    private fun formatRate(rate: Float): String = "${rate}x" // 0.75x, 1.0x, 1.25x, 1.5x, 2.0x

    private companion object {
        const val TAG = "TtsPlayerController"
    }
}
