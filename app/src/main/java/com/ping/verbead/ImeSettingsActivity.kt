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
            Toast.makeText(this, getString(R.string.settings_toast_camera_permission), Toast.LENGTH_LONG).show()
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

        // First launch: show the app tour once (marked as seen immediately so back/skip never re-trigger it)
        if (savedInstanceState == null && !ModelConfig.isOnboardingCompleted(this)) {
            ModelConfig.setOnboardingCompleted(this, true)
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
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
                Toast.makeText(this, getString(R.string.settings_toast_quick_zip_not_found), Toast.LENGTH_SHORT).show()
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
        tvImportModelsStatus.text = getString(R.string.settings_import_preparing)
        // 解壓大型模型需要一段時間，避免螢幕休眠中斷
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        lifecycleScope.launch {
            val result = if (file != null) {
                ModelZipInstaller.installFromFile(this@ImeSettingsActivity, file) { currentFile, pct ->
                    runOnUiThread {
                        progressImportModels.progress = pct
                        tvImportModelsStatus.text = importProgressText(currentFile, pct)
                    }
                }
            } else if (uri != null) {
                ModelZipInstaller.installFromUri(this@ImeSettingsActivity, uri) { currentFile, pct ->
                    runOnUiThread {
                        progressImportModels.progress = pct
                        tvImportModelsStatus.text = importProgressText(currentFile, pct)
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
                    status = ModelZipInstaller.Status.INVALID_SOURCE
                )
            }

            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            btnImportModelsZip.isEnabled = true
            btnQuickImportDownload.isEnabled = true
            progressImportModels.visibility = View.GONE

            if (result.isSuccess) {
                tvImportModelsStatus.text = importResultMessage(result)
                updateAllStatus()
                updateCategorySummaries()
                val restoredListText = if (result.restoredModels.isNotEmpty()) {
                    result.restoredModels.joinToString("\n") { "• ${getString(it)}" }
                } else {
                    getString(R.string.settings_import_core_files_item)
                }
                AlertDialog.Builder(this@ImeSettingsActivity)
                    .setTitle(R.string.settings_import_success_title)
                    .setMessage(
                        getString(R.string.settings_import_success_message, result.fileCount, restoredListText)
                    )
                    .setPositiveButton(R.string.settings_import_success_ok, null)
                    .show()
            } else {
                tvImportModelsStatus.text = getString(R.string.toast_dict_import_failed, importResultMessage(result))
                AlertDialog.Builder(this@ImeSettingsActivity)
                    .setTitle(R.string.settings_import_failed_title)
                    .setMessage(getString(R.string.settings_import_failed_message, importResultMessage(result)))
                    .setPositiveButton(R.string.common_got_it, null)
                    .show()
            }
        }
    }

    private fun importProgressText(currentFile: String, pct: Int): String =
        if (currentFile.isEmpty()) {
            getString(R.string.settings_import_finishing)
        } else {
            getString(R.string.settings_import_extracting, currentFile, pct)
        }

    private fun importResultMessage(result: ModelZipInstaller.InstallResult): String = when (result.status) {
        ModelZipInstaller.Status.SUCCESS -> {
            val summary = result.restoredModelShortNames
                .joinToString(getString(R.string.common_list_separator))
                .ifEmpty { getString(R.string.settings_import_no_combo) }
            getString(R.string.settings_import_success_status, summary)
        }
        ModelZipInstaller.Status.NO_MODELS_IN_ARCHIVE -> getString(R.string.settings_import_no_models)
        ModelZipInstaller.Status.STREAM_UNREADABLE -> getString(R.string.settings_import_stream_unreadable)
        ModelZipInstaller.Status.FILE_NOT_FOUND -> getString(R.string.settings_import_file_not_found, result.detail)
        ModelZipInstaller.Status.INSUFFICIENT_SPACE -> getString(R.string.settings_import_insufficient_space, result.detail.toIntOrNull() ?: 0)
        ModelZipInstaller.Status.EXTRACT_FAILED -> getString(R.string.settings_import_extract_failed, result.detail)
        ModelZipInstaller.Status.INVALID_SOURCE -> getString(R.string.settings_import_invalid_source)
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
            tvStatusValMic.text = getString(R.string.settings_status_ready_check)
            tvStatusValMic.setTextColor(successColor)
        } else {
            ivStatusDotMic.setImageResource(R.drawable.bg_icon_circle)
            ivStatusDotMic.setColorFilter(warningColor)
            tvStatusValMic.text = getString(R.string.settings_perm_denied_action)
            tvStatusValMic.setTextColor(warningColor)
            pendingCount++
        }

        // 1b. Camera（文字辨識與條碼掃描）
        if (cameraGranted) {
            ivStatusDotCamera.setImageResource(R.drawable.ic_check_circle)
            ivStatusDotCamera.setColorFilter(successColor)
            tvStatusValCamera.text = getString(R.string.settings_status_ready_check)
            tvStatusValCamera.setTextColor(successColor)
        } else {
            ivStatusDotCamera.setImageResource(R.drawable.bg_icon_circle)
            ivStatusDotCamera.setColorFilter(warningColor)
            tvStatusValCamera.text = getString(R.string.settings_perm_denied_action)
            tvStatusValCamera.setTextColor(warningColor)
            pendingCount++
        }

        // 2. Overlay Permission
        if (overlayGranted) {
            ivStatusDotOverlay.setImageResource(R.drawable.ic_check_circle)
            ivStatusDotOverlay.setColorFilter(successColor)
            tvStatusValOverlay.text = getString(R.string.settings_status_ready_check)
            tvStatusValOverlay.setTextColor(successColor)
        } else {
            ivStatusDotOverlay.setImageResource(R.drawable.bg_icon_circle)
            ivStatusDotOverlay.setColorFilter(warningColor)
            tvStatusValOverlay.text = getString(R.string.settings_status_off_action)
            tvStatusValOverlay.setTextColor(warningColor)
            pendingCount++
        }

        // 3. Accessibility Service
        if (accessibilityEnabled) {
            ivStatusDotAccessibility.setImageResource(R.drawable.ic_check_circle)
            ivStatusDotAccessibility.setColorFilter(successColor)
            tvStatusValAccessibility.text = getString(R.string.settings_status_ready_check)
            tvStatusValAccessibility.setTextColor(successColor)
        } else {
            ivStatusDotAccessibility.setImageResource(R.drawable.bg_icon_circle)
            ivStatusDotAccessibility.setColorFilter(warningColor)
            tvStatusValAccessibility.text = getString(R.string.settings_status_off_action)
            tvStatusValAccessibility.setTextColor(warningColor)
            pendingCount++
        }

        // 4. Speech Model
        if (anyModelReady) {
            ivStatusDotModel.setImageResource(R.drawable.ic_check_circle)
            ivStatusDotModel.setColorFilter(successColor)
            tvStatusValModel.text = getString(R.string.settings_status_ready_check)
            tvStatusValModel.setTextColor(successColor)
        } else {
            ivStatusDotModel.setImageResource(R.drawable.bg_icon_circle)
            ivStatusDotModel.setColorFilter(warningColor)
            tvStatusValModel.text = getString(R.string.settings_perm_not_downloaded_action)
            tvStatusValModel.setTextColor(warningColor)
            pendingCount++
        }

        // Overall Badge
        if (pendingCount == 0) {
            tvOverallBadge.text = getString(R.string.settings_overall_all_ready)
            tvOverallBadge.setTextColor(successColor)
            tvOverallBadge.setBackgroundResource(R.drawable.bg_status_badge_success)
        } else {
            tvOverallBadge.text = getString(R.string.settings_overall_pending, pendingCount)
            tvOverallBadge.setTextColor(warningColor)
            tvOverallBadge.setBackgroundResource(R.drawable.bg_status_badge_warning)
        }
    }

    private fun updateCategorySummaries() {
        // 1. Bubble
        val isRunning = FloatingBubbleService.isRunning
        val runningStatus = getString(if (isRunning) R.string.settings_summary_bubble_running else R.string.settings_summary_bubble_not_running)
        val isMultiBubble = ModelConfig.isMultiBubbleEnabled(this)
        val bubbleModeStr = if (isMultiBubble) {
            val count = ModelConfig.getMultiBubbleCount(this)
            getString(R.string.settings_summary_bubble_multi, count)
        } else {
            getString(R.string.settings_summary_bubble_single)
        }
        val enabledModesCount = ModelConfig.getEnabledBubbleModes(this).size
        tvCatBubbleSummary.text = getString(R.string.settings_summary_bubble, runningStatus, bubbleModeStr, enabledModesCount)

        // 2. Speech
        val dual = ModelConfig.isDualEngineEnabled(this)
        val vad = (ModelConfig.vadSilenceSeconds(this) * 1000).toInt()
        val speechEngineStr = when {
            dual -> getString(R.string.settings_summary_engine_dual)
            ModelConfig.selectedEngine(this) == ModelConfig.ENGINE_QWEN3 -> getString(R.string.settings_summary_engine_qwen)
            else -> getString(R.string.settings_summary_engine_xasr)
        }
        tvCatSpeechSummary.text = "$speechEngineStr · VAD ${vad}ms"

        // 3. OCR
        val ocrEngine = ModelConfig.selectedOcrModel(this)
        val ocrReady = if (ocrEngine == ModelConfig.ENGINE_PP_OCR_SMALL) {
            ModelConfig.isOcrSmallReady(this)
        } else {
            ModelConfig.isOcrTinyReady(this)
        }
        val ocrName = getString(if (ocrEngine == ModelConfig.ENGINE_PP_OCR_SMALL) R.string.ocr_model_name_small else R.string.ocr_model_name_tiny)
        val ocrStatusStr = getString(if (ocrReady) R.string.common_ready else R.string.common_not_downloaded)
        val pangu = getString(if (ModelConfig.isOcrPanguSpacingEnabled(this)) R.string.settings_summary_ocr_pangu else R.string.settings_summary_ocr_compact)
        val trad = getString(if (ModelConfig.isOcrToTraditionalEnabled(this)) R.string.settings_summary_ocr_trad else R.string.settings_summary_ocr_original)
        tvCatOcrSummary.text = "$ocrName ($ocrStatusStr) · $trad · $pangu"

        // 4. Offline Model Package Import (Main page inline)
        val totalCount = 4
        var downloadedCount = 0
        if (ModelConfig.isXAsrReady(this)) downloadedCount++
        if (ModelConfig.isQwen3Ready(this)) downloadedCount++
        if (ModelConfig.isOcrTinyReady(this)) downloadedCount++
        if (ModelConfig.isOcrSmallReady(this)) downloadedCount++
        tvCatBackupSummary.text = getString(R.string.settings_summary_backup, downloadedCount, totalCount)

        val allReady = ModelConfig.areAllModelsReady(this)
        if (allReady) {
            tvAllModelsBadge.text = getString(R.string.settings_all_models_ready)
            tvAllModelsBadge.setTextColor(ContextCompat.getColor(this, R.color.status_success_text))
            tvAllModelsBadge.setBackgroundResource(R.drawable.bg_status_badge_success)
        } else {
            tvAllModelsBadge.text = getString(R.string.settings_backup_status_not_ready)
            tvAllModelsBadge.setTextColor(ContextCompat.getColor(this, R.color.status_warning_text))
            tvAllModelsBadge.setBackgroundResource(R.drawable.bg_status_badge_warning)
        }

        val quickZip = ModelZipInstaller.findDefaultZipPackage(this)
        if (quickZip != null) {
            btnQuickImportDownload.visibility = View.VISIBLE
            val sizeMb = quickZip.length() / (1024 * 1024)
            btnQuickImportDownload.text = getString(R.string.settings_restore_load_internal_size, sizeMb)
        } else {
            btnQuickImportDownload.visibility = View.GONE
        }

        // 6. About
        tvCatAboutSummary.text = getString(R.string.settings_summary_about, BuildConfig.VERSION_NAME)
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
