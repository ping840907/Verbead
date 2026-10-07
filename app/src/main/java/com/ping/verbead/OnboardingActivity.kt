package com.ping.verbead

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.engine.ModelDownloadState
import com.ping.verbead.engine.ModelZipInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class OnboardingActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null
    private var pendingDownloadEngine: String? = null

    private var currentStep = 1
    private var selectedMode = ModelConfig.MODE_BUBBLE

    // Top & Navigation Views
    private lateinit var btnSkip: MaterialButton
    private lateinit var progressSteps: LinearProgressIndicator
    private lateinit var scrollContent: View
    private lateinit var btnPrev: MaterialButton
    private lateinit var tvStepLabel: TextView
    private lateinit var btnNext: MaterialButton

    // Step Containers
    private lateinit var step1Container: LinearLayout
    private lateinit var step2Container: LinearLayout
    private lateinit var step3Container: LinearLayout
    private lateinit var step4Container: LinearLayout
    private lateinit var step5Container: LinearLayout

    // Step 1 Views
    private lateinit var tvMicStatus: TextView
    private lateinit var btnGrantMic: MaterialButton
    private lateinit var tvCameraStatus: TextView
    private lateinit var btnGrantCamera: MaterialButton

    // Step 2 Views
    private lateinit var cardModeBubble: MaterialCardView
    private lateinit var ivModeBubbleCheck: ImageView

    // Step 3 Bubble Views
    private lateinit var layoutStep3BubbleGroup: LinearLayout
    private lateinit var tvOverlayStatus: TextView
    private lateinit var btnGrantOverlay: MaterialButton
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnGrantAccessibility: MaterialButton
    private lateinit var btnOpenAppDetails: MaterialButton

    // Step 4 Views
    private lateinit var tvXasrBadge: TextView
    private lateinit var progressXasr: LinearProgressIndicator
    private lateinit var tvStatusXasr: TextView
    private lateinit var btnDownloadXasr: MaterialButton

    private lateinit var tvQwen3Badge: TextView
    private lateinit var progressQwen3: LinearProgressIndicator
    private lateinit var tvStatusQwen3: TextView
    private lateinit var btnDownloadQwen3: MaterialButton

    private lateinit var tvOcrBadge: TextView
    private lateinit var btnOcrTiny: MaterialButton
    private lateinit var btnOcrSmall: MaterialButton
    private lateinit var progressOcr: LinearProgressIndicator
    private lateinit var tvStatusOcr: TextView
    private lateinit var btnDownloadOcr: MaterialButton

    // Step 4 Model Package ZIP Import
    private lateinit var tvOnboardingAllModelsBadge: TextView
    private lateinit var progressOnboardingImportModels: LinearProgressIndicator
    private lateinit var tvOnboardingImportStatus: TextView
    private lateinit var btnOnboardingQuickImportDownload: MaterialButton
    private lateinit var btnOnboardingImportZip: MaterialButton

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

    // Step 5 Views
    private lateinit var layoutQwen3Preferences: LinearLayout
    private lateinit var cardXasrOnlyNotice: MaterialCardView
    private lateinit var btnVadQuick: MaterialButton
    private lateinit var btnVadNormal: MaterialButton
    private lateinit var btnVadRelaxed: MaterialButton
    private lateinit var switchPunctuation: MaterialSwitch
    private lateinit var switchOcrAutoEnter: MaterialSwitch
    private lateinit var etTest: EditText
    private lateinit var btnFinish: MaterialButton

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updateStep1Status()
    }

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updateStep1Status()
    }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pendingDownloadEngine?.let { engine ->
            ModelDownloadService.start(this, engine)
            pendingDownloadEngine = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        bindViews()
        setupListeners()
        updateModeSelection(ModelConfig.getOnboardingMode(this))
        renderStep(1)
        startObservingDownloads()
    }

    override fun onDestroy() {
        super.onDestroy()
        observeJob?.cancel()
    }

    override fun onResume() {
        super.onResume()
        updateAllStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            updateAllStatus()
        }
    }

    private fun bindViews() {
        btnSkip       = findViewById(R.id.btn_onboarding_skip)
        progressSteps = findViewById(R.id.progress_onboarding_steps)
        scrollContent = findViewById(R.id.scroll_onboarding_content)
        btnPrev       = findViewById(R.id.btn_onboarding_prev)
        tvStepLabel   = findViewById(R.id.tv_onboarding_step_label)
        btnNext       = findViewById(R.id.btn_onboarding_next)

        step1Container = findViewById(R.id.step_1_container)
        step2Container = findViewById(R.id.step_2_container)
        step3Container = findViewById(R.id.step_3_container)
        step4Container = findViewById(R.id.step_4_container)
        step5Container = findViewById(R.id.step_5_container)

        // Step 1
        tvMicStatus     = findViewById(R.id.tv_onboarding_mic_status)
        btnGrantMic     = findViewById(R.id.btn_onboarding_grant_mic)
        tvCameraStatus  = findViewById(R.id.tv_onboarding_camera_status)
        btnGrantCamera  = findViewById(R.id.btn_onboarding_grant_camera)

        // Step 2
        cardModeBubble      = findViewById(R.id.card_mode_bubble)
        ivModeBubbleCheck   = findViewById(R.id.iv_mode_bubble_check)

        // Step 3
        layoutStep3BubbleGroup   = findViewById(R.id.layout_step3_bubble_group)
        tvOverlayStatus          = findViewById(R.id.tv_onboarding_overlay_status)
        btnGrantOverlay          = findViewById(R.id.btn_onboarding_grant_overlay)
        tvAccessibilityStatus    = findViewById(R.id.tv_onboarding_accessibility_status)
        btnGrantAccessibility    = findViewById(R.id.btn_onboarding_grant_accessibility)
        btnOpenAppDetails        = findViewById(R.id.btn_onboarding_open_app_details)

        // Step 4
        tvXasrBadge      = findViewById(R.id.tv_onboarding_xasr_badge)
        progressXasr     = findViewById(R.id.progress_onboarding_xasr)
        tvStatusXasr     = findViewById(R.id.tv_onboarding_status_xasr)
        btnDownloadXasr  = findViewById(R.id.btn_onboarding_download_xasr)

        tvQwen3Badge     = findViewById(R.id.tv_onboarding_qwen3_badge)
        progressQwen3    = findViewById(R.id.progress_onboarding_qwen3)
        tvStatusQwen3    = findViewById(R.id.tv_onboarding_status_qwen3)
        btnDownloadQwen3 = findViewById(R.id.btn_onboarding_download_qwen3)

        tvOcrBadge       = findViewById(R.id.tv_onboarding_ocr_badge)
        btnOcrTiny       = findViewById(R.id.btn_onboarding_ocr_tiny)
        btnOcrSmall      = findViewById(R.id.btn_onboarding_ocr_small)
        progressOcr      = findViewById(R.id.progress_onboarding_ocr)
        tvStatusOcr      = findViewById(R.id.tv_onboarding_status_ocr)
        btnDownloadOcr   = findViewById(R.id.btn_onboarding_download_ocr)

        // Step 4 Model Package ZIP Import
        tvOnboardingAllModelsBadge        = findViewById(R.id.tv_onboarding_all_models_badge)
        progressOnboardingImportModels    = findViewById(R.id.progress_onboarding_import_models)
        tvOnboardingImportStatus          = findViewById(R.id.tv_onboarding_import_status)
        btnOnboardingQuickImportDownload  = findViewById(R.id.btn_onboarding_quick_import_download)
        btnOnboardingImportZip            = findViewById(R.id.btn_onboarding_import_zip)

        // Step 5
        layoutQwen3Preferences = findViewById(R.id.layout_qwen3_preferences)
        cardXasrOnlyNotice     = findViewById(R.id.card_xasr_only_notice)
        btnVadQuick            = findViewById(R.id.btn_vad_quick)
        btnVadNormal           = findViewById(R.id.btn_vad_normal)
        btnVadRelaxed          = findViewById(R.id.btn_vad_relaxed)
        switchPunctuation      = findViewById(R.id.switch_onboarding_punctuation)
        switchOcrAutoEnter     = findViewById(R.id.switch_onboarding_ocr_auto_enter)
        etTest                 = findViewById(R.id.et_onboarding_test)
        btnFinish              = findViewById(R.id.btn_onboarding_finish)
    }

    private fun setupListeners() {
        // Top & Bottom Navigation
        btnSkip.setOnClickListener {
            completeOnboarding()
        }

        btnPrev.setOnClickListener {
            if (currentStep > 1) {
                renderStep(currentStep - 1)
            }
        }

        btnNext.setOnClickListener {
            if (currentStep < 5) {
                if (currentStep == 1 && !hasMicPermission()) {
                    Toast.makeText(this, R.string.toast_mic_permission_required, Toast.LENGTH_SHORT).show()
                    requestMic.launch(Manifest.permission.RECORD_AUDIO)
                    return@setOnClickListener
                }
                renderStep(currentStep + 1)
            } else {
                completeOnboarding()
            }
        }

        // Step 1: Mic & Camera
        btnGrantMic.setOnClickListener {
            if (!hasMicPermission()) {
                requestMic.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                Toast.makeText(this, R.string.toast_mic_permission_ready, Toast.LENGTH_SHORT).show()
            }
        }

        btnGrantCamera.setOnClickListener {
            if (!hasCameraPermission()) {
                requestCamera.launch(Manifest.permission.CAMERA)
            } else {
                Toast.makeText(this, R.string.toast_camera_permission_ready, Toast.LENGTH_SHORT).show()
            }
        }

        // Step 2: Mode Selection
        cardModeBubble.setOnClickListener {
            updateModeSelection(ModelConfig.MODE_BUBBLE)
        }

        // Step 3: Bubble Actions
        btnGrantOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        btnGrantAccessibility.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    val compName = ComponentName(packageName, VoiceAccessibilityService::class.java.name).flattenToString()
                    putExtra(":settings:fragment_args_key", compName)
                    putExtra(":settings:show_fragment_args", Bundle().apply {
                        putString(":settings:fragment_args_key", compName)
                    })
                }
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        btnOpenAppDetails.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        // Step 4: Model Downloads
        btnDownloadXasr.setOnClickListener {
            handleDownloadButtonClick(ModelConfig.ENGINE_X_ASR)
        }

        btnDownloadQwen3.setOnClickListener {
            handleDownloadButtonClick(ModelConfig.ENGINE_QWEN3)
        }

        btnOcrTiny.setOnClickListener {
            ModelConfig.setSelectedOcrModel(this, ModelConfig.ENGINE_PP_OCR_TINY)
            updateStep4Status()
        }

        btnOcrSmall.setOnClickListener {
            ModelConfig.setSelectedOcrModel(this, ModelConfig.ENGINE_PP_OCR_SMALL)
            updateStep4Status()
        }

        btnDownloadOcr.setOnClickListener {
            val engine = ModelConfig.selectedOcrModel(this)
            handleDownloadButtonClick(engine)
        }

        // Step 4: Model Package ZIP Import
        btnOnboardingImportZip.setOnClickListener {
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

        btnOnboardingQuickImportDownload.setOnClickListener {
            val quickFile = ModelZipInstaller.findDefaultZipPackage(this)
            if (quickFile != null) {
                runModelPackageImport(file = quickFile)
            } else {
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
        }

        // Step 5: Preferences
        fun updateVadButtons(selected: Float) {
            ModelConfig.setVadSilenceSeconds(this@OnboardingActivity, selected)
            val primaryColor = ContextCompat.getColor(this@OnboardingActivity, R.color.md_theme_light_primary)
            val onPrimaryColor = ContextCompat.getColor(this@OnboardingActivity, R.color.md_theme_light_onPrimary)
            val tonalColor = ContextCompat.getColor(this@OnboardingActivity, R.color.surface_container_high)
            val onTonalColor = ContextCompat.getColor(this@OnboardingActivity, R.color.text_primary)

            fun styleChoice(btn: MaterialButton, isSelected: Boolean) {
                if (isSelected) {
                    btn.backgroundTintList = ColorStateList.valueOf(primaryColor)
                    btn.setTextColor(onPrimaryColor)
                    btn.strokeWidth = 0
                } else {
                    btn.backgroundTintList = ColorStateList.valueOf(tonalColor)
                    btn.setTextColor(onTonalColor)
                    btn.strokeWidth = 0
                }
            }
            styleChoice(btnVadQuick, selected == 0.8f)
            styleChoice(btnVadNormal, selected == 1.5f)
            styleChoice(btnVadRelaxed, selected == 2.5f)
        }

        val initialVad = ModelConfig.vadSilenceSeconds(this)
        updateVadButtons(initialVad)

        btnVadQuick.setOnClickListener { updateVadButtons(0.8f) }
        btnVadNormal.setOnClickListener { updateVadButtons(1.5f) }
        btnVadRelaxed.setOnClickListener { updateVadButtons(2.5f) }

        switchPunctuation.isChecked = ModelConfig.isFilterPunctuationEnabled(this)
        switchPunctuation.setOnCheckedChangeListener { _, isChecked ->
            ModelConfig.setFilterPunctuationEnabled(this, isChecked)
        }

        switchOcrAutoEnter.isChecked = ModelConfig.isOcrAutoEnterEnabled(this)
        switchOcrAutoEnter.setOnCheckedChangeListener { _, isChecked ->
            ModelConfig.setOcrAutoEnterEnabled(this, isChecked)
        }

        btnFinish.setOnClickListener {
            completeOnboarding()
        }
    }

    private fun renderStep(step: Int) {
        currentStep = step
        progressSteps.progress = step * 20
        tvStepLabel.text = "步驟 $step / 5"

        btnPrev.visibility = if (step > 1) View.VISIBLE else View.INVISIBLE
        btnNext.text = if (step == 5) "完成" else "下一步"

        step1Container.visibility = if (step == 1) View.VISIBLE else View.GONE
        step2Container.visibility = if (step == 2) View.VISIBLE else View.GONE
        step3Container.visibility = if (step == 3) View.VISIBLE else View.GONE
        step4Container.visibility = if (step == 4) View.VISIBLE else View.GONE
        step5Container.visibility = if (step == 5) View.VISIBLE else View.GONE

        // 滾動回頂端
        scrollContent.scrollTo(0, 0)

        // 刷新當前步驟對應狀態
        updateAllStatus()
    }

    private fun updateModeSelection(mode: String = ModelConfig.MODE_BUBBLE) {
        selectedMode = ModelConfig.MODE_BUBBLE
        ModelConfig.setOnboardingMode(this, ModelConfig.MODE_BUBBLE)

        val strokeSelected = ContextCompat.getColor(this, R.color.md_theme_light_primary)
        cardModeBubble.strokeColor = strokeSelected
        cardModeBubble.strokeWidth = 4
        ivModeBubbleCheck.visibility = View.VISIBLE

        layoutStep3BubbleGroup.visibility = View.VISIBLE
    }

    private fun updateAllStatus() {
        updateStep1Status()
        updateStep3Status()
        updateStep4Status()
        updateStep5Status()
    }

    private fun updateStep5Status() {
        val qwen3Ready = ModelConfig.isQwen3Ready(this)
        val xAsrReady = ModelConfig.isXAsrReady(this)

        // 若使用者僅下載 X-ASR，隱藏 Qwen3 專屬的 VAD 與標點符號偏好，顯示專屬說明
        if (xAsrReady && !qwen3Ready) {
            layoutQwen3Preferences.visibility = View.GONE
            cardXasrOnlyNotice.visibility = View.VISIBLE
        } else {
            layoutQwen3Preferences.visibility = View.VISIBLE
            cardXasrOnlyNotice.visibility = View.GONE
        }
    }

    private fun updateStep1Status() {
        if (hasMicPermission()) {
            tvMicStatus.text = "麥克風權限已就緒"
            tvMicStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            btnGrantMic.text = "已就緒"
            btnGrantMic.isEnabled = false
        } else {
            tvMicStatus.text = "語音輸入必備的核心權限，請點擊授予"
            tvMicStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnGrantMic.text = "授予麥克風權限"
            btnGrantMic.isEnabled = true
        }

        if (hasCameraPermission()) {
            tvCameraStatus.text = "相機鏡頭權限已就緒"
            tvCameraStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            btnGrantCamera.text = "已就緒"
            btnGrantCamera.isEnabled = false
        } else {
            tvCameraStatus.text = "相機文字辨識 (OCR) 必備權限，完全在裝置端運算"
            tvCameraStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnGrantCamera.text = "授予相機權限"
            btnGrantCamera.isEnabled = true
        }
    }

    private fun updateStep3Status() {
        // Bubble Status
        val overlayGranted = Settings.canDrawOverlays(this)
        val accessibilityEnabled = isAccessibilityServiceEnabled()

        if (overlayGranted) {
            tvOverlayStatus.text = "懸浮窗權限已就緒"
            tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            btnGrantOverlay.text = "已授權"
        } else {
            tvOverlayStatus.text = "允許語音泡泡飄浮在螢幕邊緣隨時供您點擊"
            tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnGrantOverlay.text = "前往授權懸浮窗"
        }

        if (accessibilityEnabled) {
            tvAccessibilityStatus.text = "自動填入文字服務已就緒"
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            btnGrantAccessibility.text = "已開啟"
        } else {
            tvAccessibilityStatus.text = "懸浮球辨識完成後，以此服務將文字填入輸入框"
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnGrantAccessibility.text = "前往開啟無障礙服務"
        }

        // 自動確保懸浮泡泡服務在系統條件就緒時運行
        if (overlayGranted && accessibilityEnabled && !FloatingBubbleService.isRunning) {
            FloatingBubbleService.start(this)
        }
    }

    private fun updateStep4Status() {
        val xAsrReady = ModelConfig.isXAsrReady(this)
        val qwen3Ready = ModelConfig.isQwen3Ready(this)
        val ocrReady = ModelConfig.isOcrReady(this)
        val selectedOcr = ModelConfig.selectedOcrModel(this)
        val isTiny = selectedOcr == ModelConfig.ENGINE_PP_OCR_TINY

        tvXasrBadge.text = if (xAsrReady) "已就緒" else "未下載"
        tvXasrBadge.setTextColor(ContextCompat.getColor(this, if (xAsrReady) R.color.status_success else R.color.text_tertiary))

        tvQwen3Badge.text = if (qwen3Ready) "已就緒" else "未下載"
        tvQwen3Badge.setTextColor(ContextCompat.getColor(this, if (qwen3Ready) R.color.status_success else R.color.text_tertiary))

        tvOcrBadge.text = if (ocrReady) "已就緒" else "未下載"
        tvOcrBadge.setTextColor(ContextCompat.getColor(this, if (ocrReady) R.color.status_success else R.color.text_tertiary))

        val primaryColor = ContextCompat.getColor(this, R.color.md_theme_light_primary)
        val onPrimaryColor = ContextCompat.getColor(this, R.color.md_theme_light_onPrimary)
        val tonalColor = ContextCompat.getColor(this, R.color.surface_container_high)
        val onTonalColor = ContextCompat.getColor(this, R.color.text_primary)

        if (isTiny) {
            btnOcrTiny.backgroundTintList = ColorStateList.valueOf(primaryColor)
            btnOcrTiny.setTextColor(onPrimaryColor)
            btnOcrTiny.strokeWidth = 0
            btnOcrSmall.backgroundTintList = ColorStateList.valueOf(tonalColor)
            btnOcrSmall.setTextColor(onTonalColor)
            btnOcrSmall.strokeWidth = 0
        } else {
            btnOcrSmall.backgroundTintList = ColorStateList.valueOf(primaryColor)
            btnOcrSmall.setTextColor(onPrimaryColor)
            btnOcrSmall.strokeWidth = 0
            btnOcrTiny.backgroundTintList = ColorStateList.valueOf(tonalColor)
            btnOcrTiny.setTextColor(onTonalColor)
            btnOcrTiny.strokeWidth = 0
        }

        val active = ModelDownloadState.active.value
        if (active == null) {
            btnDownloadXasr.text = if (xAsrReady) "已就緒" else "下載 X-ASR 模型"
            btnDownloadQwen3.text = if (qwen3Ready) "已就緒" else "下載 Qwen3-ASR 模型"
            btnDownloadOcr.text = if (ocrReady) "已就緒" else "下載 PP-OCRv6 模型 (${if (isTiny) "11MB" else "22MB"})"
        }

        val allModelsReady = ModelConfig.areAllModelsReady(this)
        if (allModelsReady) {
            tvOnboardingAllModelsBadge.text = "全部模型已就緒"
            tvOnboardingAllModelsBadge.setTextColor(ContextCompat.getColor(this, R.color.status_success_text))
            tvOnboardingAllModelsBadge.setBackgroundResource(R.drawable.bg_status_badge_success)
        } else {
            tvOnboardingAllModelsBadge.text = "推薦離線復原"
            tvOnboardingAllModelsBadge.setTextColor(ContextCompat.getColor(this, R.color.tag_recommend))
            tvOnboardingAllModelsBadge.setBackgroundResource(R.drawable.bg_status_badge_recommend)
        }

        val quickZip = ModelZipInstaller.findDefaultZipPackage(this)
        if (quickZip != null) {
            btnOnboardingQuickImportDownload.visibility = View.VISIBLE
            val sizeMb = quickZip.length() / (1024 * 1024)
            btnOnboardingQuickImportDownload.text = "⚡ 快速載入內部儲存模型包 (${sizeMb}MB)"
        } else {
            btnOnboardingQuickImportDownload.visibility = View.GONE
        }
    }

    private fun runModelPackageImport(file: java.io.File? = null, uri: Uri? = null) {
        btnOnboardingImportZip.isEnabled = false
        btnOnboardingQuickImportDownload.isEnabled = false
        progressOnboardingImportModels.visibility = View.VISIBLE
        progressOnboardingImportModels.progress = 0
        tvOnboardingImportStatus.visibility = View.VISIBLE
        tvOnboardingImportStatus.text = "正在準備解壓模型包…"

        scope.launch {
            val result = if (file != null) {
                ModelZipInstaller.installFromFile(this@OnboardingActivity, file) { currentFile, pct ->
                    runOnUiThread {
                        progressOnboardingImportModels.progress = pct
                        tvOnboardingImportStatus.text = "解壓中：$currentFile ($pct%)"
                    }
                }
            } else if (uri != null) {
                ModelZipInstaller.installFromUri(this@OnboardingActivity, uri) { currentFile, pct ->
                    runOnUiThread {
                        progressOnboardingImportModels.progress = pct
                        tvOnboardingImportStatus.text = "解壓中：$currentFile ($pct%)"
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

            btnOnboardingImportZip.isEnabled = true
            btnOnboardingQuickImportDownload.isEnabled = true
            progressOnboardingImportModels.visibility = View.GONE

            if (result.isSuccess) {
                tvOnboardingImportStatus.text = "✅ ${result.message}"
                updateStep4Status()
                Toast.makeText(this@OnboardingActivity, R.string.toast_models_extracted_success, Toast.LENGTH_LONG).show()
            } else {
                tvOnboardingImportStatus.text = "❌ ${result.message}"
                Toast.makeText(this@OnboardingActivity, result.message, Toast.LENGTH_LONG).show()
                updateStep4Status()
            }
        }
    }

    private fun handleDownloadButtonClick(engine: String) {
        val active = ModelDownloadState.active.value
        if (active != null && active.engine == engine) {
            ModelDownloadService.cancel(this)
            updateStep4Status()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            pendingDownloadEngine = engine
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        ModelDownloadService.start(this, engine)
    }

    private fun startObservingDownloads() {
        observeJob = scope.launch {
            launch {
                ModelDownloadState.active.collect { active ->
                    renderDownloadProgress(active)
                }
            }
            launch {
                ModelDownloadState.results.collect { (engine, result) ->
                    val label = when (engine) {
                        ModelConfig.ENGINE_X_ASR -> "X-ASR"
                        ModelConfig.ENGINE_QWEN3 -> "Qwen3-ASR"
                        ModelConfig.ENGINE_PP_OCR_TINY -> "PP-OCRv6 Tiny"
                        ModelConfig.ENGINE_PP_OCR_SMALL -> "PP-OCRv6 Small"
                        else -> engine
                    }
                    result.onSuccess {
                        Toast.makeText(this@OnboardingActivity, getString(R.string.toast_model_download_done, label), Toast.LENGTH_LONG).show()
                        updateStep4Status()
                    }.onFailure { ex ->
                        Toast.makeText(this@OnboardingActivity, getString(R.string.toast_model_download_failed, label, ex.message ?: ""), Toast.LENGTH_LONG).show()
                        updateStep4Status()
                    }
                }
            }
        }
    }

    private fun renderDownloadProgress(active: ModelDownloadState.Active?) {
        if (active == null) {
            progressXasr.visibility = View.GONE
            tvStatusXasr.visibility = View.GONE
            progressQwen3.visibility = View.GONE
            tvStatusQwen3.visibility = View.GONE
            progressOcr.visibility = View.GONE
            tvStatusOcr.visibility = View.GONE
            updateStep4Status()
            return
        }

        val isXasr = active.engine == ModelConfig.ENGINE_X_ASR
        val isQwen3 = active.engine == ModelConfig.ENGINE_QWEN3
        val isOcr = active.engine == ModelConfig.ENGINE_PP_OCR_TINY || active.engine == ModelConfig.ENGINE_PP_OCR_SMALL

        val progressIndicator = when {
            isXasr -> progressXasr
            isQwen3 -> progressQwen3
            else -> progressOcr
        }
        val tvStatus = when {
            isXasr -> tvStatusXasr
            isQwen3 -> tvStatusQwen3
            else -> tvStatusOcr
        }
        val btn = when {
            isXasr -> btnDownloadXasr
            isQwen3 -> btnDownloadQwen3
            else -> btnDownloadOcr
        }

        btn.text = "取消下載"
        progressIndicator.visibility = View.VISIBLE
        tvStatus.visibility = View.VISIBLE

        progressIndicator.isIndeterminate = active.progress.percent < 0
        if (active.progress.percent >= 0) {
            progressIndicator.progress = active.progress.percent
        }

        val pctStr = if (active.progress.percent >= 0) "${active.progress.percent}%" else ""
        tvStatus.text = "${active.progress.label} $pctStr"
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityServiceEnabled(): Boolean {
        if (VoiceAccessibilityService.isServiceRunning()) return true
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(packageName)
    }

    private fun completeOnboarding() {
        ModelConfig.setOnboardingCompleted(this, true)

        // 若下載了模型但尚未選取引擎，自動選取一個就緒的引擎
        val xAsrReady = ModelConfig.isXAsrReady(this)
        val qwen3Ready = ModelConfig.isQwen3Ready(this)
        if (qwen3Ready) {
            ModelConfig.setSelectedEngine(this, ModelConfig.ENGINE_QWEN3)
        } else if (xAsrReady) {
            ModelConfig.setSelectedEngine(this, ModelConfig.ENGINE_X_ASR)
        }

        startActivity(Intent(this, ImeSettingsActivity::class.java))
        finish()
    }
}
