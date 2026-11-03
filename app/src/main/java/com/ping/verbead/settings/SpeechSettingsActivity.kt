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
import com.ping.verbead.util.label
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
    private lateinit var layoutSpokenNumbersToggle: View
    private lateinit var switchSpokenNumbers: MaterialSwitch
    private lateinit var layoutConvertTraditionalToggle: View
    private lateinit var switchConvertTraditional: MaterialSwitch
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

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, getString(R.string.settings_speech_toast_bluetooth_denied), Toast.LENGTH_SHORT).show()
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
        layoutSpokenNumbersToggle = findViewById(R.id.layout_spoken_numbers_toggle)
        switchSpokenNumbers = findViewById(R.id.switch_spoken_numbers)
        layoutConvertTraditionalToggle = findViewById(R.id.layout_convert_traditional_toggle)
        switchConvertTraditional = findViewById(R.id.switch_convert_traditional)
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
            switchPreferExternalAudio.toggle()
        }
        switchPreferExternalAudio.isChecked = ModelConfig.isPreferExternalAudio(this)
        updateAudioGainVisibility(switchPreferExternalAudio.isChecked)
        switchPreferExternalAudio.setOnCheckedChangeListener { _, isChecked ->
            HapticUtil.click(this)
            ModelConfig.setPreferExternalAudio(this, isChecked)
            updateAudioGainVisibility(isChecked)
            if (isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
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

        // Convert to Traditional Chinese
        layoutConvertTraditionalToggle.setOnClickListener {
            switchConvertTraditional.toggle()
        }
        switchConvertTraditional.isChecked = ModelConfig.isConvertToTraditionalEnabled(this)
        switchConvertTraditional.setOnCheckedChangeListener { _, isChecked ->
            HapticUtil.click(this)
            ModelConfig.setConvertToTraditionalEnabled(this, isChecked)
        }

        // Spoken numbers to digits
        layoutSpokenNumbersToggle.setOnClickListener {
            switchSpokenNumbers.toggle()
        }
        switchSpokenNumbers.isChecked = ModelConfig.isSpokenNumbersEnabled(this)
        switchSpokenNumbers.setOnCheckedChangeListener { _, isChecked ->
            HapticUtil.click(this)
            ModelConfig.setSpokenNumbersEnabled(this, isChecked)
        }

        // Filter punctuation
        layoutFilterPunctuationToggle.setOnClickListener {
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

    private fun audioGainLabel(gain: Float) = if (gain == 1.0f) getString(R.string.settings_speech_gain_original) else getString(R.string.settings_speech_gain_format, gain)
    private fun vadLabel(sec: Float) = getString(R.string.settings_speech_vad_format, sec)

    private fun updateAllStatus() {
        val xReady = ModelConfig.isXAsrReady(this)
        val qReady = ModelConfig.isQwen3Ready(this)
        val selected = ModelConfig.selectedEngine(this)
        val isDual = ModelConfig.isDualEngineActive(this)

        // Dual Engine
        switchDualEngine.isChecked = isDual

        // X-ASR
        tvStatusBadgeXasr.text = getString(if (xReady) R.string.common_ready else R.string.common_not_downloaded)
        btnDownloadXasr.text = getString(if (xReady) R.string.common_redownload else R.string.common_download_model)
        val isXSelected = !isDual && selected == ModelConfig.ENGINE_X_ASR
        btnSelectXasr.isEnabled = xReady && !isXSelected
        btnSelectXasr.text = getString(if (isXSelected) R.string.settings_speech_engine_in_use else R.string.settings_speech_engine_switch)
        ChoiceButtonUtil.applyChoiceState(btnSelectXasr, isXSelected)
        updateModelCardSelection(cardXAsr, isXSelected)

        // Qwen3-ASR
        tvStatusBadgeQwen3.text = getString(if (qReady) R.string.common_ready else R.string.common_not_downloaded)
        btnDownloadQwen3.text = getString(if (qReady) R.string.common_redownload else R.string.common_download_model)
        val isQSelected = !isDual && selected == ModelConfig.ENGINE_QWEN3
        btnSelectQwen3.isEnabled = qReady && !isQSelected
        btnSelectQwen3.text = getString(if (isQSelected) R.string.settings_speech_engine_in_use else R.string.settings_speech_engine_switch)
        ChoiceButtonUtil.applyChoiceState(btnSelectQwen3, isQSelected)
        updateModelCardSelection(cardQwen3, isQSelected)

        // Custom Dictionary word count
        val dictCount = try {
            UserDictionary.load(this).size
        } catch (_: Exception) { 0 }
        tvVocabDesc.text = if (dictCount > 0) {
            getString(R.string.settings_speech_vocab_desc_count, dictCount)
        } else {
            getString(R.string.settings_speech_vocab_desc)
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
            val name = getString(if (targetEngine == ModelConfig.ENGINE_X_ASR) R.string.settings_speech_engine_xasr_name else R.string.settings_speech_engine_qwen_name)
            Toast.makeText(this, getString(R.string.settings_speech_toast_engine_switched, name), Toast.LENGTH_SHORT).show()
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
            val msg = getString(if (next) R.string.settings_speech_toast_dual_on else R.string.settings_speech_toast_dual_off)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else {
            switchDualEngine.isChecked = false
            val missing = if (!xReady && !qReady) getString(R.string.settings_speech_missing_both) else if (!xReady) "X-ASR" else "Qwen3-ASR"
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_speech_dual_needs_model_title)
                .setMessage(getString(R.string.settings_speech_dual_needs_model_message, missing))
                .setPositiveButton(R.string.common_got_it, null)
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
        val label = getString(if (engine == ModelConfig.ENGINE_X_ASR) R.string.settings_speech_model_label_xasr else R.string.settings_speech_model_label_qwen)
        lifecycleScope.launch {
            val totalBytes = withContext(Dispatchers.IO) { downloader.estimateTotalBytes(target) }
            val totalMb = totalBytes / 1024 / 1024
            val ready = if (engine == ModelConfig.ENGINE_X_ASR) ModelConfig.isXAsrReady(this@SpeechSettingsActivity) else ModelConfig.isQwen3Ready(this@SpeechSettingsActivity)
            val action = if (ready) R.string.common_redownload else R.string.common_download

            AlertDialog.Builder(this@SpeechSettingsActivity)
                .setTitle(getString(if (ready) R.string.settings_speech_redownload_title else R.string.settings_speech_download_title, label))
                .setMessage(getString(R.string.settings_speech_download_message, totalMb))
                .setPositiveButton(action) { _, _ ->
                    HapticUtil.click(this@SpeechSettingsActivity)
                    requestNotifAndStartDownload(engine)
                }
                .setNegativeButton(R.string.btn_cancel, null)
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
        btn.text = getString(R.string.common_cancel_download)
        prog.visibility = View.VISIBLE
        prog.isIndeterminate = true
        tv.visibility = View.VISIBLE
        tv.text = getString(R.string.common_connecting)
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
        btn.text = getString(if (ready) R.string.common_redownload else R.string.common_download_model)
    }

    private fun observeDownloadState() {
        lifecycleScope.launch {
            ModelDownloadState.active.collect { active ->
                if (active != null && (active.engine == ModelConfig.ENGINE_X_ASR || active.engine == ModelConfig.ENGINE_QWEN3)) {
                    val (btn, prog, tv) = getDownloadViews(active.engine)
                    btn.text = getString(R.string.common_cancel_download)
                    prog.visibility = View.VISIBLE
                    tv.visibility = View.VISIBLE
                    prog.isIndeterminate = active.progress.percent < 0
                    if (active.progress.percent >= 0) prog.progress = active.progress.percent
                    tv.text = "${active.progress.label(this@SpeechSettingsActivity)} (${active.progress.percent}%)"
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
                        Toast.makeText(this@SpeechSettingsActivity, getString(R.string.settings_speech_toast_download_done), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
