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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.ping.verbead.DictSettingsActivity
import com.ping.verbead.ModelDownloadService
import com.ping.verbead.R
import com.ping.verbead.UserDictionary
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.engine.AsrCatalog
import com.ping.verbead.engine.AsrMode
import com.ping.verbead.engine.AsrModel
import com.ping.verbead.engine.AsrRecommender
import com.ping.verbead.engine.AsrTier
import java.util.Locale
import com.ping.verbead.util.LanguageSection
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

    // Speech model list
    private lateinit var tvComboLive: TextView
    private lateinit var tvComboFinal: TextView
    private lateinit var btnClearLive: View
    private lateinit var btnClearFinal: View
    private lateinit var tvAsrMemoryHint: TextView
    private lateinit var tvAsrRamTotal: TextView
    private lateinit var chipsKind: ChipGroup
    private lateinit var chipDownloadedOnly: Chip
    private lateinit var btnLanguageFilter: MaterialButton
    private lateinit var layoutModels: android.widget.LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvCustomProblems: TextView

    // Quick setup
    private lateinit var btnPrimaryLanguage: MaterialButton
    private lateinit var chipsProfile: ChipGroup
    private lateinit var switchPreview: MaterialSwitch
    private lateinit var layoutPreviewToggle: View
    private lateinit var tvRecNone: TextView
    private lateinit var layoutAdvanced: View
    private lateinit var ivAdvancedArrow: android.widget.ImageView
    private lateinit var quickMain: QuickRow
    private lateinit var quickPreview: QuickRow
    private var previewWanted = false
    private var renderingQuick = false
    private lateinit var chipsMode: ChipGroup

    /** The kind of model the quick setup recommends as the main one; null = follow what is selected now. */
    private var quickMode: AsrMode? = null

    /** Models the user asked to use right after their download finishes (from the quick setup). */
    private val pendingUse = HashMap<String, Boolean>() // model id -> used as the extra live preview

    private class QuickRow(
        val root: View,
        val name: TextView,
        val info: TextView,
        val action: MaterialButton,
        val progress: LinearProgressIndicator,
        val progressText: TextView,
    ) {
        var modelId: String? = null
    }

    private var filterMode: AsrMode? = null
    private var filterLanguage: String? = null

    /** The views of each model card currently shown, by model id (the download progress is drawn into them). */
    private class CardViews(
        val card: MaterialCardView,
        val action: MaterialButton,
        val progress: LinearProgressIndicator,
        val progressText: TextView,
    )
    private val cards = HashMap<String, CardViews>()

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
    private lateinit var layoutFilterHalfPunctToggle: View
    private lateinit var switchFilterHalfPunct: MaterialSwitch


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
        ModelConfig.reloadCustomModels(this)
        updateAllStatus()
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)

        tvComboLive = findViewById(R.id.tv_combo_live)
        tvComboFinal = findViewById(R.id.tv_combo_final)
        btnClearLive = findViewById(R.id.btn_combo_clear_live)
        btnClearFinal = findViewById(R.id.btn_combo_clear_final)
        btnClearLive.setOnClickListener { clearSlot(AsrMode.LIVE) }
        btnClearFinal.setOnClickListener { clearSlot(AsrMode.FINAL) }
        tvAsrMemoryHint = findViewById(R.id.tv_asr_memory_hint)
        tvAsrRamTotal = findViewById(R.id.tv_asr_ram_total)
        chipsKind = findViewById(R.id.chips_asr_kind)
        chipDownloadedOnly = findViewById(R.id.chip_downloaded_only)
        btnLanguageFilter = findViewById(R.id.btn_asr_language_filter)
        layoutModels = findViewById(R.id.layout_asr_models)
        tvEmpty = findViewById(R.id.tv_asr_empty)
        tvCustomProblems = findViewById(R.id.tv_asr_custom_problems)

        btnPrimaryLanguage = findViewById(R.id.btn_asr_primary_language)
        chipsProfile = findViewById(R.id.chips_asr_profile)
        chipsMode = findViewById(R.id.chips_asr_mode)
        chipsMode.setOnCheckedStateChangeListener { _, ids ->
            if (renderingQuick) return@setOnCheckedStateChangeListener
            quickMode = if (ids.firstOrNull() == R.id.chip_mode_live) AsrMode.LIVE else AsrMode.FINAL
            renderQuick()
        }
        switchPreview = findViewById(R.id.switch_asr_preview)
        layoutPreviewToggle = findViewById(R.id.layout_preview_toggle)
        tvRecNone = findViewById(R.id.tv_rec_none)
        layoutAdvanced = findViewById(R.id.layout_asr_advanced)
        ivAdvancedArrow = findViewById(R.id.iv_advanced_arrow)
        quickMain = inflateQuickRow(findViewById(R.id.layout_quick_main))
        quickPreview = inflateQuickRow(findViewById(R.id.layout_quick_preview))
        previewWanted = ModelConfig.selectedAsrModel(this, AsrMode.LIVE) != null && ModelConfig.selectedAsrModel(this, AsrMode.FINAL) != null

        btnPrimaryLanguage.setOnClickListener {
            showLanguagePicker(ModelConfig.asrPrimaryLanguage(this), withAll = false) { tag ->
                if (tag != null) ModelConfig.setAsrPrimaryLanguage(this, tag)
                renderQuick()
            }
        }
        chipsProfile.setOnCheckedStateChangeListener { _, ids ->
            if (renderingQuick) return@setOnCheckedStateChangeListener
            val tier = when (ids.firstOrNull()) {
                R.id.chip_profile_light -> AsrTier.LIGHT
                R.id.chip_profile_best -> AsrTier.BEST
                else -> AsrTier.BALANCED
            }
            ModelConfig.setAsrProfile(this, tier)
            renderQuick()
        }
        layoutPreviewToggle.setOnClickListener { switchPreview.toggle() }
        switchPreview.setOnCheckedChangeListener { _, checked ->
            if (renderingQuick) return@setOnCheckedChangeListener
            previewWanted = checked
            // turning it off puts the live model away, as long as a final model remains to record with
            if (!checked && ModelConfig.selectedAsrModel(this, AsrMode.FINAL) != null) {
                ModelConfig.setAsrModel(this, AsrMode.LIVE, null)
            }
            updateAllStatus()
        }
        findViewById<View>(R.id.layout_advanced_header).setOnClickListener {
            val show = layoutAdvanced.visibility != View.VISIBLE
            layoutAdvanced.visibility = if (show) View.VISIBLE else View.GONE
            ivAdvancedArrow.setImageResource(if (show) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down)
        }

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
        layoutFilterHalfPunctToggle = findViewById(R.id.layout_filter_half_punct_toggle)
        switchFilterHalfPunct = findViewById(R.id.switch_filter_half_punct)


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
        chipsKind.setOnCheckedStateChangeListener { _, ids ->
            filterMode = when (ids.firstOrNull()) {
                R.id.chip_kind_live -> AsrMode.LIVE
                R.id.chip_kind_final -> AsrMode.FINAL
                else -> null
            }
            renderModels()
        }
        chipDownloadedOnly.setOnCheckedChangeListener { _, _ -> renderModels() }
        btnLanguageFilter.setOnClickListener { showLanguageFilterDialog() }

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
        setupChineseSection()

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

        // Filter half-width punctuation
        layoutFilterHalfPunctToggle.setOnClickListener {
            switchFilterHalfPunct.toggle()
        }
        switchFilterHalfPunct.isChecked = ModelConfig.isFilterHalfWidthPunctuationEnabled(this)
        switchFilterHalfPunct.setOnCheckedChangeListener { _, isChecked ->
            HapticUtil.click(this)
            ModelConfig.setFilterHalfWidthPunctuationEnabled(this, isChecked)
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

    private fun setupChineseSection() {
        LanguageSection(
            context = this,
            id = "speech_chinese",
            header = findViewById(R.id.section_chinese_header),
            content = findViewById(R.id.section_chinese_content),
            title = getString(R.string.language_section_chinese_title),
        ).refresh()
    }

    private fun audioGainLabel(gain: Float) = if (gain == 1.0f) getString(R.string.settings_speech_gain_original) else getString(R.string.settings_speech_gain_format, gain)
    private fun vadLabel(sec: Float) = getString(R.string.settings_speech_vad_format, sec)

    private fun updateAllStatus() {
        renderQuick()
        renderModels()

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

    // ── Speech model list ─────────────────────────────────────────────────────

    private fun clearSlot(mode: AsrMode) {
        HapticUtil.click(this)
        ModelConfig.setAsrModel(this, mode, null)
        updateAllStatus()
    }

    // ── Quick setup ───────────────────────────────────────────────────────────

    private fun inflateQuickRow(container: android.widget.LinearLayout): QuickRow {
        val v = layoutInflater.inflate(R.layout.item_asr_quick_row, container, false)
        container.addView(v)
        return QuickRow(
            container, v.findViewById(R.id.tv_quick_name), v.findViewById(R.id.tv_quick_info),
            v.findViewById(R.id.btn_quick_action), v.findViewById(R.id.progress_quick), v.findViewById(R.id.tv_quick_progress),
        )
    }

    /**
     * Puts a recommended model to use. The quick setup describes the whole choice, so a live model used as the main
     * model stands alone, and a final model has the live preview only when the preview switch is on.
     */
    private fun applyQuickModel(model: AsrModel, asPreview: Boolean) {
        ModelConfig.setAsrModel(this, model.mode, model.id)
        if (asPreview) return
        if (model.mode == AsrMode.LIVE) {
            ModelConfig.setAsrModel(this, AsrMode.FINAL, null)
        } else if (!previewWanted) {
            ModelConfig.setAsrModel(this, AsrMode.LIVE, null)
        }
    }

    /** Fills one recommendation row: the model, what it costs, and a single button that does the right thing. */
    private fun bindQuickRow(row: QuickRow, model: AsrModel, asPreview: Boolean) {
        row.modelId = model.id
        val ready = ModelConfig.isModelReady(this, model)
        val inSlot = ModelConfig.selectedAsrModel(this, model.mode)?.id == model.id
        // a live model as the main one is in use only when it is the only one
        val inUse = inSlot && (asPreview || model.mode == AsrMode.FINAL || ModelConfig.selectedAsrModel(this, AsrMode.FINAL) == null)
        row.name.text = model.name
        val desc = model.descriptionFor(resources.configuration.locales[0])
        val cost = listOfNotNull(
            if (!ready && model.sizeMb > 0) getString(R.string.asr_model_size_format, model.sizeMb) else null,
            if (model.ramMb > 0) getString(R.string.asr_ram_format, AsrCatalog.formatMb(model.ramMb)) else null,
        ).joinToString("\u30FB")
        row.info.text = if (cost.isEmpty()) desc else desc + "\n" + cost
        row.action.text = when {
            inUse -> getString(R.string.asr_rec_in_use)
            ready -> getString(R.string.asr_rec_use)
            else -> getString(R.string.asr_rec_download_use)
        }
        row.action.isEnabled = !inUse
        ChoiceButtonUtil.applyChoiceState(row.action, inUse)
        row.action.setOnClickListener {
            HapticUtil.click(this)
            if (ready) {
                applyQuickModel(model, asPreview)
                updateAllStatus()
            } else {
                handleDownloadButtonClick(model.id, useAfter = true, asPreview = asPreview)
            }
        }
    }

    /** The quick-setup card: a recommendation for the user's language and profile, and the optional live preview. */
    private fun renderQuick() {
        renderingQuick = true
        try {
            val lang = ModelConfig.asrPrimaryLanguage(this)
            val tier = ModelConfig.asrProfile(this)
            btnPrimaryLanguage.text = getString(R.string.asr_simple_language_format, AsrCatalog.languageName(lang, Locale.getDefault()))
            chipsProfile.check(when (tier) {
                AsrTier.LIGHT -> R.id.chip_profile_light
                AsrTier.BEST -> R.id.chip_profile_best
                else -> R.id.chip_profile_balanced
            })

            val models = AsrCatalog.models
            // what the user wants as the main model: the chosen kind, else what is in use now (live only, or final)
            val mode = quickMode
                ?: if (ModelConfig.selectedAsrModel(this, AsrMode.FINAL) == null && ModelConfig.selectedAsrModel(this, AsrMode.LIVE) != null) AsrMode.LIVE else AsrMode.FINAL
            chipsMode.check(if (mode == AsrMode.LIVE) R.id.chip_mode_live else R.id.chip_mode_final)
            val other = if (mode == AsrMode.LIVE) AsrMode.FINAL else AsrMode.LIVE
            val main = AsrRecommender.pick(models, lang, mode, tier) ?: AsrRecommender.pick(models, lang, other, tier)
            quickMain.root.visibility = if (main != null) View.VISIBLE else View.GONE
            tvRecNone.visibility = if (main == null) View.VISIBLE else View.GONE
            if (main != null) bindQuickRow(quickMain, main, asPreview = false) else quickMain.modelId = null

            // The live preview is only an extra next to a final model; a live model chosen as the main one stands alone.
            val preview = if (main?.mode == AsrMode.FINAL) AsrRecommender.pick(models, lang, AsrMode.LIVE, tier) else null
            layoutPreviewToggle.visibility = if (preview != null) View.VISIBLE else View.GONE
            switchPreview.isChecked = previewWanted && preview != null
            val showPreview = previewWanted && preview != null
            quickPreview.root.visibility = if (showPreview) View.VISIBLE else View.GONE
            if (showPreview) bindQuickRow(quickPreview, preview!!, asPreview = true) else quickPreview.modelId = null
        } finally {
            renderingQuick = false
        }
        applyActiveDownload()
    }

    /** Memory of this phone in MB (0 when it cannot be read). */
    private fun deviceRamMb(): Long {
        val info = android.app.ActivityManager.MemoryInfo()
        getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(info) ?: return 0
        return info.totalMem / (1024 * 1024)
    }

    /** The big card at the top: which model each slot uses (dimmed "not set" when empty). */
    private fun showCombo(live: AsrModel?, fin: AsrModel?) {
        val none = getString(R.string.asr_combo_not_set)
        for ((view, model) in listOf(tvComboLive to live, tvComboFinal to fin)) {
            view.text = model?.name ?: none
            view.alpha = if (model == null) 0.45f else 1f
        }
        // a slot can be cleared while the other one still has a model (one must always stay)
        btnClearLive.visibility = if (live != null && fin != null) View.VISIBLE else View.GONE
        btnClearFinal.visibility = if (live != null && fin != null) View.VISIBLE else View.GONE
    }

    /** Rebuilds the card list for the current filter and the current download / selection state. */
    private fun renderModels() {
        val live = ModelConfig.selectedAsrModel(this, AsrMode.LIVE)
        val fin = ModelConfig.selectedAsrModel(this, AsrMode.FINAL)
        showCombo(live, fin)
        val inUse = listOfNotNull(live, fin)
        val totalRam = inUse.sumOf { it.ramMb }
        tvAsrRamTotal.visibility = if (totalRam > 0) View.VISIBLE else View.GONE
        tvAsrRamTotal.text = getString(R.string.asr_ram_total_format, AsrCatalog.formatMb(totalRam))
        tvAsrMemoryHint.visibility = if (AsrCatalog.isHeavyFor(deviceRamMb(), inUse)) View.VISIBLE else View.GONE

        btnLanguageFilter.text = getString(
            R.string.asr_language_filter_format,
            filterLanguage?.let { AsrCatalog.languageName(it, Locale.getDefault()) } ?: getString(R.string.asr_language_all)
        )

        val shown = AsrCatalog.filter(
            filterMode, filterLanguage,
            isDownloaded = { ModelConfig.isModelReady(this, it) },
            downloadedOnly = chipDownloadedOnly.isChecked
        )
        layoutModels.removeAllViews()
        cards.clear()
        tvEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        val problems = AsrCatalog.customProblems
        tvCustomProblems.visibility = if (problems.isEmpty()) View.GONE else View.VISIBLE
        tvCustomProblems.text = getString(R.string.asr_custom_problems_format, problems.joinToString("\n"))
        val inflater = layoutInflater
        val groups = AsrCatalog.grouped(shown)
        for (mode in listOf(AsrMode.LIVE, AsrMode.FINAL)) {
            val ofMode = AsrCatalog.sortForDisplay(
                groups.filter { it.first().mode == mode },
                inUse = { g -> g.any { ModelConfig.selectedAsrModel(this, it.mode)?.id == it.id } },
                downloaded = { g -> g.any { ModelConfig.isModelReady(this, it) } },
            )
            if (ofMode.isEmpty()) continue
            layoutModels.addView(sectionHeader(if (mode == AsrMode.LIVE) R.string.asr_mode_live_chip else R.string.asr_mode_final_chip))
            for (variants in ofMode) {
                val model = chooseVariant(variants)
                val view = inflater.inflate(R.layout.item_asr_model, layoutModels, false) as MaterialCardView
                bindModelCard(view, model, variants)
                layoutModels.addView(view)
            }
        }
        applyActiveDownload()
    }

    /** The variant of each group the user looked at last (the card shows one variant at a time). */
    private val chosenVariant = HashMap<String, String>()

    /** Which variant of a group the card shows: the one chosen, else the one in use, else a downloaded one, else the first. */
    private fun chooseVariant(variants: List<AsrModel>): AsrModel {
        val group = variants.first().group ?: return variants.first()
        chosenVariant[group]?.let { id -> variants.firstOrNull { it.id == id }?.let { return it } }
        return variants.firstOrNull { ModelConfig.selectedAsrModel(this, it.mode)?.id == it.id }
            ?: variants.firstOrNull { ModelConfig.isModelReady(this, it) }
            ?: variants.first()
    }

    private fun variantLabel(m: AsrModel, variants: List<AsrModel>): String {
        val locale = Locale.getDefault()
        val sameLanguages = variants.all { it.languages == m.languages }
        val langs = m.languages.joinToString(", ") { AsrCatalog.languageName(it, locale) }
        val label = when {
            m.variant == null -> langs
            sameLanguages -> m.variant
            else -> "$langs \u00B7 ${m.variant}"
        }
        return if (ModelConfig.isModelReady(this, m)) "$label \u2713" else label
    }

    private fun showVariantDialog(variants: List<AsrModel>, current: AsrModel) {
        AlertDialog.Builder(this)
            .setTitle(R.string.asr_variant_dialog_title)
            .setSingleChoiceItems(variants.map { variantLabel(it, variants) }.toTypedArray(), variants.indexOf(current)) { dialog, which ->
                variants[which].group?.let { chosenVariant[it] = variants[which].id }
                dialog.dismiss()
                renderModels()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** A small heading above the cards of one kind (live / final). */
    private fun sectionHeader(textRes: Int): TextView =
        TextView(this, null, 0, R.style.Verbead_Text_Section).apply {
            setText(textRes)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(6f)
                bottomMargin = dpToPx(8f)
                marginStart = dpToPx(4f)
            }
        }

    /** The languages line: names when they are few, a count that unfolds into the names when they are many. */
    private fun bindLanguages(view: TextView, model: AsrModel) {
        val locale = Locale.getDefault()
        val names = model.languages.map { AsrCatalog.languageName(it, locale) }
        if (names.size <= FEW_LANGUAGES) {
            view.text = getString(R.string.asr_languages_format, names.joinToString(", "))
            view.setOnClickListener(null)
            view.isClickable = false
            return
        }
        var expanded = false
        fun show() {
            view.text = if (expanded) {
                getString(R.string.asr_languages_format, names.joinToString(", ")) + "  \u25B4"
            } else {
                getString(R.string.asr_languages_count_format, names.size) + "  \u25BE"
            }
        }
        show()
        view.setOnClickListener {
            expanded = !expanded
            show()
        }
    }

    /**
     * One card, one main button. The button does the next sensible thing for the model's state:
     * download it, use it, or (when it is in use) stop using it. Re-downloading and deleting live in the menu.
     */
    private fun bindModelCard(card: MaterialCardView, model: AsrModel, variants: List<AsrModel>) {
        val ready = ModelConfig.isModelReady(this, model)
        val inUse = ModelConfig.selectedAsrModel(this, model.mode)?.id == model.id

        card.findViewById<TextView>(R.id.tv_model_name).text = model.group ?: model.name
        card.findViewById<TextView>(R.id.tv_model_desc).text = model.descriptionFor(resources.configuration.locales[0])
        card.findViewById<MaterialButton>(R.id.btn_model_variant).apply {
            if (variants.size > 1) {
                visibility = View.VISIBLE
                text = getString(R.string.asr_variant_format, variantLabel(model, variants))
                setOnClickListener { showVariantDialog(variants, model) }
            } else {
                visibility = View.GONE
            }
        }
        bindLanguages(card.findViewById(R.id.tv_model_languages), model)
        card.findViewById<TextView>(R.id.tv_model_ram).apply {
            visibility = if (model.ramMb > 0) View.VISIBLE else View.GONE
            text = getString(R.string.asr_ram_format, AsrCatalog.formatMb(model.ramMb))
        }

        val action = card.findViewById<MaterialButton>(R.id.btn_model_action)
        action.text = when {
            inUse -> getString(R.string.asr_in_use)
            ready -> getString(R.string.asr_rec_use)
            model.sizeMb > 0 -> getString(R.string.asr_action_download_format, model.sizeMb)
            else -> getString(R.string.common_download)
        }
        ChoiceButtonUtil.applyChoiceState(action, inUse)
        action.setOnClickListener {
            if (ready) onUseClick(model) else handleDownloadButtonClick(model.id)
        }

        // rare actions, only for a model that is on the phone
        card.findViewById<View>(R.id.btn_model_more).apply {
            visibility = if (ready) View.VISIBLE else View.GONE
            setOnClickListener { showModelMenu(it, model) }
        }

        updateModelCardSelection(card, inUse)
        cards[model.id] = CardViews(
            card, action,
            card.findViewById(R.id.progress_model_download),
            card.findViewById(R.id.tv_model_download_status),
        )
    }

    private fun showModelMenu(anchor: View, model: AsrModel) {
        androidx.appcompat.widget.PopupMenu(this, anchor).apply {
            menu.add(0, MENU_REDOWNLOAD, 0, R.string.common_redownload)
            menu.add(0, MENU_DELETE, 1, R.string.asr_menu_delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_REDOWNLOAD -> handleDownloadButtonClick(model.id)
                    MENU_DELETE -> confirmDelete(model)
                }
                true
            }
        }.show()
    }

    private fun confirmDelete(model: AsrModel) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.asr_delete_title, model.name))
            .setMessage(R.string.asr_delete_message)
            .setPositiveButton(R.string.asr_menu_delete) { _, _ ->
                HapticUtil.click(this)
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { ModelConfig.deleteAsrModel(this@SpeechSettingsActivity, model) }
                    updateAllStatus()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** Puts the model into its slot, or takes it out again when it is already there. */
    private fun onUseClick(model: AsrModel) {
        HapticUtil.click(this)
        if (!ModelConfig.isModelReady(this, model)) {
            Toast.makeText(this, R.string.toast_model_not_downloaded, Toast.LENGTH_SHORT).show()
            return
        }
        val inUse = ModelConfig.selectedAsrModel(this, model.mode)?.id == model.id
        ModelConfig.setAsrModel(this, model.mode, if (inUse) null else model.id)
        // at least one slot must stay filled, or there is nothing to record with
        if (ModelConfig.selectedAsrModel(this, AsrMode.LIVE) == null && ModelConfig.selectedAsrModel(this, AsrMode.FINAL) == null) {
            ModelConfig.setAsrModel(this, model.mode, model.id)
            Toast.makeText(this, R.string.asr_keep_one, Toast.LENGTH_SHORT).show()
        }
        updateAllStatus()
    }

    private fun showLanguageFilterDialog() {
        showLanguagePicker(filterLanguage, withAll = true) { tag ->
            filterLanguage = tag
            renderModels()
        }
    }

    /** A dropdown with a search box and the list of languages; typing narrows the list. */
    private fun showLanguagePicker(current: String?, withAll: Boolean, onPick: (String?) -> Unit) {
        val locale = Locale.getDefault()
        val all = AsrCatalog.allLanguages(locale)
        val allLabel = getString(R.string.asr_language_all)
        val dp = resources.displayMetrics.density

        class Row(val tag: String?, val label: String)
        val rows = (if (withAll) listOf(Row(null, allLabel)) else emptyList()) + all.map { Row(it, AsrCatalog.languageName(it, locale)) }
        fun text(r: Row) = if (r.tag == current) "\u2713  ${r.label}" else r.label

        val shown = ArrayList(rows)
        val adapter = object : android.widget.ArrayAdapter<Row>(this, android.R.layout.simple_list_item_1, shown) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).also { it.text = text(getItem(position)!!) }
        }
        val search = android.widget.EditText(this).apply {
            hint = getString(R.string.asr_language_search_hint)
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        val list = android.widget.ListView(this).apply {
            this.adapter = adapter
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, (360 * dp).toInt()
            )
        }
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, (8 * dp).toInt(), pad, 0)
            addView(search)
            addView(list)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.asr_language_dialog_title)
            .setView(content)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim()?.lowercase(locale).orEmpty()
                shown.clear()
                shown.addAll(rows.filter { q.isEmpty() || it.label.lowercase(locale).contains(q) || it.tag?.lowercase()?.startsWith(q) == true })
                adapter.notifyDataSetChanged()
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        list.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            onPick(shown[position].tag)
        }
        dialog.show()
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

    private fun handleDownloadButtonClick(engine: String, useAfter: Boolean = false, asPreview: Boolean = false) {
        val active = ModelDownloadState.active.value
        if (active != null && active.engine == engine) {
            ModelDownloadService.cancel(this)
            return
        }
        val model = AsrCatalog.byId(engine) ?: return
        val target = ModelDownloadSpec.forEngine(engine)
        lifecycleScope.launch {
            val totalBytes = withContext(Dispatchers.IO) { downloader.estimateTotalBytes(target) }
            val totalMb = totalBytes / 1024 / 1024
            val ready = ModelConfig.isModelReady(this@SpeechSettingsActivity, model)
            val action = if (ready) R.string.common_redownload else R.string.common_download

            AlertDialog.Builder(this@SpeechSettingsActivity)
                .setTitle(getString(if (ready) R.string.settings_speech_redownload_title else R.string.settings_speech_download_title, model.name))
                .setMessage(getString(R.string.settings_speech_download_message, totalMb))
                .setPositiveButton(action) { _, _ ->
                    HapticUtil.click(this@SpeechSettingsActivity)
                    if (useAfter) pendingUse[engine] = asPreview
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
        val views = cards[engine] ?: return
        views.action.text = getString(R.string.common_cancel_download)
        views.action.setOnClickListener { handleDownloadButtonClick(engine) }
        views.progress.visibility = View.VISIBLE
        views.progress.isIndeterminate = true
        views.progressText.visibility = View.VISIBLE
        views.progressText.text = getString(R.string.common_connecting)
    }

    /** Draws the running download (if any) into the card of that model. */
    private fun applyActiveDownload() {
        val active = ModelDownloadState.active.value ?: return
        for (row in listOf(quickMain, quickPreview)) {
            if (row.modelId != active.engine) continue
            row.action.text = getString(R.string.common_cancel_download)
            row.action.isEnabled = true
            row.action.setOnClickListener { handleDownloadButtonClick(active.engine) }
            row.progress.visibility = View.VISIBLE
            row.progressText.visibility = View.VISIBLE
            row.progress.isIndeterminate = active.progress.percent < 0
            if (active.progress.percent >= 0) row.progress.progress = active.progress.percent
            row.progressText.text = "${active.progress.label(this)} (${active.progress.percent}%)"
        }
        val views = cards[active.engine] ?: return
        views.action.text = getString(R.string.common_cancel_download)
        views.action.setOnClickListener { handleDownloadButtonClick(active.engine) }
        ChoiceButtonUtil.applyChoiceState(views.action, false)
        views.progress.visibility = View.VISIBLE
        views.progressText.visibility = View.VISIBLE
        views.progress.isIndeterminate = active.progress.percent < 0
        if (active.progress.percent >= 0) views.progress.progress = active.progress.percent
        views.progressText.text = "${active.progress.label(this)} (${active.progress.percent}%)"
    }

    private fun observeDownloadState() {
        lifecycleScope.launch {
            ModelDownloadState.active.collect { active ->
                if (active != null) {
                    applyActiveDownload()
                } else {
                    // nothing is downloading any more: show the cards as they are now
                    updateAllStatus()
                }
            }
        }
        lifecycleScope.launch {
            ModelDownloadState.results.collect { (engine, _) ->
                val model = AsrCatalog.byId(engine) ?: return@collect
                // a model fetched from the quick setup is put to use once it has arrived
                val asPreview = pendingUse.remove(engine)
                if (asPreview != null && ModelConfig.isModelReady(this@SpeechSettingsActivity, model)) {
                    applyQuickModel(model, asPreview)
                }
                updateAllStatus()
            }
        }
    }

    private companion object {
        /** Up to this many languages are written out on a card; more are shown as a count that unfolds. */
        const val FEW_LANGUAGES = 4

        const val MENU_REDOWNLOAD = 1
        const val MENU_DELETE = 2
    }
}
