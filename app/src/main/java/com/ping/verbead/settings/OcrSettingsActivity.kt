package com.ping.verbead.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.ping.verbead.ModelDownloadService
import com.ping.verbead.R
import com.ping.verbead.engine.ModelConfig
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

    private lateinit var btnSelectOcrTiny: MaterialButton
    private lateinit var btnSelectOcrSmall: MaterialButton

    private lateinit var layoutOcrAutoEnterToggle: View
    private lateinit var switchOcrAutoEnter: MaterialSwitch

    private lateinit var layoutOcrSmartStitchToggle: View
    private lateinit var switchOcrSmartStitch: MaterialSwitch

    private lateinit var layoutOcrPanguSpacingToggle: View
    private lateinit var switchOcrPanguSpacing: MaterialSwitch

    private lateinit var dividerOcrSep: View
    private lateinit var layoutOcrSepContainer: View
    private lateinit var btnOcrSepNewline: MaterialButton
    private lateinit var btnOcrSepSpace: MaterialButton
    private lateinit var btnOcrSepNone: MaterialButton

    private val downloader by lazy { ModelDownloader(this) }
    private var isDownloading = false

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (isDownloading) {
            beginDownload()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_ocr)

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

        btnSelectOcrTiny = findViewById(R.id.btn_select_ocr_tiny)
        btnSelectOcrSmall = findViewById(R.id.btn_select_ocr_small)

        layoutOcrAutoEnterToggle = findViewById(R.id.layout_ocr_auto_enter_toggle)
        switchOcrAutoEnter = findViewById(R.id.switch_ocr_auto_enter)

        layoutOcrSmartStitchToggle = findViewById(R.id.layout_ocr_smart_stitch_toggle)
        switchOcrSmartStitch = findViewById(R.id.switch_ocr_smart_stitch)

        layoutOcrPanguSpacingToggle = findViewById(R.id.layout_ocr_pangu_spacing_toggle)
        switchOcrPanguSpacing = findViewById(R.id.switch_ocr_pangu_spacing)

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
            val active = ModelDownloadState.active.value
            if (active != null && (active.engine == ModelConfig.ENGINE_PP_OCR_TINY || active.engine == ModelConfig.ENGINE_PP_OCR_SMALL)) {
                ModelDownloadService.cancel(this)
                hideDownloadProgress()
                updateAllStatus()
                return@setOnClickListener
            }

            val target = ModelDownloadSpec.forEngine(selectedModel)
            val modelName = if (selectedModel == ModelConfig.ENGINE_PP_OCR_SMALL) "PP-OCRv6 標準版" else "PP-OCRv6 輕量版"
            btnDownloadOcr.isEnabled = false
            btnDownloadOcr.text = "檢查檔案大小…"

            lifecycleScope.launch {
                val bytes = withContext(Dispatchers.IO) { downloader.estimateTotalBytes(target) }
                btnDownloadOcr.isEnabled = true
                val ready = isSelectedModelReady()
                btnDownloadOcr.text = if (ready) "重新下載" else "下載模型"

                AlertDialog.Builder(this@OcrSettingsActivity)
                    .setTitle("下載 $modelName")
                    .setMessage("即將下載約 ${ModelDownloader.formatBytes(bytes)} 的離線繁中辨識模型，下載完成後文字辨識全程在本機執行。\n\n是否繼續？")
                    .setPositiveButton("開始下載") { _, _ ->
                        HapticUtil.click(this@OcrSettingsActivity)
                        isDownloading = true
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(this@OcrSettingsActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            beginDownload()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
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
            HapticUtil.click(this)
            switchOcrAutoEnter.toggle()
        }
        switchOcrAutoEnter.isChecked = ModelConfig.isOcrAutoEnterEnabled(this)
        switchOcrAutoEnter.setOnCheckedChangeListener { _, isChecked ->
            HapticUtil.click(this)
            ModelConfig.setOcrAutoEnterEnabled(this, isChecked)
        }

        layoutOcrSmartStitchToggle.setOnClickListener {
            HapticUtil.click(this)
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
            HapticUtil.click(this)
            switchOcrPanguSpacing.toggle()
        }
        switchOcrPanguSpacing.isChecked = ModelConfig.isOcrPanguSpacingEnabled(this)
        switchOcrPanguSpacing.setOnCheckedChangeListener { _, isChecked ->
            HapticUtil.click(this)
            ModelConfig.setOcrPanguSpacingEnabled(this, isChecked)
        }
    }

    private fun isSelectedModelReady(): Boolean {
        val selected = ModelConfig.selectedOcrModel(this)
        return if (selected == ModelConfig.ENGINE_PP_OCR_SMALL) {
            ModelConfig.isOcrSmallReady(this)
        } else {
            ModelConfig.isOcrTinyReady(this)
        }
    }

    private fun beginDownload() {
        val selectedModel = ModelConfig.selectedOcrModel(this)
        ModelDownloadService.start(this, selectedModel)
        btnDownloadOcr.text = "取消下載"
        progressOcr.visibility = View.VISIBLE
        progressOcr.isIndeterminate = true
        tvOcrProgress.visibility = View.VISIBLE
        tvOcrProgress.text = "連線中…"
    }

    private fun hideDownloadProgress() {
        progressOcr.visibility = View.GONE
        tvOcrProgress.visibility = View.GONE
        val ready = isSelectedModelReady()
        btnDownloadOcr.text = if (ready) "重新下載" else "下載模型"
    }

    private fun updateAllStatus() {
        val selected = ModelConfig.selectedOcrModel(this)
        ChoiceButtonUtil.applyChoiceState(btnSelectOcrTiny, selected == ModelConfig.ENGINE_PP_OCR_TINY)
        ChoiceButtonUtil.applyChoiceState(btnSelectOcrSmall, selected == ModelConfig.ENGINE_PP_OCR_SMALL)

        val ready = isSelectedModelReady()
        tvOcrStatusBadge.text = if (ready) "已就緒" else "未下載"
        btnDownloadOcr.text = if (ready) "重新下載" else "下載模型"
        updateSepButtons()
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
                if (active != null && (active.engine == ModelConfig.ENGINE_PP_OCR_TINY || active.engine == ModelConfig.ENGINE_PP_OCR_SMALL)) {
                    btnDownloadOcr.text = "取消下載"
                    progressOcr.visibility = View.VISIBLE
                    tvOcrProgress.visibility = View.VISIBLE
                    progressOcr.isIndeterminate = active.progress.percent < 0
                    if (active.progress.percent >= 0) progressOcr.progress = active.progress.percent
                    tvOcrProgress.text = "${active.progress.label} (${active.progress.percent}%)"
                } else {
                    if (ModelDownloadState.active.value == null) {
                        hideDownloadProgress()
                        updateAllStatus()
                    }
                }
            }
        }
        lifecycleScope.launch {
            ModelDownloadState.results.collect { (engine, result) ->
                if (engine == ModelConfig.ENGINE_PP_OCR_TINY || engine == ModelConfig.ENGINE_PP_OCR_SMALL) {
                    hideDownloadProgress()
                    updateAllStatus()
                    if (result.isSuccess) {
                        Toast.makeText(this@OcrSettingsActivity, "PP-OCR 模型下載完成！", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
