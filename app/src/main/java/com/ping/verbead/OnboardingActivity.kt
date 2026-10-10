package com.ping.verbead

import android.animation.AnimatorSet
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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

        initViews()
        setupViewPager()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (vpOnboarding.currentItem > 0) {
                    vpOnboarding.currentItem = vpOnboarding.currentItem - 1
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
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
            btnNext.text = "前往設定開始使用"
        } else {
            btnNext.text = "下一步"
        }

        // Indicator dots
        val activeWidth = (20 * resources.displayMetrics.density).toInt()
        val inactiveWidth = (6 * resources.displayMetrics.density).toInt()
        for (i in dots.indices) {
            val dot = dots[i]
            val params = dot.layoutParams
            if (i == position) {
                params.width = activeWidth
                dot.setBackgroundResource(R.drawable.bg_indicator_dot_active)
            } else {
                params.width = inactiveWidth
                dot.setBackgroundResource(R.drawable.bg_indicator_dot_inactive)
            }
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
        val previewPill = view.findViewById<View>(R.id.layout_mock_preview_pill)
        val wave1 = view.findViewById<View>(R.id.mock_wave_1)
        val wave2 = view.findViewById<View>(R.id.mock_wave_2)
        val wave3 = view.findViewById<View>(R.id.mock_wave_3)

        currentAnimJob = activityScope.launch {
            val sampleText = "今天下午兩點在三樓會議室開會。"
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
        val tvModeTitle = view.findViewById<TextView>(R.id.tv_mock_active_mode_title)
        val tvModeDesc = view.findViewById<TextView>(R.id.tv_mock_active_mode_desc)
        val ivModeIcon = view.findViewById<ImageView>(R.id.iv_mock_active_mode_icon)

        val density = resources.displayMetrics.density
        val itemPitchPx = 56f * density

        currentAnimJob = activityScope.launch {
            var modeIndex = 0
            while (isActive) {
                // Dragging the capsule: bringing item modeIndex to center anchor
                // Item 0 is at offset 0, Item 1 requires translating up by -56dp, Item 2 by -112dp
                val targetTranslationY = -modeIndex * itemPitchPx
                capsulePill.animate()
                    .translationY(targetTranslationY)
                    .setDuration(450)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()

                when (modeIndex) {
                    0 -> {
                        tvModeTitle?.text = "語音即時輸入"
                        tvModeDesc?.text = "高精準度離線語音辨識，輕觸即開始聽寫"
                        ivModeIcon?.setImageResource(R.drawable.ic_mic)
                    }
                    1 -> {
                        tvModeTitle?.text = "條碼與 QR 掃描"
                        tvModeDesc?.text = "極速本機掃瞄，瞄準即讀取並自動填入"
                        ivModeIcon?.setImageResource(R.drawable.ic_barcode)
                    }
                    2 -> {
                        tvModeTitle?.text = "螢幕文字辨識 (OCR)"
                        tvModeDesc?.text = "凍結畫面框選文字，離線高精度字元辨識"
                        ivModeIcon?.setImageResource(R.drawable.ic_ocr)
                    }
                }

                delay(2200)
                modeIndex = (modeIndex + 1) % 3
            }
        }
    }

    // ── Slide 2: Undo Input via Small X Button Mockup Animation ─────────────
    private fun startUndoAnimation(view: View) {
        val tvInput = view.findViewById<TextView>(R.id.tv_mock_undo_input) ?: return
        val btnX = view.findViewById<View>(R.id.btn_mock_undo_x) ?: return
        val tvToast = view.findViewById<TextView>(R.id.tv_mock_undo_toast)
        val finger = view.findViewById<View>(R.id.view_mock_undo_finger)

        val fullText = "今天開會時間是兩點整。"

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

    // ── Slide 3: 70% Edge Tucking & Keyboard Linkage Animation ───────────────
    private fun startTuckAndKeyboardAnimation(view: View) {
        val bubble = view.findViewById<View>(R.id.layout_mock_tuck_bubble) ?: return
        val keyboard = view.findViewById<View>(R.id.layout_mock_keyboard) ?: return
        val tvTitle = view.findViewById<TextView>(R.id.tv_mock_tuck_status_title)
        val tvDesc = view.findViewById<TextView>(R.id.tv_mock_tuck_status_desc)

        val density = resources.displayMetrics.density
        // Bubble marginEnd is 12dp inside screen. Bubble width is 52dp.
        // Translating right by (12dp + 36dp) = 48dp pushes 36dp (70%) outside and leaves 16dp (30%) visible!
        val tuckHiddenX = 48f * density
        val keyboardHeightPx = 90f * density

        currentAnimJob = activityScope.launch {
            while (isActive) {
                // Phase 1: Edge Tuck (70% outside, 30% visible)
                tvTitle?.text = "1. 邊緣收納：超出邊界 70%"
                tvDesc?.text = "自動藏入螢幕邊緣，僅露 30% 圓弧，輕點即可拉出"

                keyboard.translationY = keyboardHeightPx
                bubble.alpha = 1.0f
                bubble.animate()
                    .translationX(tuckHiddenX)
                    .translationY(0f)
                    .setDuration(700)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()

                delay(2600)
                if (!isActive) break

                // Phase 2: Keyboard Linkage (Bubble fades in above keyboard)
                tvTitle?.text = "2. 鍵盤聯動：僅打字時現身"
                tvDesc?.text = "主設定開啟「僅軟體鍵盤出現時顯示」，伴隨鍵盤升起淡入出現"

                // Untuck and prepare above keyboard
                bubble.translationX = 0f
                bubble.translationY = -keyboardHeightPx / 2f
                bubble.alpha = 0f

                // Keyboard slides up, bubble fades in
                keyboard.animate()
                    .translationY(0f)
                    .setDuration(350)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()

                bubble.animate()
                    .alpha(1.0f)
                    .setDuration(350)
                    .start()

                delay(2400)
                if (!isActive) break

                // Keyboard slides down, bubble fades out
                keyboard.animate()
                    .translationY(keyboardHeightPx)
                    .setDuration(350)
                    .start()

                bubble.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .start()

                delay(800)
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
            return SlideViewHolder(view)
        }

        override fun onBindViewHolder(holder: SlideViewHolder, position: Int) {
            if (position == (activity as? OnboardingActivity)?.vpOnboarding?.currentItem) {
                (activity as? OnboardingActivity)?.playPageAnimation(position)
            }
        }
    }
}
