package com.ping.verbead

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.settings.AboutSettingsActivity
import com.ping.verbead.settings.BackupSettingsActivity
import com.ping.verbead.settings.BubbleSettingsActivity
import com.ping.verbead.settings.OcrSettingsActivity
import com.ping.verbead.settings.SlmSettingsActivity
import com.ping.verbead.settings.SpeechSettingsActivity
import com.ping.verbead.slm.SlmModelConfig
import com.ping.verbead.util.HapticUtil

class ImeSettingsActivity : AppCompatActivity() {

    companion object {
        @Volatile
        var instance: ImeSettingsActivity? = null
            private set
    }

    fun syncMultiBubbleState() {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                updateAllStatus()
                updateCategorySummaries()
            }
        }
    }

    private val bubblePrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ModelConfig.KEY_MULTI_BUBBLE_ENABLED ||
            key == ModelConfig.KEY_MULTI_BUBBLE_COUNT ||
            key == ModelConfig.KEY_BUBBLE_DISABLED_MODES ||
            key == ModelConfig.KEY_BUBBLE_MODE_ORDER
        ) {
            syncMultiBubbleState()
        }
    }

    // Status Indicator
    private lateinit var tvOverallBadge: TextView
    private lateinit var btnOpenOnboarding: MaterialButton

    private lateinit var itemStatusMic: LinearLayout
    private lateinit var ivStatusDotMic: ImageView
    private lateinit var tvStatusValMic: TextView

    private lateinit var itemStatusOverlay: LinearLayout
    private lateinit var ivStatusDotOverlay: ImageView
    private lateinit var tvStatusValOverlay: TextView

    private lateinit var itemStatusAccessibility: LinearLayout
    private lateinit var ivStatusDotAccessibility: ImageView
    private lateinit var tvStatusValAccessibility: TextView

    private lateinit var itemStatusModel: LinearLayout
    private lateinit var ivStatusDotModel: ImageView
    private lateinit var tvStatusValModel: TextView

    // Category Cards
    private lateinit var cardCatBubble: MaterialCardView
    private lateinit var tvCatBubbleSummary: TextView

    private lateinit var cardCatSpeech: MaterialCardView
    private lateinit var tvCatSpeechSummary: TextView

    private lateinit var cardCatOcr: MaterialCardView
    private lateinit var tvCatOcrSummary: TextView

    private lateinit var cardCatSlm: MaterialCardView
    private lateinit var tvCatSlmSummary: TextView

    private lateinit var cardCatBackup: MaterialCardView
    private lateinit var tvCatBackupSummary: TextView

    private lateinit var cardCatAbout: MaterialCardView
    private lateinit var tvCatAboutSummary: TextView

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updateAllStatus()
        updateCategorySummaries()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        setContentView(R.layout.activity_settings)

        getSharedPreferences(ModelConfig.PREF_BUBBLE_SETTINGS, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(bubblePrefsListener)

        bindViews()
        setupListeners()
        updateAllStatus()
        updateCategorySummaries()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updateAllStatus()
        updateCategorySummaries()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            updateAllStatus()
            updateCategorySummaries()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
        getSharedPreferences(ModelConfig.PREF_BUBBLE_SETTINGS, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(bubblePrefsListener)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra("auto_import_default_zip", false) || intent.hasExtra("import_zip_path")) {
            val backupIntent = Intent(this, BackupSettingsActivity::class.java).apply {
                putExtras(intent)
            }
            startActivity(backupIntent)
        }
    }

    private fun bindViews() {
        tvOverallBadge = findViewById(R.id.tv_overall_badge)
        btnOpenOnboarding = findViewById(R.id.btn_open_onboarding)

        itemStatusMic = findViewById(R.id.item_status_mic)
        ivStatusDotMic = findViewById(R.id.iv_status_dot_mic)
        tvStatusValMic = findViewById(R.id.tv_status_val_mic)

        itemStatusOverlay = findViewById(R.id.item_status_overlay)
        ivStatusDotOverlay = findViewById(R.id.iv_status_dot_overlay)
        tvStatusValOverlay = findViewById(R.id.tv_status_val_overlay)

        itemStatusAccessibility = findViewById(R.id.item_status_accessibility)
        ivStatusDotAccessibility = findViewById(R.id.iv_status_dot_accessibility)
        tvStatusValAccessibility = findViewById(R.id.tv_status_val_accessibility)

        itemStatusModel = findViewById(R.id.item_status_model)
        ivStatusDotModel = findViewById(R.id.iv_status_dot_model)
        tvStatusValModel = findViewById(R.id.tv_status_val_model)

        // Category Cards
        cardCatBubble = findViewById(R.id.card_cat_bubble)
        tvCatBubbleSummary = findViewById(R.id.tv_cat_bubble_summary)

        cardCatSpeech = findViewById(R.id.card_cat_speech)
        tvCatSpeechSummary = findViewById(R.id.tv_cat_speech_summary)

        cardCatOcr = findViewById(R.id.card_cat_ocr)
        tvCatOcrSummary = findViewById(R.id.tv_cat_ocr_summary)

        cardCatSlm = findViewById(R.id.card_cat_slm)
        tvCatSlmSummary = findViewById(R.id.tv_cat_slm_summary)

        cardCatBackup = findViewById(R.id.card_cat_backup)
        tvCatBackupSummary = findViewById(R.id.tv_cat_backup_summary)

        cardCatAbout = findViewById(R.id.card_cat_about)
        tvCatAboutSummary = findViewById(R.id.tv_cat_about_summary)
    }

    private fun setupListeners() {
        btnOpenOnboarding.setOnClickListener {
            HapticUtil.click(this)
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        // Diagnostics Quick Clicks
        itemStatusMic.setOnClickListener {
            HapticUtil.click(this)
            val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            if (!micGranted) {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                startActivity(Intent(this, SpeechSettingsActivity::class.java))
            }
        }

        itemStatusOverlay.setOnClickListener {
            HapticUtil.click(this)
            if (!Settings.canDrawOverlays(this)) {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                }
            } else {
                startActivity(Intent(this, BubbleSettingsActivity::class.java))
            }
        }

        itemStatusAccessibility.setOnClickListener {
            HapticUtil.click(this)
            if (!isAccessibilityServiceEnabled()) {
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                } catch (e: Exception) {
                    startActivity(Intent(this, BubbleSettingsActivity::class.java))
                }
            } else {
                startActivity(Intent(this, BubbleSettingsActivity::class.java))
            }
        }

        itemStatusModel.setOnClickListener {
            HapticUtil.click(this)
            val anyModelReady = ModelConfig.isXAsrReady(this) || ModelConfig.isQwen3Ready(this)
            if (!anyModelReady) {
                startActivity(Intent(this, BackupSettingsActivity::class.java))
            } else {
                startActivity(Intent(this, SpeechSettingsActivity::class.java))
            }
        }

        // Category Cards Clicks
        cardCatBubble.setOnClickListener {
            HapticUtil.click(this)
            startActivity(Intent(this, BubbleSettingsActivity::class.java))
        }

        cardCatSpeech.setOnClickListener {
            HapticUtil.click(this)
            startActivity(Intent(this, SpeechSettingsActivity::class.java))
        }

        cardCatOcr.setOnClickListener {
            HapticUtil.click(this)
            startActivity(Intent(this, OcrSettingsActivity::class.java))
        }

        cardCatSlm.setOnClickListener {
            HapticUtil.click(this)
            startActivity(Intent(this, SlmSettingsActivity::class.java))
        }

        cardCatBackup.setOnClickListener {
            HapticUtil.click(this)
            startActivity(Intent(this, BackupSettingsActivity::class.java))
        }

        cardCatAbout.setOnClickListener {
            HapticUtil.click(this)
            startActivity(Intent(this, AboutSettingsActivity::class.java))
        }
    }

    private fun updateAllStatus() {
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val overlayGranted = Settings.canDrawOverlays(this)
        val accessibilityEnabled = isAccessibilityServiceEnabled()

        val xAsrDownloaded = ModelConfig.isXAsrReady(this)
        val qwen3Downloaded = ModelConfig.isQwen3Ready(this)
        val anyModelReady = xAsrDownloaded || qwen3Downloaded

        // Auto ensure FloatingBubbleService runs when permissions are satisfied
        if (overlayGranted && accessibilityEnabled && !FloatingBubbleService.isRunning) {
            FloatingBubbleService.start(this)
        }

        val successColor = ContextCompat.getColor(this, R.color.status_success)
        val warningColor = ContextCompat.getColor(this, R.color.status_warning_text)

        var pendingCount = 0

        // 1. Microphone
        if (micGranted) {
            ivStatusDotMic.setImageResource(R.drawable.ic_check_circle)
            ivStatusDotMic.setColorFilter(successColor)
            tvStatusValMic.text = "已就緒 ✓"
            tvStatusValMic.setTextColor(successColor)
        } else {
            ivStatusDotMic.setImageResource(R.drawable.bg_icon_circle)
            ivStatusDotMic.setColorFilter(warningColor)
            tvStatusValMic.text = "未授權 ➜"
            tvStatusValMic.setTextColor(warningColor)
            pendingCount++
        }

        // 2. Overlay
        if (overlayGranted) {
            ivStatusDotOverlay.setImageResource(R.drawable.ic_check_circle)
            ivStatusDotOverlay.setColorFilter(successColor)
            tvStatusValOverlay.text = "已就緒 ✓"
            tvStatusValOverlay.setTextColor(successColor)
        } else {
            ivStatusDotOverlay.setImageResource(R.drawable.bg_icon_circle)
            ivStatusDotOverlay.setColorFilter(warningColor)
            tvStatusValOverlay.text = "未授權 ➜"
            tvStatusValOverlay.setTextColor(warningColor)
            pendingCount++
        }

        // 3. Accessibility
        if (accessibilityEnabled) {
            ivStatusDotAccessibility.setImageResource(R.drawable.ic_check_circle)
            ivStatusDotAccessibility.setColorFilter(successColor)
            tvStatusValAccessibility.text = "已就緒 ✓"
            tvStatusValAccessibility.setTextColor(successColor)
        } else {
            ivStatusDotAccessibility.setImageResource(R.drawable.bg_icon_circle)
            ivStatusDotAccessibility.setColorFilter(warningColor)
            tvStatusValAccessibility.text = "未開啟 ➜"
            tvStatusValAccessibility.setTextColor(warningColor)
            pendingCount++
        }

        // 4. Speech Model
        if (anyModelReady) {
            ivStatusDotModel.setImageResource(R.drawable.ic_check_circle)
            ivStatusDotModel.setColorFilter(successColor)
            tvStatusValModel.text = "已就緒 ✓"
            tvStatusValModel.setTextColor(successColor)
        } else {
            ivStatusDotModel.setImageResource(R.drawable.bg_icon_circle)
            ivStatusDotModel.setColorFilter(warningColor)
            tvStatusValModel.text = "未下載 ➜"
            tvStatusValModel.setTextColor(warningColor)
            pendingCount++
        }

        // Overall Badge
        if (pendingCount == 0) {
            tvOverallBadge.text = "全部就緒"
            tvOverallBadge.setTextColor(successColor)
            tvOverallBadge.setBackgroundResource(R.drawable.bg_status_badge_success)
        } else {
            tvOverallBadge.text = "待配置 ${pendingCount} 項"
            tvOverallBadge.setTextColor(warningColor)
            tvOverallBadge.setBackgroundResource(R.drawable.bg_status_badge_warning)
        }
    }

    private fun updateCategorySummaries() {
        // 1. Bubble
        val isRunning = FloatingBubbleService.isRunning
        val runningStatus = if (isRunning) "常駐運作中" else "未運行"
        val isMultiBubble = ModelConfig.isMultiBubbleEnabled(this)
        val bubbleModeStr = if (isMultiBubble) {
            val count = ModelConfig.getMultiBubbleCount(this)
            "${count} 球模式"
        } else {
            "單球模式"
        }
        val enabledModesCount = ModelConfig.getEnabledBubbleModes(this).size
        tvCatBubbleSummary.text = "$runningStatus · $bubbleModeStr · 啟用 $enabledModesCount 個模式"

        // 2. Speech
        val dual = ModelConfig.isDualEngineEnabled(this)
        val vad = (ModelConfig.vadSilenceSeconds(this) * 1000).toInt()
        val speechEngineStr = when {
            dual -> "雙引擎切換 (X-ASR + Qwen3)"
            ModelConfig.selectedEngine(this) == ModelConfig.ENGINE_QWEN3 -> "Qwen3 離線高精辨識"
            else -> "X-ASR 繁中串流辨識"
        }
        tvCatSpeechSummary.text = "$speechEngineStr · VAD ${vad}ms"

        // 3. OCR
        val ocrEngine = ModelConfig.selectedOcrModel(this)
        val ocrReady = if (ocrEngine == ModelConfig.ENGINE_PP_OCR_SMALL) {
            ModelConfig.isOcrSmallReady(this)
        } else {
            ModelConfig.isOcrTinyReady(this)
        }
        val ocrName = if (ocrEngine == ModelConfig.ENGINE_PP_OCR_SMALL) "PP-OCRv6 標準版" else "PP-OCRv6 輕量版"
        val ocrStatusStr = if (ocrReady) "已就緒" else "未下載"
        val pangu = if (ModelConfig.isOcrPanguSpacingEnabled(this)) "盤古空格" else "緊湊排版"
        tvCatOcrSummary.text = "$ocrName ($ocrStatusStr) · 條碼掃描 · $pangu"

        // 4. SLM
        val slmModelId = SlmModelConfig.getSelectedModel(this)
        val slmMeta = SlmModelConfig.AVAILABLE_MODELS.find { it.id == slmModelId }
        val slmReady = SlmModelConfig.isModelDownloaded(this, slmModelId)
        val slmName = slmMeta?.displayName ?: "Qwen 2B"
        val slmStatusStr = if (slmReady) "已就緒" else "未下載"
        val thinkingStr = if (SlmModelConfig.isThinkingEnabled(this)) "思考模式開" else "思考模式關"
        tvCatSlmSummary.text = "$slmName ($slmStatusStr) · $thinkingStr · 智慧改寫"

        // 5. Backup
        val totalCount = 4 + SlmModelConfig.AVAILABLE_MODELS.size
        var downloadedCount = 0
        if (ModelConfig.isXAsrReady(this)) downloadedCount++
        if (ModelConfig.isQwen3Ready(this)) downloadedCount++
        if (ModelConfig.isOcrTinyReady(this)) downloadedCount++
        if (ModelConfig.isOcrSmallReady(this)) downloadedCount++
        SlmModelConfig.AVAILABLE_MODELS.forEach {
            if (SlmModelConfig.isModelDownloaded(this, it.id)) downloadedCount++
        }
        tvCatBackupSummary.text = "已下載 $downloadedCount/$totalCount 個離線模型 · 支援 ZIP 導入"

        // 6. About
        tvCatAboutSummary.text = "版本 ${BuildConfig.VERSION_NAME} · 開源技術致謝與授權"
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        if (VoiceAccessibilityService.isServiceRunning()) return true
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(packageName)
    }
}
