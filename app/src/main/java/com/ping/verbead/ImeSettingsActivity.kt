package com.ping.verbead

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.engine.ModelZipInstaller
import com.ping.verbead.settings.AboutSettingsActivity
import com.ping.verbead.settings.BubbleSettingsActivity
import com.ping.verbead.settings.OcrSettingsActivity
import com.ping.verbead.settings.SpeechSettingsActivity
import com.ping.verbead.util.HapticUtil
import kotlinx.coroutines.launch
import java.io.File

class ImeSettingsActivity : AppCompatActivity() {

    companion object {
        @Volatile
        var instance: ImeSettingsActivity? = null
            private set
    }

    fun syncMultiBubbleState() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            // 頁面不在前景時不必重算（狀態檢查會讀檔案與系統設定，在主執行緒做會拖慢懸浮球的手勢）；
            // 回到前景時 onResume 會整頁更新
            if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return@runOnUiThread
            updateAllStatus()
            updateCategorySummaries()
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

    private lateinit var itemStatusCamera: LinearLayout
    private lateinit var ivStatusDotCamera: ImageView
    private lateinit var tvStatusValCamera: TextView

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


    // Inline Offline Model Package Import
    private lateinit var cardImportModels: MaterialCardView
    private lateinit var tvCatBackupSummary: TextView
    private lateinit var tvAllModelsBadge: TextView
    private lateinit var progressImportModels: LinearProgressIndicator
    private lateinit var tvImportModelsStatus: TextView
    private lateinit var btnImportModelsZip: MaterialButton
    private lateinit var btnQuickImportDownload: MaterialButton

