package com.ping.verbead.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
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
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.ping.verbead.ModelDownloadService
import com.ping.verbead.R
import com.ping.verbead.engine.ModelDownloadSpec
import com.ping.verbead.engine.ModelDownloadState
import com.ping.verbead.engine.ModelDownloader
import com.ping.verbead.slm.SlmInferenceEngine
import com.ping.verbead.slm.SlmModelConfig
import com.ping.verbead.util.ChoiceButtonUtil
import com.ping.verbead.util.HapticUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SlmSettingsActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar

    private lateinit var btnSlmModel08b: MaterialButton
    private lateinit var btnSlmModel2b: MaterialButton
    private lateinit var btnSlmModel4b: MaterialButton
    private lateinit var btnSlmModel9b: MaterialButton
    private lateinit var tvSlmDescription: TextView
    private lateinit var tvSlmStatus: TextView
    private lateinit var progressDownloadSlm: LinearProgressIndicator
    private lateinit var tvDownloadStatusSlm: TextView
    private lateinit var btnLoadSlmMemory: MaterialButton
    private lateinit var btnDownloadSlm: MaterialButton
    private lateinit var btnDeleteSlm: MaterialButton
    private lateinit var layoutSlmThinkingToggle: View
    private lateinit var switchSlmThinking: MaterialSwitch
    private lateinit var layoutSlmHardwareAccelToggle: View
    private lateinit var switchSlmHardwareAccel: MaterialSwitch

    // Custom prompt views
    private lateinit var cardPromptEditor: MaterialCardView
    private lateinit var etSlmPrompt: TextInputEditText
    private lateinit var btnResetSlmPrompt: MaterialButton
    private lateinit var btnSaveSlmPrompt: MaterialButton
    private lateinit var tvSlmPromptBadge: TextView

    private val downloader by lazy { ModelDownloader(this) }
    private var isDownloading = false

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (isDownloading) {
            val selected = SlmModelConfig.getSelectedModel(this)
            beginDownload(selected)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_slm)

        bindViews()
        setupToolbar()
        setupListeners()
        observeDownloadState()
    }

    override fun onResume() {
        super.onResume()
        updateAllStatus()
    }

    override fun onPause() {
        super.onPause()
        val text = etSlmPrompt.text?.toString()?.trim()
        if (!text.isNullOrBlank()) {
            val def = SlmModelConfig.getDefaultPrompt(this).trim()
            if (text != def) {
                SlmModelConfig.setCustomPrompt(this, text)
            }
        }
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)

        btnSlmModel08b = findViewById(R.id.btn_slm_model_0_8b)
        btnSlmModel2b = findViewById(R.id.btn_slm_model_2b)
        btnSlmModel4b = findViewById(R.id.btn_slm_model_4b)
        btnSlmModel9b = findViewById(R.id.btn_slm_model_9b)
        tvSlmDescription = findViewById(R.id.tv_slm_description)
        tvSlmStatus = findViewById(R.id.tv_slm_status)
        progressDownloadSlm = findViewById(R.id.progress_download_slm)
        tvDownloadStatusSlm = findViewById(R.id.tv_download_status_slm)
        btnLoadSlmMemory = findViewById(R.id.btn_load_slm_memory)
        btnDownloadSlm = findViewById(R.id.btn_download_slm)
        btnDeleteSlm = findViewById(R.id.btn_delete_slm)
        layoutSlmThinkingToggle = findViewById(R.id.layout_slm_thinking_toggle)
        switchSlmThinking = findViewById(R.id.switch_slm_thinking)
        layoutSlmHardwareAccelToggle = findViewById(R.id.layout_slm_hardware_accel_toggle)
        switchSlmHardwareAccel = findViewById(R.id.switch_slm_hardware_accel)

        cardPromptEditor = findViewById(R.id.card_prompt_editor)
        etSlmPrompt = findViewById(R.id.et_slm_prompt)
        btnResetSlmPrompt = findViewById(R.id.btn_reset_slm_prompt)
        btnSaveSlmPrompt = findViewById(R.id.btn_save_slm_prompt)
        tvSlmPromptBadge = findViewById(R.id.tv_slm_prompt_badge)
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            HapticUtil.click(this)
            finish()
        }
    }

    private fun setupListeners() {
        fun selectModel(id: String) {
            SlmModelConfig.setSelectedModel(this, id)
            HapticUtil.click(this)
            updateAllStatus()
        }

        btnSlmModel08b.setOnClickListener { selectModel(SlmModelConfig.MODEL_QWEN_0_8B) }
        btnSlmModel2b.setOnClickListener { selectModel(SlmModelConfig.MODEL_QWEN_2B) }
        btnSlmModel4b.setOnClickListener { selectModel(SlmModelConfig.MODEL_QWEN_4B) }
        btnSlmModel9b.setOnClickListener { selectModel(SlmModelConfig.MODEL_QWEN_9B) }

        btnLoadSlmMemory.setOnClickListener {
            HapticUtil.click(this)
            val selectedModel = SlmModelConfig.getSelectedModel(this)
            if (SlmInferenceEngine.isModelLoaded(selectedModel)) {
                SlmInferenceEngine.unloadModel(this)
                Toast.makeText(this, getString(R.string.toast_slm_unloaded_memory), Toast.LENGTH_SHORT).show()
                updateAllStatus()
            } else {
                if (SlmModelConfig.isModelDownloaded(this, selectedModel)) {
                    lifecycleScope.launch {
                        btnLoadSlmMemory.isEnabled = false
                        btnLoadSlmMemory.text = "載入中…"
                        val result = SlmInferenceEngine.loadModel(this@SlmSettingsActivity, selectedModel)
                        btnLoadSlmMemory.isEnabled = true
                        result.onSuccess {
                            val meta = SlmModelConfig.getModelMetadata(selectedModel)
                            Toast.makeText(this@SlmSettingsActivity, getString(R.string.toast_slm_loaded_memory, meta.displayName), Toast.LENGTH_SHORT).show()
                        }.onFailure { ex ->
                            Toast.makeText(this@SlmSettingsActivity, "記憶體載入失敗: ${ex.message}", Toast.LENGTH_LONG).show()
                        }
                        updateAllStatus()
                    }
                } else {
                    Toast.makeText(this, getString(R.string.toast_slm_not_downloaded), Toast.LENGTH_LONG).show()
                }
            }
        }

        btnDownloadSlm.setOnClickListener {
            HapticUtil.click(this)
            val selectedModel = SlmModelConfig.getSelectedModel(this)
            val active = ModelDownloadState.active.value
            if (active != null && active.engine == selectedModel) {
                ModelDownloadService.cancel(this)
                hideDownloadProgress()
                updateAllStatus()
                return@setOnClickListener
            }

            val target = ModelDownloadSpec.forEngine(selectedModel)
            val meta = SlmModelConfig.getModelMetadata(selectedModel)
            btnDownloadSlm.isEnabled = false
            btnDownloadSlm.text = "檢查檔案大小…"

            lifecycleScope.launch {
                val bytes = withContext(Dispatchers.IO) { downloader.estimateTotalBytes(target) }
                btnDownloadSlm.isEnabled = true
                val ready = SlmModelConfig.isModelDownloaded(this@SlmSettingsActivity, selectedModel)
                btnDownloadSlm.text = if (ready) "重新下載" else "下載模型"

                AlertDialog.Builder(this@SlmSettingsActivity)
                    .setTitle("下載 ${meta.displayName} 模型")
                    .setMessage(
                        "即將下載約 ${ModelDownloader.formatBytes(bytes)} 的模型檔案，下載會在背景進行。\n\n" +
                                "模型推論全程在手機本機 GPU / NPU 上運行，僅下載需使用網路。\n\n是否繼續？"
                    )
                    .setPositiveButton("開始下載") { _, _ ->
                        HapticUtil.click(this@SlmSettingsActivity)
                        isDownloading = true
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(this@SlmSettingsActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            beginDownload(selectedModel)
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        btnDeleteSlm.setOnClickListener {
            HapticUtil.click(this)
            val selectedModel = SlmModelConfig.getSelectedModel(this)
            val meta = SlmModelConfig.getModelMetadata(selectedModel)
            val file = SlmModelConfig.getModelFile(this, selectedModel)
            val sizeMb = if (file.exists()) file.length() / (1024 * 1024) else 0L
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_delete_slm_title)
                .setMessage(getString(R.string.dialog_delete_slm_message, meta.displayName, sizeMb))
                .setPositiveButton(R.string.btn_delete_model) { _, _ ->
                    if (SlmInferenceEngine.isModelLoaded(selectedModel)) {
                        SlmInferenceEngine.unloadModel(this)
                    }
                    val ok = SlmModelConfig.deleteModel(this, selectedModel)
                    if (ok) {
                        Toast.makeText(this, R.string.toast_slm_deleted, Toast.LENGTH_SHORT).show()
                    }
                    updateAllStatus()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        switchSlmThinking.setOnClickListener {
            val isChecked = switchSlmThinking.isChecked
            SlmModelConfig.setThinkingEnabled(this, isChecked)
            HapticUtil.click(this)
            updatePromptUI(forceReloadIfDefault = true)
        }
        layoutSlmThinkingToggle.setOnClickListener {
            switchSlmThinking.performClick()
        }

        switchSlmHardwareAccel.setOnClickListener {
            val isChecked = switchSlmHardwareAccel.isChecked
            SlmModelConfig.setHardwareAccelEnabled(this, isChecked)
            HapticUtil.click(this)
        }
        layoutSlmHardwareAccelToggle.setOnClickListener {
            switchSlmHardwareAccel.performClick()
        }

        // Custom prompt save and reset
        btnSaveSlmPrompt.setOnClickListener {
            val text = etSlmPrompt.text?.toString()?.trim() ?: ""
            HapticUtil.click(this)
            if (text.isBlank() || text == SlmModelConfig.getDefaultPrompt(this).trim()) {
                SlmModelConfig.resetCustomPrompt(this)
                Toast.makeText(this, "已恢復預設 Prompt", Toast.LENGTH_SHORT).show()
            } else {
                SlmModelConfig.setCustomPrompt(this, text)
                Toast.makeText(this, "自訂 Prompt 已儲存生效", Toast.LENGTH_SHORT).show()
            }
            updatePromptUI()
        }

        btnResetSlmPrompt.setOnClickListener {
            HapticUtil.click(this)
            AlertDialog.Builder(this)
                .setTitle("還原初始預設 Prompt")
                .setMessage("確定要捨棄目前的自訂內容，還原為系統初始的預設提示詞契約嗎？")
                .setPositiveButton("確認還原") { _, _ ->
                    SlmModelConfig.resetCustomPrompt(this)
                    val defaultPrompt = SlmModelConfig.getDefaultPrompt(this)
                    etSlmPrompt.setText(defaultPrompt)
                    HapticUtil.click(this)
                    Toast.makeText(this, "已還原為初始狀態", Toast.LENGTH_SHORT).show()
                    updatePromptUI()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // Prompt editor focus highlight
        etSlmPrompt.setOnFocusChangeListener { _, hasFocus ->
            cardPromptEditor.strokeColor = if (hasFocus) {
                ContextCompat.getColor(this, R.color.md_theme_light_primary)
            } else {
                ContextCompat.getColor(this, R.color.prompt_edit_box_stroke)
            }
            cardPromptEditor.strokeWidth = if (hasFocus) {
                (2 * resources.displayMetrics.density).toInt()
            } else {
                (1 * resources.displayMetrics.density).toInt()
            }
        }

        // Independent text scrolling mechanism within parent NestedScrollView
        @SuppressLint("ClickableViewAccessibility")
        etSlmPrompt.setOnTouchListener { v, event ->
            if (v.canScrollVertically(-1) || v.canScrollVertically(1)) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
            }
            false
        }
    }

    private fun beginDownload(engine: String) {
        ModelDownloadService.start(this, engine)
        btnDownloadSlm.text = "取消下載"
        progressDownloadSlm.visibility = View.VISIBLE
        progressDownloadSlm.isIndeterminate = true
        tvDownloadStatusSlm.visibility = View.VISIBLE
        tvDownloadStatusSlm.text = "連線中…"
    }

    private fun hideDownloadProgress() {
        progressDownloadSlm.visibility = View.GONE
        tvDownloadStatusSlm.visibility = View.GONE
        val selected = SlmModelConfig.getSelectedModel(this)
        val ready = SlmModelConfig.isModelDownloaded(this, selected)
        btnDownloadSlm.text = if (ready) "重新下載" else "下載模型"
    }

    private fun updateAllStatus() {
        val selectedSlm = SlmModelConfig.getSelectedModel(this)
        val slmMeta = SlmModelConfig.getModelMetadata(selectedSlm)
        val isSlmDownloaded = SlmModelConfig.isModelDownloaded(this, selectedSlm)
        val isSlmLoaded = SlmInferenceEngine.isModelLoaded(selectedSlm)

        ChoiceButtonUtil.applyChoiceState(btnSlmModel08b, selectedSlm == SlmModelConfig.MODEL_QWEN_0_8B)
        ChoiceButtonUtil.applyChoiceState(btnSlmModel2b, selectedSlm == SlmModelConfig.MODEL_QWEN_2B)
        ChoiceButtonUtil.applyChoiceState(btnSlmModel4b, selectedSlm == SlmModelConfig.MODEL_QWEN_4B)
        ChoiceButtonUtil.applyChoiceState(btnSlmModel9b, selectedSlm == SlmModelConfig.MODEL_QWEN_9B)

        tvSlmDescription.text = slmMeta.description

        if (isSlmLoaded) {
            tvSlmStatus.text = "已載入記憶體常駐 (${SlmInferenceEngine.getLoadedMemoryInfo()}) · 隨時可秒級推論"
            tvSlmStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success_text))
            btnLoadSlmMemory.isEnabled = true
            btnLoadSlmMemory.text = "釋放記憶體"
            btnDownloadSlm.text = "重新下載"
            btnDeleteSlm.visibility = View.VISIBLE
        } else if (isSlmDownloaded) {
            tvSlmStatus.text = "已下載就緒 (未常駐於記憶體)"
            tvSlmStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success_text))
            btnLoadSlmMemory.isEnabled = true
            btnLoadSlmMemory.text = "載入至記憶體"
            btnDownloadSlm.text = "重新下載"
            btnDeleteSlm.visibility = View.VISIBLE
        } else {
            tvSlmStatus.text = "未下載 (${slmMeta.quantizedSize})"
            tvSlmStatus.setTextColor(ContextCompat.getColor(this, R.color.status_warning_text))
            btnLoadSlmMemory.isEnabled = false
            btnLoadSlmMemory.text = "載入至記憶體"
            btnDownloadSlm.text = "下載模型"
            btnDeleteSlm.visibility = View.GONE
        }

        switchSlmThinking.isChecked = SlmModelConfig.isThinkingEnabled(this)
        switchSlmHardwareAccel.isChecked = SlmModelConfig.isHardwareAccelEnabled(this)

        updatePromptUI()
    }

    private fun updatePromptUI(forceReloadIfDefault: Boolean = false) {
        val isCustom = SlmModelConfig.isCustomPromptActive(this)
        if (isCustom) {
            tvSlmPromptBadge.text = "已自訂"
            tvSlmPromptBadge.setBackgroundResource(R.drawable.bg_status_badge_success)
            tvSlmPromptBadge.setTextColor(ContextCompat.getColor(this, R.color.status_success_text))
        } else {
            tvSlmPromptBadge.text = "預設狀態"
            tvSlmPromptBadge.setBackgroundResource(R.drawable.bg_status_badge_recommend)
            tvSlmPromptBadge.setTextColor(ContextCompat.getColor(this, R.color.tag_recommend))
        }

        // Pre-fill the prompt if not currently being typed into, or if force reload is requested
        if (!etSlmPrompt.hasFocus() || forceReloadIfDefault) {
            if (!isCustom) {
                val defaultPrompt = SlmModelConfig.getDefaultPrompt(this)
                if (etSlmPrompt.text?.toString() != defaultPrompt) {
                    etSlmPrompt.setText(defaultPrompt)
                }
            } else {
                val customPrompt = SlmModelConfig.getCustomPrompt(this)
                if (customPrompt != null && etSlmPrompt.text?.toString() != customPrompt) {
                    etSlmPrompt.setText(customPrompt)
                }
            }
        }
    }

    private fun observeDownloadState() {
        lifecycleScope.launch {
            ModelDownloadState.active.collect { active ->
                val selected = SlmModelConfig.getSelectedModel(this@SlmSettingsActivity)
                if (active != null && active.engine == selected) {
                    btnDownloadSlm.text = "取消下載"
                    progressDownloadSlm.visibility = View.VISIBLE
                    tvDownloadStatusSlm.visibility = View.VISIBLE
                    progressDownloadSlm.isIndeterminate = active.progress.percent < 0
                    if (active.progress.percent >= 0) progressDownloadSlm.progress = active.progress.percent
                    tvDownloadStatusSlm.text = "${active.progress.label} (${active.progress.percent}%)"
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
                val selected = SlmModelConfig.getSelectedModel(this@SlmSettingsActivity)
                if (engine == selected) {
                    hideDownloadProgress()
                    updateAllStatus()
                    if (result.isSuccess) {
                        Toast.makeText(this@SlmSettingsActivity, "SLM 模型下載完成！", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
