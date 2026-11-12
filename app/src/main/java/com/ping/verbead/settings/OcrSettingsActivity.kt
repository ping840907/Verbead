package com.ping.verbead.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.ping.verbead.ui.GlassSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.ping.verbead.ModelDownloadService
import com.ping.verbead.R
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.util.LanguageSection
import com.ping.verbead.util.label
import com.ping.verbead.engine.ModelDownloader
import com.ping.verbead.engine.ModelDownloadSpec
import com.ping.verbead.engine.ModelDownloadState
import com.ping.verbead.util.ChoiceButtonUtil
import com.ping.verbead.util.HapticUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OcrSettingsActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar

    private lateinit var tvOcrStatusBadge: TextView
    private lateinit var btnDownloadOcr: MaterialButton
    private lateinit var progressOcr: LinearProgressIndicator
    private lateinit var tvOcrProgress: TextView

    private lateinit var tvKoreanStatus: TextView
    private lateinit var btnDownloadKorean: MaterialButton
    private lateinit var progressKorean: LinearProgressIndicator
    private lateinit var tvKoreanProgress: TextView
    private lateinit var layoutKoreanToggle: View
    private lateinit var switchKorean: GlassSwitch

    private lateinit var btnSelectOcrTiny: MaterialButton
    private lateinit var btnSelectOcrSmall: MaterialButton

    private lateinit var layoutOcrAutoEnterToggle: View
    private lateinit var switchOcrAutoEnter: GlassSwitch

    private lateinit var layoutOcrSmartStitchToggle: View
    private lateinit var switchOcrSmartStitch: GlassSwitch

    private lateinit var layoutOcrPanguSpacingToggle: View
    private lateinit var switchOcrPanguSpacing: GlassSwitch

    private lateinit var layoutOcrToTraditionalToggle: View
    private lateinit var switchOcrToTraditional: GlassSwitch

    private lateinit var dividerOcrSep: View
    private lateinit var layoutOcrSepContainer: View
    private lateinit var btnOcrSepNewline: MaterialButton
    private lateinit var btnOcrSepSpace: MaterialButton
    private lateinit var btnOcrSepNone: MaterialButton

    private val downloader by lazy { ModelDownloader(this) }
    private var isDownloading = false
    private var pendingEngine: String? = null

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (isDownloading) {
            beginDownload(pendingEngine ?: ModelConfig.selectedOcrModel(this))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_ocr)
        com.ping.verbead.ui.GlassStage.install(this)

        bindViews()
        setupToolbar()
        setupListeners()
        observeDownloadState()
    }

    override fun onResume() {
        super.onResume()
        updateAllStatus()
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)

        tvOcrStatusBadge = findViewById(R.id.tv_ocr_status)
        btnDownloadOcr = findViewById(R.id.btn_download_ocr)
        progressOcr = findViewById(R.id.progress_download_ocr)
        tvOcrProgress = findViewById(R.id.tv_download_status_ocr)

        tvKoreanStatus = findViewById(R.id.tv_ocr_korean_status)
        btnDownloadKorean = findViewById(R.id.btn_download_ocr_korean)
        progressKorean = findViewById(R.id.progress_download_ocr_korean)
        tvKoreanProgress = findViewById(R.id.tv_download_status_ocr_korean)
        layoutKoreanToggle = findViewById(R.id.layout_ocr_korean_toggle)
        switchKorean = findViewById(R.id.switch_ocr_korean)

        btnSelectOcrTiny = findViewById(R.id.btn_select_ocr_tiny)
        btnSelectOcrSmall = findViewById(R.id.btn_select_ocr_small)

        layoutOcrAutoEnterToggle = findViewById(R.id.layout_ocr_auto_enter_toggle)
        switchOcrAutoEnter = findViewById(R.id.switch_ocr_auto_enter)

        layoutOcrSmartStitchToggle = findViewById(R.id.layout_ocr_smart_stitch_toggle)
        switchOcrSmartStitch = findViewById(R.id.switch_ocr_smart_stitch)

        layoutOcrPanguSpacingToggle = findViewById(R.id.layout_ocr_pangu_spacing_toggle)
        switchOcrPanguSpacing = findViewById(R.id.switch_ocr_pangu_spacing)

        LanguageSection(
            context = this,
            id = "ocr_chinese",
            header = findViewById(R.id.section_chinese_ocr_header),
            content = findViewById(R.id.section_chinese_ocr_content),
            title = getString(R.string.language_section_chinese_title),
        ).refresh()
        layoutOcrToTraditionalToggle = findViewById(R.id.layout_ocr_to_traditional_toggle)
        switchOcrToTraditional = findViewById(R.id.switch_ocr_to_traditional)

        dividerOcrSep = findViewById(R.id.divider_ocr_sep)
        layoutOcrSepContainer = findViewById(R.id.layout_ocr_sep_container)
        btnOcrSepNewline = findViewById(R.id.btn_ocr_sep_newline)
        btnOcrSepSpace = findViewById(R.id.btn_ocr_sep_space)
        btnOcrSepNone = findViewById(R.id.btn_ocr_sep_none)
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            HapticUtil.click(this)
            finish()
        }
    }

    private fun setupListeners() {
        btnSelectOcrTiny.setOnClickListener {
            HapticUtil.click(this)
            ModelConfig.setSelectedOcrModel(this, ModelConfig.ENGINE_PP_OCR_TINY)
            updateAllStatus()
        }

        btnSelectOcrSmall.setOnClickListener {
            HapticUtil.click(this)
            ModelConfig.setSelectedOcrModel(this, ModelConfig.ENGINE_PP_OCR_SMALL)
            updateAllStatus()
        }

        btnDownloadOcr.setOnClickListener {
            val selectedModel = ModelConfig.selectedOcrModel(this)
            val modelName = getString(if (selectedModel == ModelConfig.ENGINE_PP_OCR_SMALL) R.string.ocr_model_name_small else R.string.ocr_model_name_tiny)
            onDownloadButton(selectedModel, modelName, R.string.settings_ocr_download_message, btnDownloadOcr) { isModelReady(selectedModel) }
        }

        btnDownloadKorean.setOnClickListener {
            onDownloadButton(
                ModelConfig.OCR_MODEL_KOREAN,
                getString(R.string.ocr_model_name_korean),
                R.string.settings_ocr_korean_download_message,
                btnDownloadKorean
            ) { ModelConfig.isOcrKoreanReady(this) }
        }

        layoutKoreanToggle.setOnClickListener { switchKorean.toggle() }
        switchKorean.isChecked = ModelConfig.isOcrKoreanEnabled(this)
        switchKorean.setOnCheckedChangeListener { _, isChecked ->
            HapticUtil.click(this)
            ModelConfig.setOcrKoreanEnabled(this, isChecked)
        }

        fun updateSep(sep: String) {
            ModelConfig.setOcrSeparator(this, sep)
            HapticUtil.click(this)
            updateSepButtons()

        }

        btnOcrSepNewline.setOnClickListener { updateSep("\n") }
        btnOcrSepSpace.setOnClickListener { updateSep(" ") }
        btnOcrSepNone.setOnClickListener { updateSep("") }

        layoutOcrAutoEnterToggle.setOnClickListener {
            switchOcrAutoEnter.toggle()
        }
        switchOcrAutoEnter.isChecked = ModelConfig.isOcrAutoEnterEnabled(this)
        switchOcrAutoEnter.setOnCheckedChangeListener { _, isChecked ->
            HapticUtil.click(this)
            ModelConfig.setOcrAutoEnterEnabled(this, isChecked)
        }

        layoutOcrSmartStitchToggle.setOnClickListener {
            switchOcrSmartStitch.toggle()
        }
        switchOcrSmartStitch.isChecked = ModelConfig.isOcrSmartStitchEnabled(this)
        updateSmartStitchUI(switchOcrSmartStitch.isChecked)
        switchOcrSmartStitch.setOnCheckedChangeListener { _, isChecked ->
            HapticUtil.click(this)
            ModelConfig.setOcrSmartStitchEnabled(this, isChecked)
            updateSmartStitchUI(isChecked)
        }

        layoutOcrPanguSpacingToggle.setOnClickListener {
            switchOcrPanguSpacing.toggle()
        }
        switchOcrPanguSpacing.isChecked = ModelConfig.isOcrPanguSpacingEnabled(this)
        switchOcrPanguSpacing.setOnCheckedChangeListener { _, isChecked ->
            HapticUtil.click(this)
            ModelConfig.setOcrPanguSpacingEnabled(this, isChecked)
        }

        layoutOcrToTraditionalToggle.setOnClickListener {
            switchOcrToTraditional.toggle()
        }
        switchOcrToTraditional.isChecked = ModelConfig.isOcrToTraditionalEnabled(this)
        switchOcrToTraditional.setOnCheckedChangeListener { _, isChecked ->
            HapticUtil.click(this)
            ModelConfig.setOcrToTraditionalEnabled(this, isChecked)
        }
    }

    private fun isModelReady(model: String): Boolean = when (model) {
        ModelConfig.ENGINE_PP_OCR_SMALL -> ModelConfig.isOcrSmallReady(this)
        ModelConfig.OCR_MODEL_KOREAN -> ModelConfig.isOcrKoreanReady(this)
        else -> ModelConfig.isOcrTinyReady(this)
    }

    private fun isSelectedModelReady(): Boolean = isModelReady(ModelConfig.selectedOcrModel(this))

    private fun isOcrEngine(engine: String) =
        engine == ModelConfig.ENGINE_PP_OCR_TINY || engine == ModelConfig.ENGINE_PP_OCR_SMALL || engine == ModelConfig.OCR_MODEL_KOREAN

    /** Shared by the main OCR model and the Korean model: cancel a running download, or size it up and ask before starting. */
    private fun onDownloadButton(
        engine: String,
        modelName: String,
        messageRes: Int,
        button: MaterialButton,
        isReady: () -> Boolean
    ) {
        val active = ModelDownloadState.active.value
        if (active != null && active.engine == engine) {
            ModelDownloadService.cancel(this)
            hideDownloadProgress()
            updateAllStatus()
            return
        }

        val target = ModelDownloadSpec.forEngine(engine)
        button.isEnabled = false
        button.text = getString(R.string.settings_ocr_checking_size)

        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { downloader.estimateTotalBytes(target) }
            button.isEnabled = true
            button.text = getString(if (isReady()) R.string.common_redownload else R.string.common_download_model)

            AlertDialog.Builder(this@OcrSettingsActivity)
                .setTitle(getString(R.string.settings_speech_download_title, modelName))
                .setMessage(getString(messageRes, if (bytes < 0) getString(R.string.common_size_unknown) else ModelDownloader.formatBytes(bytes)))
                .setPositiveButton(R.string.settings_ocr_download_start) { _, _ ->
                    HapticUtil.click(this@OcrSettingsActivity)
                    isDownloading = true
                    pendingEngine = engine
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(this@OcrSettingsActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        beginDownload(engine)
                    }
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }
    }

    private fun beginDownload(engine: String) {
        ModelDownloadService.start(this, engine)
        val (button, progress, text) = if (engine == ModelConfig.OCR_MODEL_KOREAN) {
            Triple(btnDownloadKorean, progressKorean, tvKoreanProgress)
        } else {
            Triple(btnDownloadOcr, progressOcr, tvOcrProgress)
        }
        button.text = getString(R.string.common_cancel_download)
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        text.visibility = View.VISIBLE
        text.text = getString(R.string.common_connecting)
    }

    private fun hideDownloadProgress() {
        progressOcr.visibility = View.GONE
        tvOcrProgress.visibility = View.GONE
        btnDownloadOcr.text = getString(if (isSelectedModelReady()) R.string.common_redownload else R.string.common_download_model)
        progressKorean.visibility = View.GONE
        tvKoreanProgress.visibility = View.GONE
        btnDownloadKorean.text = getString(if (ModelConfig.isOcrKoreanReady(this)) R.string.common_redownload else R.string.common_download_model)
    }

    private fun updateAllStatus() {
        val selected = ModelConfig.selectedOcrModel(this)
        ChoiceButtonUtil.applyChoiceState(btnSelectOcrTiny, selected == ModelConfig.ENGINE_PP_OCR_TINY)
        ChoiceButtonUtil.applyChoiceState(btnSelectOcrSmall, selected == ModelConfig.ENGINE_PP_OCR_SMALL)

        val ready = isSelectedModelReady()
        tvOcrStatusBadge.text = getString(if (ready) R.string.common_ready else R.string.common_not_downloaded)
        btnDownloadOcr.text = getString(if (ready) R.string.common_redownload else R.string.common_download_model)
        updateSepButtons()

        val koreanReady = ModelConfig.isOcrKoreanReady(this)
        tvKoreanStatus.text = getString(if (koreanReady) R.string.common_ready else R.string.common_not_downloaded)
        btnDownloadKorean.text = getString(if (koreanReady) R.string.common_redownload else R.string.common_download_model)

        updateSmartStitchUI(switchOcrSmartStitch.isChecked)
    }

    private fun updateSmartStitchUI(isSmartStitchEnabled: Boolean) {
        // When smart paragraph stitching is enabled, hide manual multi-block separator choices
        layoutOcrSepContainer.visibility = if (isSmartStitchEnabled) View.GONE else View.VISIBLE
        dividerOcrSep.visibility = if (isSmartStitchEnabled) View.GONE else View.VISIBLE
    }

    private fun updateSepButtons() {
        val currentSep = ModelConfig.ocrSeparator(this)
        ChoiceButtonUtil.applyChoiceState(btnOcrSepNewline, currentSep == "\n")
        ChoiceButtonUtil.applyChoiceState(btnOcrSepSpace, currentSep == " ")
        ChoiceButtonUtil.applyChoiceState(btnOcrSepNone, currentSep == "")
    }

    private fun observeDownloadState() {
        lifecycleScope.launch {
            ModelDownloadState.active.collect { active ->
                if (active != null && isOcrEngine(active.engine)) {
                    val korean = active.engine == ModelConfig.OCR_MODEL_KOREAN
                    val button = if (korean) btnDownloadKorean else btnDownloadOcr
                    val progress = if (korean) progressKorean else progressOcr
                    val text = if (korean) tvKoreanProgress else tvOcrProgress
                    button.text = getString(R.string.common_cancel_download)
                    progress.visibility = View.VISIBLE
                    text.visibility = View.VISIBLE
                    progress.isIndeterminate = active.progress.percent < 0
                    if (active.progress.percent >= 0) progress.progress = active.progress.percent
                    text.text = "${active.progress.label(this@OcrSettingsActivity)} (${active.progress.percent}%)"
                } else {
                    if (ModelDownloadState.active.value == null) {
                        hideDownloadProgress()
                        updateAllStatus()
                    }
                }
            }
        }
        lifecycleScope.launch {
            ModelDownloadState.results.collect { (engine, _) ->
                if (isOcrEngine(engine)) {
                    hideDownloadProgress()
                    updateAllStatus()
                }
            }
        }
    }
}