    private lateinit var cardCatAbout: MaterialCardView
    private lateinit var tvCatAboutSummary: TextView

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updateAllStatus()
        updateCategorySummaries()
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updateAllStatus()
        updateCategorySummaries()
        // 系統不再跳出授權視窗（曾選「不再詢問」）時，只能請使用者到系統設定開啟
        if (!granted && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            Toast.makeText(this, "請在系統設定中開啟相機權限", Toast.LENGTH_LONG).show()
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                // 沒有可處理的設定頁就維持提示文字即可
            }
        }
    }

    private val pickZipFileLauncher = registerForActivityResult(
        object : ActivityResultContracts.OpenDocument() {
            override fun createIntent(context: Context, input: Array<String>): Intent {
                val intent = super.createIntent(context, input)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val downloadUri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")
                    intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadUri)
                }
                return intent
            }
        }
    ) { uri: Uri? ->
        uri?.let {
            runModelPackageImport(uri = it)
        }
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
        if (intent.getBooleanExtra("auto_import_default_zip", false)) {
            val quickFile = ModelZipInstaller.findDefaultZipPackage(this)
            if (quickFile != null) {
                runModelPackageImport(file = quickFile)
            }
        } else if (intent.hasExtra("import_zip_path")) {
            val path = intent.getStringExtra("import_zip_path")
            if (!path.isNullOrEmpty()) {
                val f = File(path)
                if (f.exists()) {
                    runModelPackageImport(file = f)
                }
            }
        }
    }

    private fun bindViews() {
        tvOverallBadge = findViewById(R.id.tv_overall_badge)
        btnOpenOnboarding = findViewById(R.id.btn_open_onboarding)

        itemStatusMic = findViewById(R.id.item_status_mic)
        ivStatusDotMic = findViewById(R.id.iv_status_dot_mic)
        tvStatusValMic = findViewById(R.id.tv_status_val_mic)

        itemStatusCamera = findViewById(R.id.item_status_camera)
        ivStatusDotCamera = findViewById(R.id.iv_status_dot_camera)
        tvStatusValCamera = findViewById(R.id.tv_status_val_camera)

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


        // Inline Model Package Import
        cardImportModels = findViewById(R.id.card_import_models)
        tvCatBackupSummary = findViewById(R.id.tv_cat_backup_summary)
        tvAllModelsBadge = findViewById(R.id.tv_all_models_badge)
        progressImportModels = findViewById(R.id.progress_import_models)
        tvImportModelsStatus = findViewById(R.id.tv_import_models_status)
        btnImportModelsZip = findViewById(R.id.btn_import_models_zip)
        btnQuickImportDownload = findViewById(R.id.btn_quick_import_download)

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

        itemStatusCamera.setOnClickListener {
            HapticUtil.click(this)
            val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
            if (!cameraGranted) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                startActivity(Intent(this, OcrSettingsActivity::class.java))
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
                // Focus on inline model package import or open speech settings
                startActivity(Intent(this, SpeechSettingsActivity::class.java))
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

        // Inline Offline Model Package Import Listeners
        btnImportModelsZip.setOnClickListener {
            HapticUtil.click(this)
            try {
                pickZipFileLauncher.launch(
                    arrayOf(
                        "application/zip",
                        "application/x-zip-compressed",
                        "application/octet-stream"
                    )
                )
            } catch (ex: Exception) {
                Toast.makeText(this, getString(R.string.toast_file_picker_error, ex.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }

        btnQuickImportDownload.setOnClickListener {
            HapticUtil.click(this)
            val quickFile = ModelZipInstaller.findDefaultZipPackage(this)
            if (quickFile != null) {
                runModelPackageImport(file = quickFile)
            } else {
                Toast.makeText(this, "未在 Download 目錄中找到預設模型 ZIP 包", Toast.LENGTH_SHORT).show()
            }
        }

        cardCatAbout.setOnClickListener {
            HapticUtil.click(this)
            startActivity(Intent(this, AboutSettingsActivity::class.java))
        }
    }

    private fun runModelPackageImport(file: File? = null, uri: Uri? = null) {
        btnImportModelsZip.isEnabled = false
        btnQuickImportDownload.isEnabled = false
        progressImportModels.visibility = View.VISIBLE
        progressImportModels.progress = 0
        tvImportModelsStatus.visibility = View.VISIBLE
        tvImportModelsStatus.text = "正在準備解壓模型包…"

        lifecycleScope.launch {
            val result = if (file != null) {
                ModelZipInstaller.installFromFile(this@ImeSettingsActivity, file) { currentFile, pct ->
                    runOnUiThread {
                        progressImportModels.progress = pct
                        tvImportModelsStatus.text = "解壓中：$currentFile ($pct%)"
                    }
                }
            } else if (uri != null) {
                ModelZipInstaller.installFromUri(this@ImeSettingsActivity, uri) { currentFile, pct ->
                    runOnUiThread {
                        progressImportModels.progress = pct
                        tvImportModelsStatus.text = "解壓中：$currentFile ($pct%)"
                    }
                }
            } else {
                ModelZipInstaller.InstallResult(
                    isSuccess = false,
                    xAsrReady = false,
                    qwen3Ready = false,
                    ocrReady = false,
                    fileCount = 0,
                    totalBytes = 0,
                    message = "無效的檔案來源"
                )
            }

            btnImportModelsZip.isEnabled = true
            btnQuickImportDownload.isEnabled = true
            progressImportModels.visibility = View.GONE

            if (result.isSuccess) {
                tvImportModelsStatus.text = result.message
                updateAllStatus()
                updateCategorySummaries()
                val restoredListText = if (result.restoredModels.isNotEmpty()) {
                    result.restoredModels.joinToString("\n") { "• $it" }
                } else {
                    "• 離線模型核心檔案"
                }
                AlertDialog.Builder(this@ImeSettingsActivity)
                    .setTitle("離線模型一鍵復原成功")
                    .setMessage(
                        "已根據導入包內容智慧辨識並復原共 ${result.fileCount} 個模型核心檔案！\n\n" +
                                "本次復原就緒項目：\n$restoredListText\n\n" +
                                "所有對應功能均已自動啟用，可立即使用。"
                    )
                    .setPositiveButton("太棒了", null)
                    .show()
            } else {
                tvImportModelsStatus.text = "匯入失敗：${result.message}"
                AlertDialog.Builder(this@ImeSettingsActivity)
                    .setTitle("導入失敗")
                    .setMessage("無法順利解壓或安裝此模型包：\n${result.message}")
                    .setPositiveButton("我知道了", null)
                    .show()
            }
        }
    }

    private fun updateAllStatus() {
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
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

        // 1b. Camera（文字辨識與條碼掃描）
        if (cameraGranted) {
            ivStatusDotCamera.setImageResource(R.drawable.ic_check_circle)
            ivStatusDotCamera.setColorFilter(successColor)
            tvStatusValCamera.text = "已就緒 ✓"
            tvStatusValCamera.setTextColor(successColor)
        } else {
            ivStatusDotCamera.setImageResource(R.drawable.bg_icon_circle)
            ivStatusDotCamera.setColorFilter(warningColor)
            tvStatusValCamera.text = "未授權 ➜"
            tvStatusValCamera.setTextColor(warningColor)
            pendingCount++
        }

        // 2. Overlay Permission
        if (overlayGranted) {
            ivStatusDotOverlay.setImageResource(R.drawable.ic_check_circle)
            ivStatusDotOverlay.setColorFilter(successColor)
            tvStatusValOverlay.text = "已就緒 ✓"
            tvStatusValOverlay.setTextColor(successColor)
        } else {
            ivStatusDotOverlay.setImageResource(R.drawable.bg_icon_circle)
            ivStatusDotOverlay.setColorFilter(warningColor)
            tvStatusValOverlay.text = "未開啟 ➜"
            tvStatusValOverlay.setTextColor(warningColor)
            pendingCount++
        }

        // 3. Accessibility Service
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
        val trad = if (ModelConfig.isOcrToTraditionalEnabled(this)) "繁中" else "原字"
        tvCatOcrSummary.text = "$ocrName ($ocrStatusStr) · $trad · $pangu"

        // 4. Offline Model Package Import (Main page inline)
        val totalCount = 4
        var downloadedCount = 0
        if (ModelConfig.isXAsrReady(this)) downloadedCount++
        if (ModelConfig.isQwen3Ready(this)) downloadedCount++
        if (ModelConfig.isOcrTinyReady(this)) downloadedCount++
        if (ModelConfig.isOcrSmallReady(this)) downloadedCount++
        tvCatBackupSummary.text = "已下載 $downloadedCount/$totalCount 個離線模型 · 支援 ZIP 導入"

        val allReady = ModelConfig.areAllModelsReady(this)
        if (allReady) {
            tvAllModelsBadge.text = "全部模型已就緒"
            tvAllModelsBadge.setTextColor(ContextCompat.getColor(this, R.color.status_success_text))
            tvAllModelsBadge.setBackgroundResource(R.drawable.bg_status_badge_success)
        } else {
            tvAllModelsBadge.text = "未完全就緒"
            tvAllModelsBadge.setTextColor(ContextCompat.getColor(this, R.color.status_warning_text))
            tvAllModelsBadge.setBackgroundResource(R.drawable.bg_status_badge_warning)
        }

        val quickZip = ModelZipInstaller.findDefaultZipPackage(this)
        if (quickZip != null) {
            btnQuickImportDownload.visibility = View.VISIBLE
            val sizeMb = quickZip.length() / (1024 * 1024)
            btnQuickImportDownload.text = "快速載入內部儲存模型包 (${sizeMb}MB)"
        } else {
            btnQuickImportDownload.visibility = View.GONE
        }

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
