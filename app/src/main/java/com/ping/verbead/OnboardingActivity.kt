package com.ping.verbead

import android.animation.AnimatorSet
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.util.HapticUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * OnboardingActivity: Interactive user experience tour.
 * Explains core features:
 * 1. Voice typing (with red listening state)
 * 2. Vertical capsule drag-to-align mode switching
 * 3. Small floating X button to undo/withdraw input
 * 4. 70% edge tucking & software keyboard sync
 * 5. 100% offline on-device privacy
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var vpOnboarding: ViewPager2
    private lateinit var btnSkip: MaterialButton
    private lateinit var btnPrev: MaterialButton
    private lateinit var btnNext: MaterialButton
    private lateinit var dots: List<View>

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentAnimJob: Job? = null
    private var currentAnimatorSet: AnimatorSet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        com.ping.verbead.ui.GlassStage.install(this)

        initViews()
        setupViewPager()

        onBackPressedDispatcher.addCallback(this, backToPreviousPage)
    }

    /**
     * Back goes to the previous slide. It is only enabled past the first slide, so on the first slide the system
     * handles back itself and can show its predictive "peek at where you are going" animation.
     */
    private val backToPreviousPage = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (vpOnboarding.currentItem > 0) vpOnboarding.currentItem = vpOnboarding.currentItem - 1
        }
    }

    private fun initViews() {
        vpOnboarding = findViewById(R.id.vp_onboarding)
        btnSkip = findViewById(R.id.btn_onboarding_skip)
        btnPrev = findViewById(R.id.btn_onboarding_prev)
        btnNext = findViewById(R.id.btn_onboarding_next)

        dots = listOf(
            findViewById(R.id.dot_0),
            findViewById(R.id.dot_1),
            findViewById(R.id.dot_2),
            findViewById(R.id.dot_3),
            findViewById(R.id.dot_4)
        )

        btnSkip.setOnClickListener {
            HapticUtil.click(this)
            completeOnboarding()
        }

        btnPrev.setOnClickListener {
            HapticUtil.click(this)
            if (vpOnboarding.currentItem > 0) {
                vpOnboarding.currentItem = vpOnboarding.currentItem - 1
            }
        }

        btnNext.setOnClickListener {
            HapticUtil.click(this)
            if (vpOnboarding.currentItem < 4) {
                vpOnboarding.currentItem = vpOnboarding.currentItem + 1
            } else {
                completeOnboarding()
            }
        }
    }

    private fun setupViewPager() {
        val adapter = OnboardingSlideAdapter(this)
        vpOnboarding.adapter = adapter

        vpOnboarding.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                backToPreviousPage.isEnabled = position > 0
                updateNavigationUI(position)
                playPageAnimation(position)
            }
        })

        // Initial UI update
        updateNavigationUI(0)
    }

    private fun updateNavigationUI(position: Int) {
        // Prev button visibility
        btnPrev.visibility = if (position > 0) View.VISIBLE else View.INVISIBLE

        // Next button text
        if (position == 4) {
            btnNext.text = getString(R.string.onboarding_finish)
        } else {
            btnNext.text = getString(R.string.onboarding_next)
        }

        // Indicator dots
        val activeWidth = (20 * resources.displayMetrics.density).toInt()
        val inactiveWidth = (6 * resources.displayMetrics.density).toInt()
        val styleModule = com.ping.verbead.ui.style.UiStyleManager.current(this)
        val dark = com.ping.verbead.ui.style.UiStyleManager.isDark(this)
        for (i in dots.indices) {
            val dot = dots[i]
            val params = dot.layoutParams
            val isActive = i == position
            params.width = if (isActive) activeWidth else inactiveWidth
            styleModule.styleIndicatorDot(dot, isActive, dark)
            dot.layoutParams = params
        }
    }

    private fun playPageAnimation(position: Int) {
        stopAllAnimations()

        val activeHolder = (vpOnboarding.getChildAt(0) as? RecyclerView)
            ?.findViewHolderForAdapterPosition(position) as? OnboardingSlideAdapter.SlideViewHolder
        val view = activeHolder?.itemView ?: return

        when (position) {
            0 -> startVoiceTypingAnimation(view)
            1 -> startCapsuleMenuAnimation(view)
            2 -> startUndoAnimation(view)
            3 -> startTuckAndKeyboardAnimation(view)
            4 -> startPrivacyAnimation(view)
        }
    }

    private fun stopAllAnimations() {
        currentAnimJob?.cancel()
        currentAnimJob = null
        currentAnimatorSet?.cancel()
        currentAnimatorSet = null
    }

    // ── Slide 0: Voice Typing Mockup Animation (Red active state) ────────────
    private fun startVoiceTypingAnimation(view: View) {
        val tvMockInput = view.findViewById<TextView>(R.id.tv_mock_voice_input) ?: return
        val viewCursor = view.findViewById<View>(R.id.view_mock_cursor)
        val bubbleLayout = view.findViewById<View>(R.id.layout_mock_bubble)
        val bubbleIcon = view.findViewById<ImageView>(R.id.iv_mock_bubble_icon)
        val finger = view.findViewById<View>(R.id.view_mock_voice_finger)
        val rippleView = view.findViewById<View>(R.id.view_mock_bubble_ripple)
        com.ping.verbead.ui.style.UiStyleManager.current(this).stylePulseViews(rippleView, null)
        val previewPill = view.findViewById<View>(R.id.layout_mock_preview_pill)
        val wave1 = view.findViewById<View>(R.id.mock_wave_1)
        val wave2 = view.findViewById<View>(R.id.mock_wave_2)
        val wave3 = view.findViewById<View>(R.id.mock_wave_3)

        currentAnimJob = activityScope.launch {
            val sampleText = getString(R.string.onboarding_voice_sample)
            while (isActive) {
                // Initial IDLE state: blue bubble
                tvMockInput.text = ""
                previewPill?.alpha = 0f
                rippleView?.alpha = 0f
                finger?.alpha = 0f
                bubbleLayout?.setBackgroundResource(R.drawable.bubble_background)
                bubbleIcon?.setImageResource(R.drawable.ic_mic)

                delay(600)
                if (!isActive) break

                // Finger dot moves in to tap the bubble
                finger?.animate()?.alpha(0.85f)?.setDuration(250)?.start()
                delay(300)
                if (!isActive) break

                // Bubble press scale feedback
                bubbleLayout?.animate()?.scaleX(0.9f)?.scaleY(0.9f)?.setDuration(120)?.withEndAction {
                    bubbleLayout.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(120)?.start()
                }?.start()

                delay(120)
                // RECORDING / LISTENING: Turns RED!
                bubbleLayout?.setBackgroundResource(R.drawable.bubble_background_active)
                bubbleIcon?.setImageResource(R.drawable.ic_mic_active)
                previewPill?.animate()?.alpha(1f)?.setDuration(250)?.start()
                rippleView?.animate()?.alpha(0.5f)?.scaleX(1.35f)?.scaleY(1.35f)?.setDuration(350)?.withEndAction {
                    rippleView.animate()?.alpha(0f)?.setDuration(250)?.start()
                }?.start()

                // Finger fades away
                finger?.animate()?.alpha(0f)?.setDuration(200)?.start()

                // Streaming typing loop
                val sb = StringBuilder()
                for (char in sampleText) {
                    if (!isActive) break
                    sb.append(char)
                    tvMockInput.text = sb.toString()

                    // Waveform jitter
                    wave1?.scaleY = (0.5f + Math.random().toFloat() * 0.9f)
                    wave2?.scaleY = (0.6f + Math.random().toFloat() * 1.0f)
                    wave3?.scaleY = (0.5f + Math.random().toFloat() * 0.8f)

                    // Cursor blink
                    viewCursor?.visibility = if (sb.length % 2 == 0) View.VISIBLE else View.INVISIBLE

                    delay(110)
                }

                // Done transcribing / pasting: Return to BLUE bubble
                delay(300)
                bubbleLayout?.setBackgroundResource(R.drawable.bubble_background)
                bubbleIcon?.setImageResource(R.drawable.ic_mic)
                viewCursor?.visibility = View.VISIBLE
                previewPill?.animate()?.alpha(0f)?.setDuration(350)?.start()

                // Pause before restarting demonstration
                delay(2400)
            }
        }
    }

    // ── Slide 1: Capsule Menu Animation (Translates entire capsule pill) ─────
    private fun startCapsuleMenuAnimation(view: View) {
        val capsulePill = view.findViewById<View>(R.id.layout_mock_capsule_pill) ?: return
        val cardModeInfo = view.findViewById<View>(R.id.card_mock_mode_info)
        val tvModeTitle = view.findViewById<TextView>(R.id.tv_mock_active_mode_title)
        val tvModeDesc = view.findViewById<TextView>(R.id.tv_mock_active_mode_desc)
        val ivModeIcon = view.findViewById<ImageView>(R.id.iv_mock_active_mode_icon)
        val tvModeBadge = view.findViewById<TextView>(R.id.tv_mock_mode_badge)
        val tvFeatureTags = view.findViewById<TextView>(R.id.tv_mock_feature_tags)
        val layoutScreen = view.findViewById<View>(R.id.layout_mock_screen_clickable)

        val itemVoice = view.findViewById<View>(R.id.mock_item_voice)
        val itemBarcode = view.findViewById<View>(R.id.mock_item_barcode)
        val itemOcr = view.findViewById<View>(R.id.mock_item_ocr)
        val itemPhrases = view.findViewById<View>(R.id.mock_item_phrases)

        val density = resources.displayMetrics.density
        val itemPitchPx = 56f * density
        val totalModes = 4

        var currentModeIndex = 0

        fun updateModeContent(mode: Int) {
            when (mode) {
                0 -> {
                    tvModeTitle?.text = getString(R.string.onboarding_mode_voice_title)
                    tvModeDesc?.text = getString(R.string.onboarding_mode_voice_desc)
                    ivModeIcon?.setImageResource(R.drawable.ic_mic)
                    tvModeBadge?.text = getString(R.string.onboarding_mode_badge, 1, 4)
                    tvFeatureTags?.text = getString(R.string.onboarding_mode_voice_tags)
                }
                1 -> {
                    tvModeTitle?.text = getString(R.string.onboarding_mode_barcode_title)
                    tvModeDesc?.text = getString(R.string.onboarding_mode_barcode_desc)
                    ivModeIcon?.setImageResource(R.drawable.ic_barcode)
                    tvModeBadge?.text = getString(R.string.onboarding_mode_badge, 2, 4)
                    tvFeatureTags?.text = getString(R.string.onboarding_mode_barcode_tags)
                }
                2 -> {
                    tvModeTitle?.text = getString(R.string.onboarding_mode_ocr_title)
                    tvModeDesc?.text = getString(R.string.onboarding_mode_ocr_desc)
                    ivModeIcon?.setImageResource(R.drawable.ic_ocr)
                    tvModeBadge?.text = getString(R.string.onboarding_mode_badge, 3, 4)
                    tvFeatureTags?.text = getString(R.string.onboarding_mode_ocr_tags)
                }
                3 -> {
                    tvModeTitle?.text = getString(R.string.onboarding_mode_phrases_title)
                    tvModeDesc?.text = getString(R.string.onboarding_mode_phrases_desc)
                    ivModeIcon?.setImageResource(R.drawable.ic_quick_phrases)
                    tvModeBadge?.text = getString(R.string.onboarding_mode_badge, 4, 4)
                    tvFeatureTags?.text = getString(R.string.onboarding_mode_phrases_tags)
                }
            }
        }

        fun applyModeDisplay(mode: Int, animate: Boolean) {
            currentModeIndex = mode
            val targetTranslationY = -mode * itemPitchPx
            if (animate) {
                capsulePill.animate()
                    .translationY(targetTranslationY)
                    .setDuration(380)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()

                cardModeInfo?.animate()
                    ?.alpha(0.35f)
                    ?.scaleX(0.96f)
                    ?.scaleY(0.96f)
                    ?.setDuration(120)
                    ?.withEndAction {
                        updateModeContent(mode)
                        cardModeInfo.animate()
                            ?.alpha(1f)
                            ?.scaleX(1f)
                            ?.scaleY(1f)
                            ?.setDuration(180)
                            ?.start()
                    }
                    ?.start()
            } else {
                capsulePill.translationY = targetTranslationY
                updateModeContent(mode)
            }
        }

        fun startAutoAdvanceLoop(initialMode: Int) {
            currentAnimJob?.cancel()
            currentAnimJob = activityScope.launch {
                var mode = initialMode
                while (isActive) {
                    delay(2600)
                    if (!isActive) break
                    mode = (mode + 1) % totalModes
                    applyModeDisplay(mode, animate = true)
                }
            }
        }

        fun onModeSelectedByUser(mode: Int) {
            HapticUtil.click(this@OnboardingActivity)
            applyModeDisplay(mode, animate = true)
            startAutoAdvanceLoop(mode)
        }

        itemVoice?.setOnClickListener { onModeSelectedByUser(0) }
        itemBarcode?.setOnClickListener { onModeSelectedByUser(1) }
        itemOcr?.setOnClickListener { onModeSelectedByUser(2) }
        itemPhrases?.setOnClickListener { onModeSelectedByUser(3) }

        val advanceToNext = {
            val next = (currentModeIndex + 1) % totalModes
            onModeSelectedByUser(next)
        }
        cardModeInfo?.setOnClickListener { advanceToNext() }
        layoutScreen?.setOnClickListener { advanceToNext() }

        // 初始狀態：載入模式 0 並啟動自動輪播
        applyModeDisplay(0, animate = false)
        startAutoAdvanceLoop(0)
    }

    // ── Slide 2: Undo Input via Small X Button Mockup Animation ─────────────
    private fun startUndoAnimation(view: View) {
        val tvInput = view.findViewById<TextView>(R.id.tv_mock_undo_input) ?: return
        val btnXIcon = view.findViewById<View>(R.id.btn_mock_undo_x) ?: return
        // wrapped in its glass plate by GlassStage.skin: animate the plate so the disc and icon move together
        val btnX: View = (btnXIcon.parent as? com.ping.verbead.ui.GlassPlateFrame) ?: btnXIcon
        val tvToast = view.findViewById<TextView>(R.id.tv_mock_undo_toast)
        val finger = view.findViewById<View>(R.id.view_mock_undo_finger)

        val fullText = getString(R.string.onboarding_undo_sample)

        currentAnimJob = activityScope.launch {
            while (isActive) {
                // Initial state: text already pasted, small X button shown above bubble
                tvInput.text = fullText
                tvToast?.alpha = 0f
                finger?.alpha = 0f
                btnX.alpha = 1f
                btnX.translationY = 0f
                btnX.scaleX = 1f
                btnX.scaleY = 1f

                delay(1200)
                if (!isActive) break

                // Finger moves to tap X button
                finger?.animate()?.alpha(0.85f)?.setDuration(250)?.start()
                delay(300)
                if (!isActive) break

                // X button press feedback
                btnX.animate().scaleX(0.85f).scaleY(0.85f).setDuration(120).withEndAction {
                    btnX.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                }.start()

                delay(150)
                // Undo performed: text is cleared / rolled back!
                tvInput.text = ""
                tvToast?.animate()?.alpha(1f)?.setDuration(250)?.start()

                // Finger lifts and X button hides
                finger?.animate()?.alpha(0f)?.setDuration(200)?.start()
                btnX.animate()
                    .alpha(0f)
                    .translationY(15f)
                    .scaleX(0.6f)
                    .scaleY(0.6f)
                    .setDuration(250)
                    .start()

                delay(2000)
                if (!isActive) break
                tvToast?.animate()?.alpha(0f)?.setDuration(300)?.start()
                delay(500)
            }
        }
    }

    // ── Slide 3: 70% Edge Tucking, Keyboard Linkage & Multi-Bubble Split ────
    private fun startTuckAndKeyboardAnimation(view: View) {
        val bubble = view.findViewById<View>(R.id.layout_mock_tuck_bubble) ?: return
        val cloneBubble = view.findViewById<View>(R.id.layout_mock_clone_bubble)
        val keyboard = view.findViewById<View>(R.id.view_mock_keyboard) ?: return
        val tuckArrow = view.findViewById<View>(R.id.iv_mock_tuck_arrow)
        val input = view.findViewById<View>(R.id.layout_mock_input)
        val caret = view.findViewById<View>(R.id.view_mock_caret)
        val badge = view.findViewById<ImageView>(R.id.iv_mock_kbd_badge)
        val tap = view.findViewById<View>(R.id.view_mock_kbd_tap)
        val screen = view.findViewById<View>(R.id.frame_mock_phone_screen)
        val tvTitle = view.findViewById<TextView>(R.id.tv_mock_tuck_status_title)
        val tvDesc = view.findViewById<TextView>(R.id.tv_mock_tuck_status_desc)

        val density = resources.displayMetrics.density
        // Bubble marginEnd is 12dp inside screen. Bubble width is 52dp.
        // Translating right by (12dp + 36dp) = 48dp pushes 36dp (70%) outside and leaves 16dp (30%) visible!
        val tuckHiddenX = 48f * density
        val keyboardHeightPx = 124f * density
        val shakeOffsetPx = 22f * density
        val cloneOffsetYPx = -62f * density
        val inputLiftPx = -(keyboardHeightPx + 4f * density)

        val primary = com.google.android.material.color.MaterialColors.getColor(
            view, androidx.appcompat.R.attr.colorPrimary, android.graphics.Color.GREEN
        )
        val onPrimary = com.google.android.material.color.MaterialColors.getColor(
            view, com.google.android.material.R.attr.colorOnPrimary, android.graphics.Color.WHITE
        )
        val badgeOffBg = com.google.android.material.color.MaterialColors.getColor(
            view, com.google.android.material.R.attr.colorSurfaceVariant, android.graphics.Color.LTGRAY
        )
        val badgeOffFg = com.google.android.material.color.MaterialColors.getColor(
            view, com.google.android.material.R.attr.colorOnSurfaceVariant, android.graphics.Color.DKGRAY
        )

        fun setBadge(on: Boolean) {
            badge?.backgroundTintList = android.content.res.ColorStateList.valueOf(if (on) primary else badgeOffBg)
            badge?.imageTintList = android.content.res.ColorStateList.valueOf(if (on) onPrimary else badgeOffFg)
            if (on) {
                badge?.animate()?.cancel()
                badge?.scaleX = 0.7f
                badge?.scaleY = 0.7f
                badge?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(300)
                    ?.setInterpolator(OvershootInterpolator(3f))?.start()
            }
        }

        currentAnimJob = activityScope.launch {
            // The text field's caret blinks while the keyboard is away.
            launch {
                while (isActive) {
                    caret?.alpha = if ((caret?.alpha ?: 0f) > 0.5f) 0f else 1f
                    delay(520)
                }
            }
            while (isActive) {
                // Reset to the resting state: no keyboard, bubble on the edge.
                keyboard.animate().cancel()
                keyboard.translationY = keyboardHeightPx
                input?.animate()?.cancel()
                input?.translationY = 0f
                tap?.alpha = 0f
                setBadge(false)
                cloneBubble?.alpha = 0f
                cloneBubble?.translationX = 0f
                cloneBubble?.translationY = 0f
                bubble.animate().cancel()
                bubble.scaleX = 1f
                bubble.scaleY = 1f
                bubble.translationY = 0f
                bubble.translationX = 0f
                tuckArrow?.animate()?.cancel()
                tuckArrow?.alpha = 0f

                // Phase 1: Edge Tuck (70% outside, 30% visible)
                tvTitle?.text = getString(R.string.onboarding_gesture_edge_title)
                tvDesc?.text = getString(R.string.onboarding_gesture_edge_desc)
                bubble.alpha = 1.0f
                bubble.animate()
                    .translationX(tuckHiddenX)
                    .setDuration(700)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
                // The pull-out arrow appears once the ball has settled at the edge, like on the real bubble.
                tuckArrow?.animate()?.alpha(0.9f)?.setStartDelay(550)?.setDuration(180)?.start()

                delay(2400)
                if (!isActive) break

                // Phase 2: Keyboard Linkage. The bubble is hidden while there is no keyboard; tapping the text field
                // opens the keyboard, the field rides up on it, and the bubble pops in right above both.
                tvTitle?.text = getString(R.string.onboarding_gesture_keyboard_title)
                tvDesc?.text = getString(R.string.onboarding_gesture_keyboard_desc)

                bubble.animate().cancel()
                tuckArrow?.animate()?.cancel()
                tuckArrow?.alpha = 0f
                bubble.alpha = 0f
                bubble.translationX = 0f
                bubble.scaleX = 0.6f
                bubble.scaleY = 0.6f
                // Parked just above the keyboard-lifted text field, on the right.
                val screenH = (screen?.height ?: 0).toFloat()
                val padPx = 16f * density
                val bubbleH = 52f * density
                val baseTop = padPx + ((screenH - 2 * padPx) - bubbleH) / 2f
                val inputTop = screenH - padPx - 4f * density - 40f * density + inputLiftPx
                val bubbleTop = inputTop - 10f * density - bubbleH
                bubble.translationY = if (screenH > 0f) bubbleTop - baseTop else -keyboardHeightPx / 2f

                delay(500)
                if (!isActive) break

                // The tap on the text field.
                tap?.scaleX = 0.5f
                tap?.scaleY = 0.5f
                tap?.animate()?.alpha(0.85f)?.scaleX(1f)?.scaleY(1f)?.setDuration(220)?.start()
                delay(420)
                tap?.animate()?.alpha(0f)?.setDuration(260)?.start()

                // Keyboard slides up, the field rides up with it, the state badge lights up.
                keyboard.animate().translationY(0f).setDuration(420)
                    .setInterpolator(OvershootInterpolator(0.9f)).start()
                input?.animate()?.translationY(inputLiftPx)?.setDuration(420)
                    ?.setInterpolator(OvershootInterpolator(0.9f))?.start()
                setBadge(true)
                caret?.alpha = 1f

                // The bubble fades in with a little pop once the keyboard is up.
                delay(260)
                bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380)
                    .setInterpolator(OvershootInterpolator(2.2f)).start()

                delay(2300)
                if (!isActive) break

                // Keyboard goes away again and the bubble goes with it.
                keyboard.animate().translationY(keyboardHeightPx).setDuration(320)
                    .setInterpolator(AccelerateDecelerateInterpolator()).start()
                input?.animate()?.translationY(0f)?.setDuration(320)
                    ?.setInterpolator(AccelerateDecelerateInterpolator())?.start()
                bubble.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(220).start()
                setBadge(false)
                delay(1000)
                if (!isActive) break

                // Phase 3: Shake to Duplicate Multi-Bubble
                tvTitle?.text = getString(R.string.onboarding_gesture_shake_title)
                tvDesc?.text = getString(R.string.onboarding_gesture_shake_desc)

                bubble.translationY = 0f
                bubble.translationX = 0f
                bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
                delay(300)
                if (!isActive) break

                bubble.animate()
                    .translationX(-shakeOffsetPx)
                    .translationY(16f * density)
                    .setDuration(110)
                    .start()
                delay(120)
                bubble.animate().translationX(0f).setDuration(110).start()
                delay(120)
                bubble.animate().translationX(-shakeOffsetPx).setDuration(110).start()
                delay(120)
                bubble.animate().translationX(0f).setDuration(120).start()
                delay(130)
                if (!isActive) break

                // Spawn clone bubble above primary bubble
                cloneBubble?.translationX = 0f
                cloneBubble?.translationY = 16f * density
                cloneBubble?.scaleX = 0.5f
                cloneBubble?.scaleY = 0.5f
                cloneBubble?.alpha = 0f
                cloneBubble?.animate()
                    ?.alpha(1.0f)
                    ?.scaleX(1.0f)
                    ?.scaleY(1.0f)
                    ?.translationY(cloneOffsetYPx + 16f * density)
                    ?.setDuration(380)
                    ?.setInterpolator(AccelerateDecelerateInterpolator())
                    ?.start()

                delay(2200)
                if (!isActive) break

                cloneBubble?.animate()?.alpha(0f)?.scaleX(0.6f)?.scaleY(0.6f)?.setDuration(250)?.start()
                delay(350)
            }
        }
    }

    // ── Slide 4: 100% Offline Privacy Animation ────────────────────────────
    private fun startPrivacyAnimation(view: View) {
        val shield = view.findViewById<View>(R.id.layout_mock_shield) ?: return
        val pulse = view.findViewById<View>(R.id.view_mock_privacy_pulse) ?: return

        currentAnimJob = activityScope.launch {
            while (isActive) {
                shield.animate()
                    .scaleX(1.08f)
                    .scaleY(1.08f)
                    .setDuration(1000)
                    .withEndAction {
                        shield.animate().scaleX(1.0f).scaleY(1.0f).setDuration(1000).start()
                    }
                    .start()

                pulse.animate()
                    .scaleX(1.25f)
                    .scaleY(1.25f)
                    .alpha(0.0f)
                    .setDuration(1200)
                    .withEndAction {
                        pulse.scaleX = 1.0f
                        pulse.scaleY = 1.0f
                        pulse.alpha = 0.35f
                    }
                    .start()

                delay(2200)
            }
        }
    }

    private fun completeOnboarding() {
        ModelConfig.setOnboardingCompleted(this, true)
        ModelConfig.setOnboardingMode(this, ModelConfig.MODE_BUBBLE)

        val intent = Intent(this, ImeSettingsActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (::vpOnboarding.isInitialized) {
            playPageAnimation(vpOnboarding.currentItem)
        }
    }

    override fun onPause() {
        super.onPause()
        stopAllAnimations()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAllAnimations()
        activityScope.cancel()
    }

    // ── ViewPager2 Adapter ──────────────────────────────────────────────────
    private class OnboardingSlideAdapter(private val activity: AppCompatActivity) :
        RecyclerView.Adapter<OnboardingSlideAdapter.SlideViewHolder>() {

        private val layoutIds = intArrayOf(
            R.layout.layout_onboarding_slide_voice,
            R.layout.layout_onboarding_slide_capsule,
            R.layout.layout_onboarding_slide_undo,
            R.layout.layout_onboarding_slide_gestures,
            R.layout.layout_onboarding_slide_privacy
        )

        class SlideViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

        override fun getItemCount(): Int = layoutIds.size

        override fun getItemViewType(position: Int): Int = layoutIds[position]

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlideViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(viewType, parent, false)
            com.ping.verbead.ui.GlassStage.skin(view)
            frostCards(view)
            return SlideViewHolder(view)
        }

        override fun onBindViewHolder(holder: SlideViewHolder, position: Int) {
            if (position == (activity as? OnboardingActivity)?.vpOnboarding?.currentItem) {
                (activity as? OnboardingActivity)?.playPageAnimation(position)
            }
        }
    }
}

private fun frostCards(v: View) {
    if (v is com.google.android.material.card.MaterialCardView) com.ping.verbead.ui.GlassStage.frost(v)
    if (v is ViewGroup) for (i in 0 until v.childCount) frostCards(v.getChildAt(i))
}
