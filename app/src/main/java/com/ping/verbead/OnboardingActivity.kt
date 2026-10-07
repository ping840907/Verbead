package com.ping.verbead

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.ping.verbead.engine.ModelConfig
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
 * Explains core features (Voice typing, vertical capsule menu, edge docking & dismiss gesture, 100% offline privacy)
 * using lightweight visual mockups and animations, guiding the user to main settings.
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
            findViewById(R.id.dot_3)
        )

        btnSkip.setOnClickListener {
            completeOnboarding()
        }

        btnPrev.setOnClickListener {
            if (vpOnboarding.currentItem > 0) {
                vpOnboarding.currentItem = vpOnboarding.currentItem - 1
            }
        }

        btnNext.setOnClickListener {
            if (vpOnboarding.currentItem < 3) {
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
        if (position == 3) {
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
            2 -> startGesturesAnimation(view)
            3 -> startPrivacyAnimation(view)
        }
    }

    private fun stopAllAnimations() {
        currentAnimJob?.cancel()
        currentAnimJob = null
        currentAnimatorSet?.cancel()
        currentAnimatorSet = null
    }

    // ── Slide 0: Voice Typing Mockup Animation ─────────────────────────────
    private fun startVoiceTypingAnimation(view: View) {
        val tvMockInput = view.findViewById<TextView>(R.id.tv_mock_voice_input) ?: return
        val viewCursor = view.findViewById<View>(R.id.view_mock_cursor)
        val rippleView = view.findViewById<View>(R.id.view_mock_bubble_ripple)
        val previewPill = view.findViewById<View>(R.id.layout_mock_preview_pill)
        val wave1 = view.findViewById<View>(R.id.mock_wave_1)
        val wave2 = view.findViewById<View>(R.id.mock_wave_2)
        val wave3 = view.findViewById<View>(R.id.mock_wave_3)

        // Waveform bounce
        currentAnimJob = activityScope.launch {
            val sampleText = "今天下午兩點在三樓會議室開會。"
            while (isActive) {
                // Initial state
                tvMockInput.text = ""
                previewPill?.alpha = 0f
                rippleView?.alpha = 0f

                delay(400)
                // Pulse ripple & show preview pill
                previewPill?.animate()?.alpha(1f)?.setDuration(300)?.start()
                rippleView?.animate()?.alpha(0.5f)?.scaleX(1.35f)?.scaleY(1.35f)?.setDuration(400)?.withEndAction {
                    rippleView.animate()?.alpha(0f)?.setDuration(300)?.start()
                }?.start()

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

                viewCursor?.visibility = View.VISIBLE
                previewPill?.animate()?.alpha(0f)?.setDuration(400)?.start()

                // Pause before restarting demonstration
                delay(2400)
            }
        }
    }

    // ── Slide 1: Capsule Menu Mockup Animation ─────────────────────────────
    private fun startCapsuleMenuAnimation(view: View) {
        val highlight = view.findViewById<View>(R.id.view_mock_capsule_highlight) ?: return
        val finger = view.findViewById<View>(R.id.view_mock_touch_finger) ?: return
        val tvModeTitle = view.findViewById<TextView>(R.id.tv_mock_active_mode_title)
        val tvModeDesc = view.findViewById<TextView>(R.id.tv_mock_active_mode_desc)
        val ivModeIcon = view.findViewById<ImageView>(R.id.iv_mock_active_mode_icon)

        val density = resources.displayMetrics.density
        val stepPx = 56f * density

        currentAnimJob = activityScope.launch {
            var modeIndex = 0
            while (isActive) {
                val targetY = modeIndex * stepPx
                highlight.animate().translationY(targetY).setDuration(400).setInterpolator(AccelerateDecelerateInterpolator()).start()
                finger.animate().translationY(targetY).setDuration(400).setInterpolator(AccelerateDecelerateInterpolator()).start()

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

                delay(2000)
                modeIndex = (modeIndex + 1) % 3
            }
        }
    }

    // ── Slide 2: Gestures Mockup Animation ─────────────────────────────────
    private fun startGesturesAnimation(view: View) {
        val bubble = view.findViewById<View>(R.id.layout_mock_gesture_bubble) ?: return
        val dismissTarget = view.findViewById<View>(R.id.btn_mock_dismiss_circle) ?: return
        val tvStatusTitle = view.findViewById<TextView>(R.id.tv_mock_gesture_status_title)
        val tvStatusDesc = view.findViewById<TextView>(R.id.tv_mock_gesture_status_desc)

        val density = resources.displayMetrics.density
        val edgeDistanceX = 105f * density
        val dismissDistanceY = 120f * density

        currentAnimJob = activityScope.launch {
            while (isActive) {
                // Phase 1: Snap to right edge & dim
                tvStatusTitle?.text = "手勢 1：貼邊半透明收納"
                tvStatusDesc?.text = "將懸浮球移至螢幕左右邊緣，自動吸附並淡化為半透明標籤"

                bubble.animate()
                    .translationX(edgeDistanceX)
                    .translationY(0f)
                    .alpha(0.4f)
                    .scaleX(0.9f)
                    .scaleY(0.9f)
                    .setDuration(700)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()

                delay(2200)
                if (!isActive) break

                // Phase 2: Drag down to dismiss target (X)
                tvStatusTitle?.text = "手勢 2：向下拖曳關閉"
                tvStatusDesc?.text = "拖曳至螢幕底部的 ✕ 區域，即可立即收合懸浮球"

                bubble.animate()
                    .translationX(0f)
                    .translationY(dismissDistanceY)
                    .alpha(1.0f)
                    .scaleX(0.85f)
                    .scaleY(0.85f)
                    .setDuration(800)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()

                delay(750)
                if (!isActive) break

                // Dismiss circle pulse
                dismissTarget.animate()
                    .scaleX(1.2f)
                    .scaleY(1.2f)
                    .setDuration(250)
                    .withEndAction {
                        dismissTarget.animate().scaleX(1.0f).scaleY(1.0f).setDuration(250).start()
                    }
                    .start()

                delay(1800)
                if (!isActive) break

                // Reset back to center
                bubble.animate()
                    .translationX(0f)
                    .translationY(0f)
                    .alpha(1.0f)
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(600)
                    .start()

                delay(900)
            }
        }
    }

    // ── Slide 3: 100% Offline Privacy Animation ────────────────────────────
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
            // Bind callback trigger
            if (position == (activity as? OnboardingActivity)?.vpOnboarding?.currentItem) {
                (activity as? OnboardingActivity)?.playPageAnimation(position)
            }
        }
    }
}
