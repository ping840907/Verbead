package com.ping.verbead.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
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
import com.ping.verbead.DictSettingsActivity
import com.ping.verbead.ModelDownloadService
import com.ping.verbead.R
import com.ping.verbead.UserDictionary
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.engine.ModelDownloadSpec
import com.ping.verbead.engine.ModelDownloadState
import com.ping.verbead.engine.ModelDownloader
import com.ping.verbead.util.ChoiceButtonUtil
import com.ping.verbead.util.HapticUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SpeechSettingsActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar

    // Dual engine
    private lateinit var layoutDualEngineToggle: View
    private lateinit var switchDualEngine: MaterialSwitch

    // X-ASR
    private lateinit var cardXAsr: MaterialCardView
    private lateinit var btnDownloadXasr: MaterialButton
    private lateinit var btnSelectXasr: MaterialButton
    private lateinit var progressXasr: LinearProgressIndicator
    private lateinit var tvProgressXasr: TextView
    private lateinit var tvStatusBadgeXasr: TextView

    // Qwen3-ASR
    private lateinit var cardQwen3: MaterialCardView
    private lateinit var btnDownloadQwen3: MaterialButton
    private lateinit var btnSelectQwen3: MaterialButton
    private lateinit var progressQwen3: LinearProgressIndicator
    private lateinit var tvProgressQwen3: TextView
    private lateinit var tvStatusBadgeQwen3: TextView

    // Audio & VAD Preferences
    private lateinit var layoutPreferExternalAudioToggle: View
    private lateinit var switchPreferExternalAudio: MaterialSwitch
    private lateinit var layoutAudioGain: View
    private lateinit var seekAudioGain: SeekBar
    private lateinit var tvAudioGainVal: TextView
    private lateinit var seekVadSilence: SeekBar
    private lateinit var tvVadSilenceVal: TextView
    private lateinit var layoutFilterPunctuationToggle: View
    private lateinit var switchFilterPunctuation: MaterialSwitch

    // Custom dictionary option card
    private lateinit var cardCustomDict: MaterialCardView
    private lateinit var layoutDictToggle: View
    private lateinit var tvVocabDesc: TextView
    private lateinit var btnOpenDict: MaterialButton

    private val downloader by lazy { ModelDownloader(this) }
    private var pendingDownloadEngine: String? = null

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pendingDownloadEngine?.let { engine ->
            pendingDownloadEngine = null
            beginDownload(engine)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_speech)

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

        layoutDualEngineToggle = findViewById(R.id.layout_dual_engine_toggle)
        switchDualEngine = findViewById(R.id.switch_dual_engine)

        cardXAsr = findViewById(R.id.card_x_asr)
        btnDownloadXasr = findViewById(R.id.btn_download_xasr)
        btnSelectXasr = findViewById(R.id.btn_select_xasr)
        progressXasr = findViewById(R.id.progress_download_xasr)
        tvProgressXasr = findViewById(R.id.tv_download_status_xasr)
        tvStatusBadgeXasr = findViewById(R.id.tv_xasr_status)

        cardQwen3 = findViewById(R.id.card_qwen3)
        btnDownloadQwen3 = findViewById(R.id.btn_download_qwen3)
        btnSelectQwen3 = findViewById(R.id.btn_select_qwen3)
        progressQwen3 = findViewById(R.id.progress_download_qwen3)
        tvProgressQwen3 = findViewById(R.id.tv_download_status_qwen3)
        tvStatusBadgeQwen3 = findViewById(R.id.tv_qwen3_status)

        layoutPreferExternalAudioToggle = findViewById(R.id.layout_prefer_external_audio_toggle)
        switchPreferExternalAudio = findViewById(R.id.switch_prefer_external_audio)
        layoutAudioGain = findViewById(R.id.layout_audio_gain)
        seekAudioGain = findViewById(R.id.sb_audio_gain)
        tvAudioGainVal = findViewById(R.id.tv_audio_gain_value)
        seekVadSilence = findViewById(R.id.sb_vad_silence)
        tvVadSilenceVal = findViewById(R.id.tv_vad_value)
        layoutFilterPunctuationToggle = findViewById(R.id.layout_filter_punctuation_toggle)
        switchFilterPunctuation = findViewById(R.id.switch_filter_punctuation)

        cardCustomDict = findViewById(R.id.card_custom_dict)
        layoutDictToggle = findViewById(R.id.layout_dict_toggle)
        tvVocabDesc = findViewById(R.id.tv_vocab_desc)
        btnOpenDict = findViewById(R.id.btn_open_dict)
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            HapticUtil.click(this)
            finish()
        }
    }

    private fun setupListeners() {
        layoutDualEngineToggle.setOnClickListener { onDualEngineToggleClick() }
        switchDualEngine.setOnClickListener { onDualEngineToggleClick() }

        // X-ASR: clicking card or select button activates engine
        cardXAsr.setOnClickListener { onSelectButtonClick(ModelConfig.ENGINE_X_ASR) }
        btnSelectXasr.setOnClickListener { onSelectButtonClick(ModelConfig.ENGINE_X_ASR) }
        btnDownloadXasr.setOnClickListener { handleDownloadButtonClick(ModelConfig.ENGINE_X_ASR) }

        // Qwen3-ASR: clicking card or select button activates engine
        cardQwen3.setOnClickListener { onSelectButtonClick(ModelConfig.ENGINE_QWEN3) }
        btnSelectQwen3.setOnClickListener { onSelectButtonClick(ModelConfig.ENGINE_QWEN3) }
        btnDownloadQwen3.setOnClickListener { handleDownloadButtonClick(ModelConfig.ENGINE_QWEN3) }

        // External audio toggle
        layoutPreferExternalAudioToggle.setOnClickListener {
            HapticUtil.click(this)
            switchPreferExternalAudio.toggle()
        }
        switchPreferExternalAudio.isChecked = ModelConfig.isPreferExternalAudio(this)
        updateAudioGainVisibility(switchPreferExternalAudio.isChecked)
        switchPreferExternalAudio.setOnCheckedChangeListener { _, isChecked ->
            HapticUtil.click(this)
            ModelConfig.setPreferExternalAudio(this, isChecked)
            updateAudioGainVisibility(isChecked)
        }

        // Gain slider (1.0x - 3.0x in 0.1x steps)
        val curGain = ModelConfig.externalAudioGain(this)
        val curProgress = ((curGain - 1.0f) / 0.1f + 0.5f).toInt().coerceIn(0, 20)
        seekAudioGain.progress = curProgress
        tvAudioGainVal.text = audioGainLabel(curGain)
        seekAudioGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val gain = 1.0f + progress * 0.1f
                    ModelConfig.setExternalAudioGain(this@SpeechSettingsActivity, gain)
                    tvAudioGainVal.text = audioGainLabel(gain)
                    HapticUtil.tick(this@SpeechSettingsActivity)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // VAD silence slider (0.5s - 3.0s in 0.1s steps)
        val curVad = ModelConfig.vadSilenceSeconds(this)
        val vadProgress = ((curVad - 0.5f) / 0.1f + 0.5f).toInt().coerceIn(0, 25)
        seekVadSilence.progress = vadProgress
        tvVadSilenceVal.text = vadLabel(curVad)
        seekVadSilence.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val sec = 0.5f + progress * 0.1f
                    ModelConfig.setVadSilenceSeconds(this@SpeechSettingsActivity, sec)
                    tvVadSilenceVal.text = vadLabel(sec)
                    HapticUtil.tick(this@SpeechSettingsActivity)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // Filter punctuation
        layoutFilterPunctuationToggle.setOnClickListener {
            HapticUtil.click(this)
            switchFilterPunctuation.toggle()
        }
        switchFilterPunctuation.isChecked = ModelConfig.isFilterPunctuationEnabled(this)
        switchFilterPunctuation.setOnCheckedChangeListener { _, isChecked ->
            HapticUtil.click(this)
            ModelConfig.setFilterPunctuationEnabled(this, isChecked)
        }

        // Custom vocabulary option card navigation
        val openDictAction = {
            HapticUtil.click(this)
            startActivity(Intent(this, DictSettingsActivity::class.java))
        }
        cardCustomDict.setOnClickListener { openDictAction() }
        layoutDictToggle.setOnClickListener { openDictAction() }
        btnOpenDict.setOnClickListener { openDictAction() }
    }

    private fun audioGainLabel(gain: Float) = if (gain == 1.0f) "1.0x (原音)" else "%.1fx 增益".format(gain)
    private fun vadLabel(sec: Float) = "%.1f 秒".format(sec)

    private fun updateAllStatus() {
        val xReady = ModelConfig.isXAsrReady(this)
        val qReady = ModelConfig.isQwen3Ready(this)
        val selected = ModelConfig.selectedEngine(this)
        val isDual = ModelConfig.isDualEngineActive(this)

        // Dual Engine
        switchDualEngine.isChecked = isDual

        // X-ASR
        tvStatusBadgeXasr.text = if (xReady) "已就緒" else "未下載"
        btnDownloadXasr.text = if (xReady) "重新下載" else "下載模型"
        val isXSelected = !isDual && selected == ModelConfig.ENGINE_X_ASR
        btnSelectXasr.isEnabled = xReady && !isXSelected
        btnSelectXasr.text = if (isXSelected) "使用中" else "切換為此引擎"
        ChoiceButtonUtil.applyChoiceState(btnSelectXasr, isXSelected)
        updateModelCardSelection(cardXAsr, isXSelected)

        // Qwen3-ASR
        tvStatusBadgeQwen3.text = if (qReady) "已就緒" else "未下載"
        btnDownloadQwen3.text = if (qReady) "重新下載" else "下載模型"
        val isQSelected = !isDual && selected == ModelConfig.ENGINE_QWEN3
        btnSelectQwen3.isEnabled = qReady && !isQSelected
        btnSelectQwen3.text = if (isQSelected) "使用中" else "切換為此引擎"
        ChoiceButtonUtil.applyChoiceState(btnSelectQwen3, isQSelected)
        updateModelCardSelection(cardQwen3, isQSelected)

        // Custom Dictionary word count
        val dictCount = try {
            UserDictionary.load(this).size
        } catch (_: Exception) { 0 }
        tvVocabDesc.text = if (dictCount > 0) {
            "已收錄 $dictCount 筆自訂詞彙 · 點擊管理專屬詞庫"
        } else {
            "加入常用人名、專有名詞或修正字"
        }

        updateAudioGainVisibility(switchPreferExternalAudio.isChecked)
    }

    private fun updateModelCardSelection(card: MaterialCardView, isSelected: Boolean) {
        if (isSelected) {
            val primaryColor = ContextCompat.getColor(card.context, R.color.md_theme_light_primary)
            card.strokeColor = primaryColor
            card.strokeWidth = dpToPx(1.5f)
        } else {
            card.strokeColor = Color.TRANSPARENT
            card.strokeWidth = 0
        }
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun updateAudioGainVisibility(enabled: Boolean) {
        layoutAudioGain.visibility = if (enabled) View.VISIBLE else View.GONE
        seekAudioGain.isEnabled = enabled
    }

    private fun onSelectButtonClick(targetEngine: String) {
        val ready = if (targetEngine == ModelConfig.ENGINE_X_ASR) ModelConfig.isXAsrReady(this) else ModelConfig.isQwen3Ready(this)
        HapticUtil.click(this)
        if (ready) {
            val isDual = ModelConfig.isDualEngineActive(this)
            val curSelected = ModelConfig.selectedEngine(this)
            if (!isDual && curSelected == targetEngine) {
                return
            }
            ModelConfig.setSelectedEngine(this, targetEngine)
            ModelConfig.setDualEngineEnabled(this, false)
            updateAllStatus()
            val name = if (targetEngine == ModelConfig.ENGINE_X_ASR) "X-ASR 串流引擎" else "Qwen3-ASR 離線引擎"
            Toast.makeText(this, "已切換主辨識引擎為 $name", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.toast_model_not_downloaded, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onDualEngineToggleClick() {
        val xReady = ModelConfig.isXAsrReady(this)
        val qReady = ModelConfig.isQwen3Ready(this)
        if (xReady && qReady) {
            val next = !ModelConfig.isDualEngineEnabled(this)
            ModelConfig.setDualEngineEnabled(this, next)
            ModelConfig.setSelectedEngine(this, ModelConfig.ENGINE_QWEN3)
            HapticUtil.click(this)
            updateAllStatus()
            val msg = if (next) "已啟用雙引擎辨識模式" else "已停用雙引擎模式"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else {
            switchDualEngine.isChecked = false
            val missing = if (!xReady && !qReady) "X-ASR 與 Qwen3-ASR" else if (!xReady) "X-ASR" else "Qwen3-ASR"
            AlertDialog.Builder(this)
                .setTitle("需要下載模型以啟用雙引擎")
                .setMessage("雙引擎模式結合了 X-ASR 的低延遲即時預覽與 Qwen3-ASR 的高精離線轉譯。\n\n目前尚未下載：$missing。\n請先點選「下載模型」完成安裝後再開啟。")
                .setPositiveButton("我知道了", null)
                .show()
        }
    }

    private fun handleDownloadButtonClick(engine: String) {
        val active = ModelDownloadState.active.value
        if (active != null && active.engine == engine) {
            ModelDownloadService.cancel(this)
            return
        }
        val target = ModelDownloadSpec.forEngine(engine)
        val label = if (engine == ModelConfig.ENGINE_X_ASR) "X-ASR 繁中串流模型" else "Qwen3-ASR 語音模型"
        lifecycleScope.launch {
            val totalBytes = withContext(Dispatchers.IO) { downloader.estimateTotalBytes(target) }
            val totalMb = totalBytes / 1024 / 1024
            val ready = if (engine == ModelConfig.ENGINE_X_ASR) ModelConfig.isXAsrReady(this@SpeechSettingsActivity) else ModelConfig.isQwen3Ready(this@SpeechSettingsActivity)
            val action = if (ready) "重新下載" else "下載"

            AlertDialog.Builder(this@SpeechSettingsActivity)
                .setTitle("$action $label")
                .setMessage("預估大小：約 $totalMb MB。\n下載過程中將在背景維持前景服務，下載完成後即可離線運作。")
                .setPositiveButton(action) { _, _ ->
                    HapticUtil.click(this@SpeechSettingsActivity)
                    requestNotifAndStartDownload(engine)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun requestNotifAndStartDownload(engine: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownloadEngine = engine
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            beginDownload(engine)
        }
    }

    private fun beginDownload(engine: String) {
        ModelDownloadService.start(this, engine)
        val (btn, prog, tv) = getDownloadViews(engine)
        btn.text = "取消下載"
        prog.visibility = View.VISIBLE
        prog.isIndeterminate = true
        tv.visibility = View.VISIBLE
        tv.text = "連線中…"
    }

    private fun getDownloadViews(engine: String): Triple<MaterialButton, LinearProgressIndicator, TextView> {
        return if (engine == ModelConfig.ENGINE_X_ASR) {
            Triple(btnDownloadXasr, progressXasr, tvProgressXasr)
        } else {
            Triple(btnDownloadQwen3, progressQwen3, tvProgressQwen3)
        }
    }

    private fun hideDownloadProgress(engine: String) {
        val (btn, prog, tv) = getDownloadViews(engine)
        prog.visibility = View.GONE
        tv.visibility = View.GONE
        val ready = if (engine == ModelConfig.ENGINE_X_ASR) ModelConfig.isXAsrReady(this) else ModelConfig.isQwen3Ready(this)
        btn.text = if (ready) "重新下載" else "下載模型"
    }

    private fun observeDownloadState() {
        lifecycleScope.launch {
            ModelDownloadState.active.collect { active ->
                if (active != null && (active.engine == ModelConfig.ENGINE_X_ASR || active.engine == ModelConfig.ENGINE_QWEN3)) {
                    val (btn, prog, tv) = getDownloadViews(active.engine)
                    btn.text = "取消下載"
                    prog.visibility = View.VISIBLE
                    tv.visibility = View.VISIBLE
                    prog.isIndeterminate = active.progress.percent < 0
                    if (active.progress.percent >= 0) prog.progress = active.progress.percent
                    tv.text = "${active.progress.label} (${active.progress.percent}%)"
                } else {
                    if (ModelDownloadState.active.value == null) {
                        hideDownloadProgress(ModelConfig.ENGINE_X_ASR)
                        hideDownloadProgress(ModelConfig.ENGINE_QWEN3)
                        updateAllStatus()
                    }
                }
            }
        }
        lifecycleScope.launch {
            ModelDownloadState.results.collect { (engine, result) ->
                if (engine == ModelConfig.ENGINE_X_ASR || engine == ModelConfig.ENGINE_QWEN3) {
                    hideDownloadProgress(engine)
                    updateAllStatus()
                    if (result.isSuccess) {
                        Toast.makeText(this@SpeechSettingsActivity, "模型下載完成！", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
