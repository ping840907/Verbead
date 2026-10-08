package com.ping.verbead

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Rect
import android.media.AudioDeviceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.ping.verbead.engine.AudioRoutingManager
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.engine.ModelDownloadSpec
import com.ping.verbead.engine.ModelDownloadState
import com.ping.verbead.engine.ModelDownloader
import com.ping.verbead.engine.ModelZipInstaller
import com.ping.verbead.util.HapticUtil
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ImeSettingsActivity : AppCompatActivity() {

    companion object {
        private const val TOAST_MODEL_NOT_DOWNLOADED = "請先下載此模型，再選取為辨識引擎"
        private const val TOAST_DUAL_ENGINE_NEED_DOWNLOAD = "請先下載 X-ASR 與 Qwen3-ASR 兩個模型，才能開啟雙引擎模式"
        private const val TOAST_DUAL_ENGINE_NEED_QWEN3_SELECTED = "請先將辨識引擎切換為 Qwen3-ASR，才能開啟雙引擎模式"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val downloader by lazy { ModelDownloader(this) }
    private var observeJob: Job? = null
    private var pendingDownloadEngine: String? = null

    // Header & Status Indicator
    private lateinit var scrollSettings: NestedScrollView
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

    private lateinit var cardMicRoot: View
    private lateinit var cardBubbleModule: View
    private lateinit var cardImportModels: View

    // Node 1: Mic Root
    private lateinit var tvMicStatus: TextView
    private lateinit var btnGrantMic: MaterialButton

    // Engine cards (highlighted when selected)
    private lateinit var cardXAsr: MaterialCardView
    private lateinit var cardQwen3: MaterialCardView

    // Node 2: Bubble Module

    private lateinit var tvBubbleBadge: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var btnGrantOverlay: MaterialButton
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnGrantAccessibility: MaterialButton
    private lateinit var tvRestrictedSettingsHelp: TextView
    private lateinit var switchShowOnlyOnKeyboard: MaterialSwitch

    // Node 3: Engines
    // X-ASR
    private lateinit var tvXasrStatus: TextView
    private lateinit var btnSelectXasr: MaterialButton
    private lateinit var btnDownloadXasr: MaterialButton
    private lateinit var progressDownloadXasr: LinearProgressIndicator
    private lateinit var tvDownloadStatusXasr: TextView

    // Qwen3-ASR
    private lateinit var tvQwen3Status: TextView
    private lateinit var btnSelectQwen3: MaterialButton
    private lateinit var btnDownloadQwen3: MaterialButton
    private lateinit var progressDownloadQwen3: LinearProgressIndicator
    private lateinit var tvDownloadStatusQwen3: TextView

    // Dual Engine
    private lateinit var layoutDualEngineToggle: View
    private lateinit var switchDualEngine: MaterialSwitch

    // Speech general preferences (External Audio & VAD)
    private lateinit var layoutPreferExternalAudioToggle: View
    private lateinit var switchPreferExternalAudio: MaterialSwitch
    private lateinit var layoutAudioGain: View
    private lateinit var tvAudioGainValue: TextView
    private lateinit var sbAudioGain: SeekBar
    private lateinit var tvVadValue: TextView
    private lateinit var sbVadSilence: SeekBar

    // Qwen3 preferences
    private lateinit var switchFilterPunctuation: MaterialSwitch

    // Node 4: Vocabulary
    private lateinit var btnOpenDict: MaterialButton

    // Camera
    private lateinit var tvCameraStatus: TextView
    private lateinit var btnGrantCamera: MaterialButton

    // OCR & Scanner
    private lateinit var tvOcrStatus: TextView
    private lateinit var btnSelectOcrTiny: MaterialButton
    private lateinit var btnSelectOcrSmall: MaterialButton
    private lateinit var btnDownloadOcr: MaterialButton
    private lateinit var progressDownloadOcr: LinearProgressIndicator
    private lateinit var tvDownloadStatusOcr: TextView
    private lateinit var switchOcrAutoEnter: MaterialSwitch
    private lateinit var btnOcrSepNewline: MaterialButton
    private lateinit var btnOcrSepSpace: MaterialButton
    private lateinit var btnOcrSepNone: MaterialButton

    // Model Package ZIP Import
    private lateinit var tvAllModelsBadge: TextView
    private lateinit var progressImportModels: LinearProgressIndicator
    private lateinit var tvImportModelsStatus: TextView
    private lateinit var btnImportModelsZip: MaterialButton
    private lateinit var btnQuickImportDownload: MaterialButton

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

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updateAllStatus()
    }

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updateAllStatus()
    }

    private val requestExternalAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            ModelConfig.setPreferExternalAudio(this, true)
            switchPreferExternalAudio.isChecked = true
            Toast.makeText(this, "已開啟優先使用外部音訊", Toast.LENGTH_SHORT).show()
        } else {
            ModelConfig.setPreferExternalAudio(this, false)
            switchPreferExternalAudio.isChecked = false
            Toast.makeText(this, "需要鄰近裝置權限方能使用藍牙音訊", Toast.LENGTH_SHORT).show()
        }
        updateAllStatus()
    }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pendingDownloadEngine?.let { engine ->
            beginDownload(engine)
            pendingDownloadEngine = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初次開啟導向新手設置引導頁
        if (!ModelConfig.isOnboardingCompleted(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_settings)

        // 即時監聽系統預設輸入法與已啟用輸入法清單之變更
        bindViews()
        setupListeners()
        updateAllStatus()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        android.util.Log.d("Verbead_Zip", "handleIntent: ${intent.extras}")
        if (intent.getBooleanExtra("auto_import_default_zip", false)) {
            val quickFile = ModelZipInstaller.findDefaultZipPackage(this)
            android.util.Log.d("Verbead_Zip", "auto_import_default_zip -> quickFile: $quickFile")
            if (quickFile != null) {
                runModelPackageImport(file = quickFile)
            }
        } else if (intent.hasExtra("import_zip_path")) {
            val path = intent.getStringExtra("import_zip_path")
            android.util.Log.d("Verbead_Zip", "import_zip_path: $path")
            if (!path.isNullOrEmpty()) {
                val f = java.io.File(path)
                if (f.exists()) {
                    runModelPackageImport(file = f)
                }
            }
        }
    }

    private fun bindViews() {
        // Overall & Status Indicator
        scrollSettings = findViewById(R.id.scroll_settings)
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

        cardMicRoot = findViewById(R.id.card_mic_root)
        cardBubbleModule = findViewById(R.id.card_bubble_module)
        cardImportModels = findViewById(R.id.card_import_models)

        // Node 1: Mic
        tvMicStatus = findViewById(R.id.tv_mic_status)
        btnGrantMic = findViewById(R.id.btn_grant_mic)

        // Node 2: Bubble Module
        tvBubbleBadge             = findViewById(R.id.tv_bubble_badge)
        tvOverlayStatus           = findViewById(R.id.tv_overlay_status)
        btnGrantOverlay           = findViewById(R.id.btn_grant_overlay)
        tvAccessibilityStatus     = findViewById(R.id.tv_accessibility_status)
        btnGrantAccessibility     = findViewById(R.id.btn_grant_accessibility)
        tvRestrictedSettingsHelp  = findViewById(R.id.tv_restricted_settings_help)
        switchShowOnlyOnKeyboard  = findViewById(R.id.switch_show_only_on_keyboard)

        // Node 3: Engines
        cardXAsr = findViewById(R.id.card_x_asr)
        cardQwen3 = findViewById(R.id.card_qwen3)
        // X-ASR
        tvXasrStatus           = findViewById(R.id.tv_xasr_status)
        btnSelectXasr          = findViewById(R.id.btn_select_xasr)
        btnDownloadXasr        = findViewById(R.id.btn_download_xasr)
        progressDownloadXasr   = findViewById(R.id.progress_download_xasr)
        tvDownloadStatusXasr   = findViewById(R.id.tv_download_status_xasr)

        // Qwen3-ASR
        tvQwen3Status           = findViewById(R.id.tv_qwen3_status)
        btnSelectQwen3          = findViewById(R.id.btn_select_qwen3)
        btnDownloadQwen3        = findViewById(R.id.btn_download_qwen3)
        progressDownloadQwen3   = findViewById(R.id.progress_download_qwen3)
        tvDownloadStatusQwen3   = findViewById(R.id.tv_download_status_qwen3)

        // Dual Engine
        layoutDualEngineToggle = findViewById(R.id.layout_dual_engine_toggle)
        switchDualEngine       = findViewById(R.id.switch_dual_engine)

        // Speech general preferences (External Audio & VAD)
        layoutPreferExternalAudioToggle = findViewById(R.id.layout_prefer_external_audio_toggle)
        switchPreferExternalAudio       = findViewById(R.id.switch_prefer_external_audio)
        layoutAudioGain                 = findViewById(R.id.layout_audio_gain)
        tvAudioGainValue                = findViewById(R.id.tv_audio_gain_value)
        sbAudioGain                     = findViewById(R.id.sb_audio_gain)
        tvVadValue                      = findViewById(R.id.tv_vad_value)
        sbVadSilence                    = findViewById(R.id.sb_vad_silence)

        // Qwen3 preferences
        switchFilterPunctuation = findViewById(R.id.switch_filter_punctuation)

        // Node 4: Vocabulary
        btnOpenDict = findViewById(R.id.btn_open_dict)

        // Camera
        tvCameraStatus       = findViewById(R.id.tv_camera_status)
        btnGrantCamera       = findViewById(R.id.btn_grant_camera)

        // OCR & Scanner
        tvOcrStatus          = findViewById(R.id.tv_ocr_status)
        btnSelectOcrTiny     = findViewById(R.id.btn_select_ocr_tiny)
        btnSelectOcrSmall    = findViewById(R.id.btn_select_ocr_small)
        btnDownloadOcr       = findViewById(R.id.btn_download_ocr)
        progressDownloadOcr  = findViewById(R.id.progress_download_ocr)
        tvDownloadStatusOcr  = findViewById(R.id.tv_download_status_ocr)
        switchOcrAutoEnter   = findViewById(R.id.switch_ocr_auto_enter)
        btnOcrSepNewline     = findViewById(R.id.btn_ocr_sep_newline)
        btnOcrSepSpace       = findViewById(R.id.btn_ocr_sep_space)
        btnOcrSepNone        = findViewById(R.id.btn_ocr_sep_none)

        // Model Package ZIP Import
        tvAllModelsBadge        = findViewById(R.id.tv_all_models_badge)
        progressImportModels    = findViewById(R.id.progress_import_models)
        tvImportModelsStatus    = findViewById(R.id.tv_import_models_status)
        btnImportModelsZip      = findViewById(R.id.btn_import_models_zip)
        btnQuickImportDownload  = findViewById(R.id.btn_quick_import_download)
    }

    private fun scrollToView(target: View) {
        scrollSettings.post {
            val rect = Rect()
            target.getDrawingRect(rect)
            scrollSettings.offsetDescendantRectToMyCoords(target, rect)
            val scrollY = (rect.top - 24 * resources.displayMetrics.density).toInt()
            scrollSettings.smoothScrollTo(0, maxOf(0, scrollY))

            // 目標卡片抵達後提供微縮放呼吸與觸覺輕震反饋，指引用戶視覺焦點
            target.postDelayed({
                target.animate()
                    .scaleX(1.025f)
                    .scaleY(1.025f)
                    .setDuration(160)
                    .withEndAction {
                        target.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(160)
                            .start()
                    }
                    .start()
                HapticUtil.tick(this@ImeSettingsActivity)
            }, 260)
        }
    }

    private fun setupListeners() {
        btnOpenOnboarding.setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        // Status indicator click to smooth scroll
        itemStatusMic.setOnClickListener { scrollToView(cardMicRoot) }
        itemStatusOverlay.setOnClickListener { scrollToView(cardBubbleModule) }
        itemStatusAccessibility.setOnClickListener { scrollToView(btnGrantAccessibility) }
        itemStatusModel.setOnClickListener { scrollToView(cardImportModels) }

        // Node 1: Mic & Camera Permissions
        btnGrantMic.setOnClickListener {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
        btnGrantCamera.setOnClickListener {
            requestCamera.launch(Manifest.permission.CAMERA)
        }


        // Node 2: Bubble & §3.2.1 高亮跳轉
        btnGrantOverlay.setOnClickListener {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (ex: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
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
            } catch (ex: Exception) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        // §3.2.2 備援連結與受限制設定指引
        tvRestrictedSettingsHelp.setOnClickListener {
            showRestrictedSettingsDialog()
        }

        switchShowOnlyOnKeyboard.setOnCheckedChangeListener { _, isChecked ->
            ModelConfig.setShowOnlyOnKeyboard(this, isChecked)
            FloatingBubbleService.instance?.applyKeyboardOnlySetting()
        }

        // Node 3: Engine Select Buttons (§2.2 點擊邏輯與聯鎖防呆)
        btnSelectXasr.setOnClickListener {
            onSelectButtonClick(ModelConfig.ENGINE_X_ASR)
        }

        btnSelectQwen3.setOnClickListener {
            onSelectButtonClick(ModelConfig.ENGINE_QWEN3)
        }

        // Downloads
        btnDownloadXasr.setOnClickListener {
            handleDownloadButtonClick(ModelConfig.ENGINE_X_ASR)
        }

        btnDownloadQwen3.setOnClickListener {
            handleDownloadButtonClick(ModelConfig.ENGINE_QWEN3)
        }

        // Dual Engine Toggle Click Logic (§2.2)
        layoutDualEngineToggle.setOnClickListener {
            onDualEngineToggleClick()
        }

        // Speech general preferences: External audio
        fun togglePreferExternalAudio() {
            val willEnable = !switchPreferExternalAudio.isChecked
            if (willEnable) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val hasBt = ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!hasBt) {
                        requestExternalAudioPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        return
                    }
                }
                ModelConfig.setPreferExternalAudio(this, true)
                switchPreferExternalAudio.isChecked = true
            } else {
                ModelConfig.setPreferExternalAudio(this, false)
                switchPreferExternalAudio.isChecked = false
            }
            updateAllStatus()
        }
        layoutPreferExternalAudioToggle.setOnClickListener {
            togglePreferExternalAudio()
        }

        // Speech general preferences: External audio gain slider
        fun audioGainLabel(gain: Float) = if (gain == 1.0f) "1.0x (原音)" else "%.1fx 增益".format(gain)
        val currentGain = ModelConfig.externalAudioGain(this)
        val gainProgress = (((currentGain - ModelConfig.MIN_EXTERNAL_AUDIO_GAIN) / ModelConfig.STEP_EXTERNAL_AUDIO_GAIN).roundToInt())
            .coerceIn(0, 10)
        sbAudioGain.progress = gainProgress
        tvAudioGainValue.text = audioGainLabel(currentGain)
        sbAudioGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val gain = ModelConfig.MIN_EXTERNAL_AUDIO_GAIN + progress * ModelConfig.STEP_EXTERNAL_AUDIO_GAIN
                tvAudioGainValue.text = audioGainLabel(gain)
                if (fromUser) {
                    ModelConfig.setExternalAudioGain(this@ImeSettingsActivity, gain)
                    HapticUtil.tick(this@ImeSettingsActivity)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // Speech general preferences: VAD slider
        fun vadLabel(seconds: Float) = "%.1f 秒".format(seconds)
        val currentVad = ModelConfig.vadSilenceSeconds(this)
        sbVadSilence.progress = (((currentVad - ModelConfig.VAD_SILENCE_MIN) / 0.1f).toInt())
        tvVadValue.text = vadLabel(currentVad)
        sbVadSilence.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val seconds = ModelConfig.VAD_SILENCE_MIN + progress * 0.1f
                tvVadValue.text = vadLabel(seconds)
                if (fromUser) {
                    ModelConfig.setVadSilenceSeconds(this@ImeSettingsActivity, seconds)
                    HapticUtil.tick(this@ImeSettingsActivity)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // Qwen3 settings: Punctuation filter
        findViewById<View?>(R.id.layout_filter_punctuation_toggle)?.setOnClickListener {
            val nextState = !switchFilterPunctuation.isChecked
            switchFilterPunctuation.isChecked = nextState
            ModelConfig.setFilterPunctuationEnabled(this, nextState)
        }
        switchFilterPunctuation.isChecked = ModelConfig.isFilterPunctuationEnabled(this)
        switchFilterPunctuation.setOnCheckedChangeListener { _, isChecked ->
            ModelConfig.setFilterPunctuationEnabled(this, isChecked)
        }

        // Vocabulary
        btnOpenDict.setOnClickListener {
            startActivity(Intent(this, DictSettingsActivity::class.java))
        }

        // OCR & Scanner
        btnSelectOcrTiny.setOnClickListener {
            ModelConfig.setSelectedOcrModel(this, ModelConfig.ENGINE_PP_OCR_TINY)
            updateAllStatus()
        }
        btnSelectOcrSmall.setOnClickListener {
            ModelConfig.setSelectedOcrModel(this, ModelConfig.ENGINE_PP_OCR_SMALL)
            updateAllStatus()
        }
        btnDownloadOcr.setOnClickListener {
            val engine = ModelConfig.selectedOcrModel(this)
            handleDownloadButtonClick(engine)
        }
        switchOcrAutoEnter.setOnCheckedChangeListener { _, isChecked ->
            ModelConfig.setOcrAutoEnterEnabled(this, isChecked)
        }

        fun updateOcrSepSelection(sep: String) {
            ModelConfig.setOcrSeparator(this, sep)
            updateAllStatus()
        }
        btnOcrSepNewline.setOnClickListener { updateOcrSepSelection("\n") }
        btnOcrSepSpace.setOnClickListener { updateOcrSepSelection(" ") }
        btnOcrSepNone.setOnClickListener { updateOcrSepSelection("") }

        // Model Package ZIP Import
        btnImportModelsZip.setOnClickListener {
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

        // 開源專案與致謝連結
        fun openWebUrl(url: String) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (ex: Exception) {
                Toast.makeText(this, getString(R.string.toast_url_open_error, ex.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<View?>(R.id.row_credit_ppocr)?.setOnClickListener {
            openWebUrl("https://github.com/PaddlePaddle/PaddleOCR")
        }
        findViewById<View?>(R.id.row_credit_zxing)?.setOnClickListener {
            openWebUrl("https://github.com/zxing-cpp/zxing-cpp")
        }
        findViewById<View?>(R.id.row_credit_sherpa)?.setOnClickListener {
            openWebUrl("https://github.com/k2-fsa/sherpa-onnx")
        }
        findViewById<View?>(R.id.row_credit_xasr)?.setOnClickListener {
            openWebUrl("https://huggingface.co/Luigi/x-asr-zh-tw-en-streaming-ft75m")
        }
        findViewById<View?>(R.id.row_credit_qwen)?.setOnClickListener {
            openWebUrl("https://github.com/QwenLM")
        }
        findViewById<View?>(R.id.row_credit_onnx)?.setOnClickListener {
            openWebUrl("https://github.com/microsoft/onnxruntime")
        }
    }

    override fun onStart() {
        super.onStart()
        observeJob = scope.launch {
            launch {
                ModelDownloadState.active.collect { active ->
                    renderDownloadStatus(active)
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
                        if (engine == ModelConfig.ENGINE_PP_OCR_TINY || engine == ModelConfig.ENGINE_PP_OCR_SMALL) {
                            ModelConfig.setSelectedOcrModel(this@ImeSettingsActivity, engine)
                        }
                        Toast.makeText(this@ImeSettingsActivity, getString(R.string.toast_model_download_done, label), Toast.LENGTH_LONG).show()
                    }.onFailure { ex ->
                        Toast.makeText(this@ImeSettingsActivity, getString(R.string.toast_model_download_failed, label, ex.message ?: ""), Toast.LENGTH_LONG).show()
                    }
                    updateAllStatus()
                }
            }
            launch {
                while (true) {
                    delay(1000)
                    ModelDownloadState.active.value?.let { renderDownloadStatus(it) }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
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

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // §2.2 線路生效運算式與狀態判定
    // ══════════════════════════════════════════════════════════════════════════
    private fun updatePermissionButton(
        button: MaterialButton,
        statusTextView: TextView,
        isGranted: Boolean,
        grantedDesc: String,
        notGrantedDesc: String
    ) {
        if (isGranted) {
            statusTextView.text = grantedDesc
            statusTextView.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            button.text = "已啟用"
            button.isEnabled = false
        } else {
            statusTextView.text = notGrantedDesc
            statusTextView.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            button.text = "授予權限"
            button.isEnabled = true
        }
    }

    private fun updateAllStatus() {
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        val overlayGranted = Settings.canDrawOverlays(this)
        val accessibilityEnabled = isAccessibilityServiceEnabled()

        val xAsrDownloaded = ModelConfig.isXAsrReady(this)
        val qwen3Downloaded = ModelConfig.isQwen3Ready(this)
        var selectedEngine = ModelConfig.selectedEngine(this)

        // Initial sanity check: ensure selected points to a valid ready engine if possible
        if (!xAsrDownloaded && !qwen3Downloaded) {
            // Neither downloaded
        } else if (selectedEngine == ModelConfig.ENGINE_X_ASR && !xAsrDownloaded && qwen3Downloaded) {
            selectedEngine = ModelConfig.ENGINE_QWEN3
            ModelConfig.setSelectedEngine(this, selectedEngine)
        } else if (selectedEngine == ModelConfig.ENGINE_QWEN3 && !qwen3Downloaded && xAsrDownloaded) {
            selectedEngine = ModelConfig.ENGINE_X_ASR
            ModelConfig.setSelectedEngine(this, selectedEngine)
        }

        var dualEngineToggle = ModelConfig.isDualEngineEnabled(this)

        // 1. 輸入路徑線路（懸浮語音球）
        val bubbleModuleLineActive = micGranted && overlayGranted && accessibilityEnabled
        val inputPathReady = bubbleModuleLineActive

        // 自動確保懸浮泡泡服務在系統條件就緒時運行
        if (overlayGranted && accessibilityEnabled && !FloatingBubbleService.isRunning) {
            FloatingBubbleService.start(this)
        }

        // 2. 模型就緒主線路
        val xAsrReadyLineActive = xAsrDownloaded
        val qwen3ReadyLineActive = qwen3Downloaded

        // 3. 雙引擎可用條件判定與聯鎖防呆
        val dualEngineSelectable = xAsrDownloaded && qwen3Downloaded && (selectedEngine == ModelConfig.ENGINE_QWEN3)
        if (!dualEngineSelectable && dualEngineToggle) {
            dualEngineToggle = false
            ModelConfig.setDualEngineEnabled(this, false)
        }
        val dualEngineLineActive = dualEngineSelectable && dualEngineToggle

        // 4. 詞彙線路
        val currentEngineReady = (selectedEngine == ModelConfig.ENGINE_X_ASR && xAsrDownloaded) ||
                (selectedEngine == ModelConfig.ENGINE_QWEN3 && qwen3Downloaded)
        val vocabularyLineActive = inputPathReady && currentEngineReady

        // ══════════════════════════════════════════════════════════════════════
        // 更新 UI 元件狀態與文案
        // ══════════════════════════════════════════════════════════════════════

        // Node 1: Mic & Camera
        val preferExternal = ModelConfig.isPreferExternalAudio(this)
        val hasBtPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val effectivePreferExternal = preferExternal && hasBtPermission
        if (preferExternal && !hasBtPermission) {
            ModelConfig.setPreferExternalAudio(this, false)
        }
        switchPreferExternalAudio.isChecked = effectivePreferExternal
        layoutAudioGain.alpha = if (effectivePreferExternal) 1.0f else 0.45f
        sbAudioGain.isEnabled = effectivePreferExternal

        val micGrantedDesc = run {
            if (effectivePreferExternal) {
                val preferred = AudioRoutingManager.getInstance(this).getPreferredInputDevice()
                val isBt = preferred?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && preferred?.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                if (isBt) {
                    "已就緒（優先使用藍牙音訊：${preferred?.productName ?: "藍牙耳機"}）"
                } else {
                    "麥克風錄音權限已就緒"
                }
            } else {
                "麥克風錄音權限已就緒（使用內建麥克風）"
            }
        }
        updatePermissionButton(
            btnGrantMic,
            tvMicStatus,
            micGranted,
            micGrantedDesc,
            "辨識語音必須的系統核心權限"
        )

        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        updatePermissionButton(
            btnGrantCamera,
            tvCameraStatus,
            cameraGranted,
            "相機鏡頭權限已就緒",
            "相機文字辨識與條碼掃描所需，完全在裝置本機端執行"
        )


        // Node 2: Bubble
        if (bubbleModuleLineActive) {
            tvBubbleBadge.text = "就緒"
            tvBubbleBadge.setTextColor(ContextCompat.getColor(this, R.color.status_success_text))
            tvBubbleBadge.setBackgroundResource(R.drawable.bg_status_badge_success)
        } else {
            tvBubbleBadge.text = "未就緒"
            tvBubbleBadge.setTextColor(ContextCompat.getColor(this, R.color.status_warning_text))
            tvBubbleBadge.setBackgroundResource(R.drawable.bg_status_badge_warning)
        }

        if (overlayGranted) {
            tvOverlayStatus.text = "懸浮視窗權限已授予"
            tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            btnGrantOverlay.text = "已授權"
        } else {
            tvOverlayStatus.text = "允許顯示在其他應用程式上層"
            tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnGrantOverlay.text = "前往授權"
        }

        if (accessibilityEnabled) {
            tvAccessibilityStatus.text = "自動貼上服務已啟用"
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            btnGrantAccessibility.text = "已開啟"
            tvRestrictedSettingsHelp.visibility = View.GONE
        } else {
            tvAccessibilityStatus.text = "將辨識文字直接填入焦點欄位"
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnGrantAccessibility.text = "前往開啟"
            tvRestrictedSettingsHelp.visibility = View.VISIBLE
        }

        switchShowOnlyOnKeyboard.isChecked = ModelConfig.isShowOnlyOnKeyboard(this)

        val primaryColor = ContextCompat.getColor(this, R.color.md_theme_light_primary)
        val onPrimaryColor = ContextCompat.getColor(this, R.color.md_theme_light_onPrimary)
        val tonalColor = ContextCompat.getColor(this, R.color.surface_container_high)
        val onTonalColor = ContextCompat.getColor(this, R.color.text_primary)
        val cardStrokeWidthSelected = (2 * resources.displayMetrics.density).toInt()

        // Node 3: X-ASR Card
        val isXasrSelected = selectedEngine == ModelConfig.ENGINE_X_ASR
        if (isXasrSelected) {
            cardXAsr.strokeColor = primaryColor
            cardXAsr.strokeWidth = cardStrokeWidthSelected
            btnSelectXasr.text = "使用中"
            btnSelectXasr.backgroundTintList = ColorStateList.valueOf(primaryColor)
            btnSelectXasr.setTextColor(onPrimaryColor)
        } else {
            cardXAsr.strokeWidth = 0
            btnSelectXasr.text = "選取引擎"
            btnSelectXasr.backgroundTintList = ColorStateList.valueOf(tonalColor)
            btnSelectXasr.setTextColor(onTonalColor)
        }

        if (xAsrDownloaded) {
            tvXasrStatus.text = "已下載就緒"
            tvXasrStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            btnDownloadXasr.text = "重新下載"
        } else {
            tvXasrStatus.text = "未下載 (約 138MB)"
            tvXasrStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnDownloadXasr.text = "下載模型"
        }

        // Node 3: Qwen3 Card
        val isQwen3Selected = selectedEngine == ModelConfig.ENGINE_QWEN3
        if (isQwen3Selected) {
            cardQwen3.strokeColor = primaryColor
            cardQwen3.strokeWidth = cardStrokeWidthSelected
            btnSelectQwen3.text = "使用中"
            btnSelectQwen3.backgroundTintList = ColorStateList.valueOf(primaryColor)
            btnSelectQwen3.setTextColor(onPrimaryColor)
        } else {
            cardQwen3.strokeWidth = 0
            btnSelectQwen3.text = "選取引擎"
            btnSelectQwen3.backgroundTintList = ColorStateList.valueOf(tonalColor)
            btnSelectQwen3.setTextColor(onTonalColor)
        }

        if (qwen3Downloaded) {
            tvQwen3Status.text = "已下載就緒"
            tvQwen3Status.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            btnDownloadQwen3.text = "重新下載"
        } else {
            tvQwen3Status.text = "未下載 (約 878MB)"
            tvQwen3Status.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnDownloadQwen3.text = "下載模型"
        }

        // Dual Engine Toggle
        switchDualEngine.isChecked = dualEngineToggle

        // OCR & Scanner Node
        val selectedOcr = ModelConfig.selectedOcrModel(this)
        val isTiny = selectedOcr == ModelConfig.ENGINE_PP_OCR_TINY
        val tinyReady = ModelConfig.isOcrTinyReady(this)
        val smallReady = ModelConfig.isOcrSmallReady(this)
        val isSelectedReady = if (isTiny) tinyReady else smallReady
        val anyOcrReady = tinyReady || smallReady

        if (isTiny) {
            btnSelectOcrTiny.backgroundTintList = ColorStateList.valueOf(primaryColor)
            btnSelectOcrTiny.setTextColor(onPrimaryColor)
            btnSelectOcrTiny.strokeWidth = 0
            btnSelectOcrSmall.backgroundTintList = ColorStateList.valueOf(tonalColor)
            btnSelectOcrSmall.setTextColor(onTonalColor)
            btnSelectOcrSmall.strokeWidth = 0
        } else {
            btnSelectOcrSmall.backgroundTintList = ColorStateList.valueOf(primaryColor)
            btnSelectOcrSmall.setTextColor(onPrimaryColor)
            btnSelectOcrSmall.strokeWidth = 0
            btnSelectOcrTiny.backgroundTintList = ColorStateList.valueOf(tonalColor)
            btnSelectOcrTiny.setTextColor(onTonalColor)
            btnSelectOcrTiny.strokeWidth = 0
        }

        btnSelectOcrTiny.text = if (tinyReady) "Tiny · 已就緒" else "Tiny · 11MB"
        btnSelectOcrSmall.text = if (smallReady) "Small · 已就緒" else "Small · 22MB"

        if (isSelectedReady) {
            tvOcrStatus.text = "已就緒 (${if (isTiny) "Tiny 輕量版" else "Small 高精版"})"
            tvOcrStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            btnDownloadOcr.text = "重新下載"
        } else {
            val sizeStr = if (isTiny) "約 11MB" else "約 22MB"
            if (anyOcrReady) {
                val existingName = if (isTiny) "Small 高精版" else "Tiny 輕量版"
                tvOcrStatus.text = "未下載 ($sizeStr，下載後將自動替換現有 $existingName)"
                tvOcrStatus.setTextColor(ContextCompat.getColor(this, R.color.status_warning_text))
                btnDownloadOcr.text = "下載並替換模型"
            } else {
                tvOcrStatus.text = "未下載 ($sizeStr)"
                tvOcrStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                btnDownloadOcr.text = "下載模型"
            }
        }
        switchOcrAutoEnter.isChecked = ModelConfig.isOcrAutoEnterEnabled(this)

        val currentSep = ModelConfig.ocrSeparator(this)
        fun styleSepButton(btn: MaterialButton, isSelected: Boolean) {
            if (isSelected) {
                btn.backgroundTintList = ColorStateList.valueOf(primaryColor)
                btn.setTextColor(onPrimaryColor)
            } else {
                btn.backgroundTintList = ColorStateList.valueOf(tonalColor)
                btn.setTextColor(onTonalColor)
            }
            btn.strokeWidth = 0
        }
        styleSepButton(btnOcrSepNewline, currentSep == "\n")
        styleSepButton(btnOcrSepSpace, currentSep == " ")
        styleSepButton(btnOcrSepNone, currentSep == "")

        // Header Overall Badge & Status Indicator Items
        val successColor = ContextCompat.getColor(this, R.color.status_success_text)
        val warningColor = ContextCompat.getColor(this, R.color.status_warning_text)

        var pendingCount = 0

        // 1. 麥克風
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

        // 2. 懸浮窗
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

        // 3. 無障礙
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

        // 4. 語音模型
        val anyModelReady = xAsrDownloaded || qwen3Downloaded
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

        // Offline Model Package Status
        val allModelsReady = ModelConfig.areAllModelsReady(this)
        if (allModelsReady) {
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
            btnQuickImportDownload.text = "⚡ 快速載入內部儲存模型包 (${sizeMb}MB)"
        } else {
            btnQuickImportDownload.visibility = View.GONE
        }
    }

    private fun runModelPackageImport(file: java.io.File? = null, uri: Uri? = null) {
        btnImportModelsZip.isEnabled = false
        btnQuickImportDownload.isEnabled = false
        progressImportModels.visibility = View.VISIBLE
        progressImportModels.progress = 0
        tvImportModelsStatus.visibility = View.VISIBLE
        tvImportModelsStatus.text = "正在準備解壓模型包…"

        scope.launch {
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
                tvImportModelsStatus.text = "✅ ${result.message}"
                updateAllStatus()
                AlertDialog.Builder(this@ImeSettingsActivity)
                    .setTitle("離線模型一鍵復原成功")
                    .setMessage("已成功復原共 ${result.fileCount} 個模型核心檔案！\n\n已就緒項目：\n• Qwen3-ASR 高精度離線語音\n• X-ASR 極速即時串流語音\n• PP-OCRv6 端側文字辨識\n\n所有功能均已就緒，可立即使用。")
                    .setPositiveButton("太棒了", null)
                    .show()
            } else {
                tvImportModelsStatus.text = "❌ ${result.message}"
                Toast.makeText(this@ImeSettingsActivity, result.message, Toast.LENGTH_LONG).show()
                updateAllStatus()
            }
        }
    }

    // §2.2 模型卡的 select_button 點擊邏輯與聯鎖防呆
    private fun onSelectButtonClick(targetEngine: String) {
        val downloaded = if (targetEngine == ModelConfig.ENGINE_X_ASR) {
            ModelConfig.isXAsrReady(this)
        } else {
            ModelConfig.isQwen3Ready(this)
        }

        if (downloaded) {
            ModelConfig.setSelectedEngine(this, targetEngine)
            // 聯鎖防呆：若切換引擎導致雙引擎條件不成立，強制關閉開關以防殘留
            val dualEngineSelectable = ModelConfig.isXAsrReady(this) &&
                    ModelConfig.isQwen3Ready(this) &&
                    (targetEngine == ModelConfig.ENGINE_QWEN3)
            if (!dualEngineSelectable) {
                ModelConfig.setDualEngineEnabled(this, false)
            }
            updateAllStatus()
        } else {
            Toast.makeText(this, R.string.toast_model_not_downloaded, Toast.LENGTH_SHORT).show()
        }
    }

    // §2.2 dual_engine_toggle 點擊邏輯
    private fun onDualEngineToggleClick() {
        val xDownloaded = ModelConfig.isXAsrReady(this)
        val qDownloaded = ModelConfig.isQwen3Ready(this)
        val selected = ModelConfig.selectedEngine(this)
        val dualEngineSelectable = xDownloaded && qDownloaded && (selected == ModelConfig.ENGINE_QWEN3)

        if (dualEngineSelectable) {
            val nextState = !ModelConfig.isDualEngineEnabled(this)
            ModelConfig.setDualEngineEnabled(this, nextState)
            updateAllStatus()
        } else if (!xDownloaded || !qDownloaded) {
            val missing = when {
                !xDownloaded && !qDownloaded -> "X-ASR 即時串流模型 與 Qwen3-ASR 離線主模型"
                !xDownloaded -> "X-ASR 即時串流模型"
                else -> "Qwen3-ASR 離線主模型"
            }
            AlertDialog.Builder(this)
                .setTitle("需要下載模型以啟用雙引擎")
                .setMessage("雙引擎模式結合了 X-ASR 的低延遲即時預覽與 Qwen3-ASR 的高精離線轉譯。\n\n目前尚未下載：$missing。\n請先於下方對應模型卡片點選「下載模型」完成安裝後再開啟。")
                .setPositiveButton("我知道了", null)
                .show()
        } else if (selected != ModelConfig.ENGINE_QWEN3) {
            AlertDialog.Builder(this)
                .setTitle("切換為雙引擎模式")
                .setMessage("雙引擎模式需以 Qwen3-ASR 作為核心辨識引擎，並由 X-ASR 提供即時文字預覽。\n\n是否立即將辨識引擎切換為 Qwen3-ASR 並開啟雙引擎模式？")
                .setPositiveButton("切換並開啟") { _, _ ->
                    ModelConfig.setSelectedEngine(this, ModelConfig.ENGINE_QWEN3)
                    ModelConfig.setDualEngineEnabled(this, true)
                    updateAllStatus()
                    Toast.makeText(this, R.string.toast_switched_to_qwen3_dual, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    // §3.2.2 受限制設定提示彈窗
    private fun showRestrictedSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("啟用受限制的設定 (Android 13+)")
            .setMessage(
                "若無障礙服務開關呈現灰階鎖定，請依照以下步驟解除限制：\n\n" +
                        "1. 點擊下方按鈕前往「應用程式資訊」頁面。\n" +
                        "2. 點選畫面右上角「⋮」（三點選單）。\n" +
                        "3. 點選「允許受限制的設定」並輸入螢幕鎖定密碼解鎖。\n" +
                        "4. 完成後返回系統無障礙服務清單即可正常開啟服務。"
            )
            .setPositiveButton("前往應用程式資訊") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun handleDownloadButtonClick(engine: String) {
        val active = ModelDownloadState.active.value
        if (active != null && active.engine == engine) {
            ModelDownloadService.cancel(this)
            hideDownloadProgress(engine)
            updateAllStatus()
            return
        }

        val target = ModelDownloadSpec.forEngine(engine)
        val engineLabel = when (engine) {
            ModelConfig.ENGINE_X_ASR -> "X-ASR (約 138MB)"
            ModelConfig.ENGINE_QWEN3 -> "Qwen3-ASR (約 878MB)"
            ModelConfig.ENGINE_PP_OCR_TINY -> "PP-OCRv6 Tiny (約 11MB)"
            ModelConfig.ENGINE_PP_OCR_SMALL -> "PP-OCRv6 Small (約 22MB)"
            else -> engine
        }
        val btn = when (engine) {
            ModelConfig.ENGINE_X_ASR -> btnDownloadXasr
            ModelConfig.ENGINE_QWEN3 -> btnDownloadQwen3
            else -> btnDownloadOcr
        }
        btn.isEnabled = false
        btn.text = "檢查檔案大小…"

        scope.launch {
            val bytes = downloader.estimateTotalBytes(target)
            btn.isEnabled = true
            val ready = when (engine) {
                ModelConfig.ENGINE_X_ASR -> ModelConfig.isXAsrReady(this@ImeSettingsActivity)
                ModelConfig.ENGINE_QWEN3 -> ModelConfig.isQwen3Ready(this@ImeSettingsActivity)
                ModelConfig.ENGINE_PP_OCR_TINY -> ModelConfig.isOcrTinyReady(this@ImeSettingsActivity)
                ModelConfig.ENGINE_PP_OCR_SMALL -> ModelConfig.isOcrSmallReady(this@ImeSettingsActivity)
                else -> ModelConfig.isOcrReady(this@ImeSettingsActivity)
            }
            val hasConflict = (engine == ModelConfig.ENGINE_PP_OCR_TINY && ModelConfig.isOcrSmallReady(this@ImeSettingsActivity)) ||
                    (engine == ModelConfig.ENGINE_PP_OCR_SMALL && ModelConfig.isOcrTinyReady(this@ImeSettingsActivity))
            btn.text = if (ready) "重新下載" else if (hasConflict) "下載並替換模型" else "下載模型"

            val conflictNotice = if (engine == ModelConfig.ENGINE_PP_OCR_TINY && ModelConfig.isOcrSmallReady(this@ImeSettingsActivity)) {
                "\n\n⚠️ 注意：目前已安裝 Small 高精版模型，下載後將自動替換並清理舊模型檔案，兩者不重複佔用空間。"
            } else if (engine == ModelConfig.ENGINE_PP_OCR_SMALL && ModelConfig.isOcrTinyReady(this@ImeSettingsActivity)) {
                "\n\n⚠️ 注意：目前已安裝 Tiny 輕量版模型，下載後將自動替換並清理舊模型檔案，兩者不重複佔用空間。"
            } else {
                ""
            }

            AlertDialog.Builder(this@ImeSettingsActivity)
                .setTitle("下載 $engineLabel 模型")
                .setMessage(
                    "即將下載約 ${ModelDownloader.formatBytes(bytes)} 的模型檔案，下載會在背景進行，" +
                            "關閉螢幕或切換應用不會中斷。\n\n" +
                            "文字辨識與語音轉譯全程在裝置本機執行，僅下載步驟需使用網路。$conflictNotice\n\n是否繼續？"
                )
                .setPositiveButton(if (hasConflict) "替換並下載" else "開始下載") { _, _ ->
                    if (engine == ModelConfig.ENGINE_PP_OCR_TINY) {
                        ModelConfig.deleteOcrModel(this@ImeSettingsActivity, ModelConfig.ENGINE_PP_OCR_SMALL)
                    } else if (engine == ModelConfig.ENGINE_PP_OCR_SMALL) {
                        ModelConfig.deleteOcrModel(this@ImeSettingsActivity, ModelConfig.ENGINE_PP_OCR_TINY)
                    }
                    requestNotificationPermissionThenDownload(engine)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun requestNotificationPermissionThenDownload(engine: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownloadEngine = engine
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            beginDownload(engine)
        }
    }

    private fun beginDownload(engine: String) {
        ModelDownloadService.start(this, engine)
    }

    private fun renderDownloadStatus(active: ModelDownloadState.Active?) {
        if (active == null) {
            hideDownloadProgress(ModelConfig.ENGINE_X_ASR)
            hideDownloadProgress(ModelConfig.ENGINE_QWEN3)
            hideDownloadProgress(ModelConfig.ENGINE_PP_OCR_TINY)
            updateAllStatus()
            return
        }

        val isXasr = active.engine == ModelConfig.ENGINE_X_ASR
        val isQwen3 = active.engine == ModelConfig.ENGINE_QWEN3
        val btn = when {
            isXasr -> btnDownloadXasr
            isQwen3 -> btnDownloadQwen3
            else -> btnDownloadOcr
        }
        val progressIndicator = when {
            isXasr -> progressDownloadXasr
            isQwen3 -> progressDownloadQwen3
            else -> progressDownloadOcr
        }
        val tvStatus = when {
            isXasr -> tvDownloadStatusXasr
            isQwen3 -> tvDownloadStatusQwen3
            else -> tvDownloadStatusOcr
        }

        btn.isEnabled = true
        btn.text = "取消下載"
        progressIndicator.visibility = View.VISIBLE
        tvStatus.visibility = View.VISIBLE

        progressIndicator.isIndeterminate = active.progress.percent < 0
        if (active.progress.percent >= 0) {
            progressIndicator.progress = active.progress.percent
        }

        val elapsed = formatElapsed(System.currentTimeMillis() - active.startedAtMs)
        tvStatus.text = "${active.progress.label} ${if (active.progress.percent >= 0) "${active.progress.percent}%" else ""} · 已耗時 $elapsed"
    }

    private fun hideDownloadProgress(engine: String) {
        when (engine) {
            ModelConfig.ENGINE_X_ASR -> {
                progressDownloadXasr.visibility = View.GONE
                tvDownloadStatusXasr.visibility = View.GONE
                btnDownloadXasr.text = if (ModelConfig.isXAsrReady(this)) "重新下載" else "下載模型"
            }
            ModelConfig.ENGINE_QWEN3 -> {
                progressDownloadQwen3.visibility = View.GONE
                tvDownloadStatusQwen3.visibility = View.GONE
                btnDownloadQwen3.text = if (ModelConfig.isQwen3Ready(this)) "重新下載" else "下載模型"
            }
            else -> {
                progressDownloadOcr.visibility = View.GONE
                tvDownloadStatusOcr.visibility = View.GONE
                val sel = ModelConfig.selectedOcrModel(this)
                val selReady = ModelConfig.isOcrReady(this, sel)
                val hasConflict = (sel == ModelConfig.ENGINE_PP_OCR_TINY && ModelConfig.isOcrSmallReady(this)) ||
                        (sel == ModelConfig.ENGINE_PP_OCR_SMALL && ModelConfig.isOcrTinyReady(this))
                btnDownloadOcr.text = if (selReady) "重新下載" else if (hasConflict) "下載並替換模型" else "下載模型"
            }
        }
    }

    private fun formatElapsed(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs / 1000).coerceAtLeast(0)
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return if (m > 0) "${m} 分 ${s} 秒" else "${s} 秒"
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
