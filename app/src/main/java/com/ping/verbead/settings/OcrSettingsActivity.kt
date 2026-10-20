package com.ping.verbead.settings

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import com.ping.verbead.engine.ModelDownloadSpec
import com.ping.verbead.engine.ModelDownloadState
import com.ping.verbead.R
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.engine.ModelDownloader
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

    private lateinit var btnOcrSepNewline: MaterialButton
    private lateinit var btnOcrSepSpace: MaterialButton
    private lateinit var btnOcrSepNone: MaterialButton

    private lateinit var switchOcrAutoEnter: MaterialSwitch
    private lateinit var switchOcrSmartStitch: MaterialSwitch
    private lateinit var switchOcrPanguSpacing: MaterialSwitch

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

        btnOcrSepNewline = findViewById(R.id.btn_ocr_sep_newline)
        btnOcrSepSpace = findViewById(R.id.btn_ocr_sep_space)
        btnOcrSepNone = findViewById(R.id.btn_ocr_sep_none)

        switchOcrAutoEnter = findViewById(R.id.switch_ocr_auto_enter)
        switchOcrSmartStitch = findViewById(R.id.switch_ocr_smart_stitch)
        switchOcrPanguSpacing = findViewById(R.id.switch_ocr_pangu_spacing)
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            HapticUtil.click(this)
            finish()
        }
    }

    private fun setupListeners() {
        btnDownloadOcr.setOnClickListener {
            val active = ModelDownloadState.active.value
            if (active != null && (active.engine == ModelConfig.ENGINE_PP_OCR_TINY || active.engine == ModelConfig.ENGINE_PP_OCR_SMALL)) {
                ModelDownloadService.cancel(this)
                hideDownloadProgress()
                updateAllStatus()
                return@setOnClickListener
            }

            val target = ModelDownloadSpec.forEngine(ModelConfig.ENGINE_PP_OCR_TINY)
            btnDownloadOcr.isEnabled = false
            btnDownloadOcr.text = "檢查檔案大小…"

            lifecycleScope.launch {
                val bytes = withContext(Dispatchers.IO) { downloader.estimateTotalBytes(target) }
                btnDownloadOcr.isEnabled = true
                val ready = ModelConfig.isOcrReady(this@OcrSettingsActivity)
                btnDownloadOcr.text = if (ready) "重新下載" else "下載模型"

                AlertDialog.Builder(this@OcrSettingsActivity)
                    .setTitle("下載 PP-OCR 文字辨識模型")
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

        switchOcrAutoEnter.isChecked = ModelConfig.isOcrAutoEnterEnabled(this)
        switchOcrAutoEnter.setOnCheckedChangeListener { _, isChecked ->
            HapticUtil.click(this)
            ModelConfig.setOcrAutoEnterEnabled(this, isChecked)
        }

        switchOcrSmartStitch.isChecked = ModelConfig.isOcrSmartStitchEnabled(this)
        switchOcrSmartStitch.setOnCheckedChangeListener { _, isChecked ->
            HapticUtil.click(this)
            ModelConfig.setOcrSmartStitchEnabled(this, isChecked)
        }

        switchOcrPanguSpacing.isChecked = ModelConfig.isOcrPanguSpacingEnabled(this)
        switchOcrPanguSpacing.setOnCheckedChangeListener { _, isChecked ->
            HapticUtil.click(this)
            ModelConfig.setOcrPanguSpacingEnabled(this, isChecked)
        }
    }

    private fun beginDownload() {
        ModelDownloadService.start(this, ModelConfig.ENGINE_PP_OCR_TINY)
        btnDownloadOcr.text = "取消下載"
        progressOcr.visibility = View.VISIBLE
        progressOcr.isIndeterminate = true
        tvOcrProgress.visibility = View.VISIBLE
        tvOcrProgress.text = "連線中…"
    }

    private fun hideDownloadProgress() {
        progressOcr.visibility = View.GONE
        tvOcrProgress.visibility = View.GONE
        val ready = ModelConfig.isOcrReady(this)
        btnDownloadOcr.text = if (ready) "重新下載" else "下載模型"
    }

    private fun updateAllStatus() {
        val ready = ModelConfig.isOcrReady(this)
        tvOcrStatusBadge.text = if (ready) "已就緒" else "未下載"
        btnDownloadOcr.text = if (ready) "重新下載" else "下載模型"
        updateSepButtons()
    }

    private fun updateSepButtons() {
        val currentSep = ModelConfig.ocrSeparator(this)
        val primaryColor = ContextCompat.getColor(this, R.color.md_theme_light_primary)
        val onPrimaryColor = ContextCompat.getColor(this, R.color.md_theme_light_onPrimary)
        val tonalColor = ContextCompat.getColor(this, R.color.md_theme_light_surfaceVariant)
        val onTonalColor = ContextCompat.getColor(this, R.color.md_theme_light_onSurfaceVariant)

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
