package com.ping.verbead.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.ping.verbead.ModelDownloadService
import com.ping.verbead.R
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.engine.ModelDownloadSpec
import com.ping.verbead.engine.ModelDownloadState
import com.ping.verbead.engine.ModelDownloader
import com.ping.verbead.engine.TtsCatalog
import com.ping.verbead.engine.TtsModel
import com.ping.verbead.engine.TtsTier
import com.ping.verbead.tts.LanguageGuess
import com.ping.verbead.tts.SherpaTtsEngine
import com.ping.verbead.tts.SpeechLang
import com.ping.verbead.tts.TtsEngine
import com.ping.verbead.util.HapticUtil
import com.ping.verbead.util.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Read-aloud settings: which voice engine reads (the system voice, or a downloaded model), with one card per
 * choice. A model card shows what tells the models apart (languages, voices, size, memory, speed, licence),
 * downloads and removes the model, and lets the user pick and preview a voice.
 */
class TtsSettingsActivity : AppCompatActivity() {

    /** The views of one card. [model] is null for the system voice. */
    private class Card(val model: TtsModel?, val root: View) {
        val state: TextView = root.findViewById(R.id.tv_tts_model_state)
        val tags: TextView = root.findViewById(R.id.tv_tts_model_tags)
        val progress: LinearProgressIndicator = root.findViewById(R.id.progress_tts_model)
        val progressText: TextView = root.findViewById(R.id.tv_tts_model_progress)
        val voiceRow: View = root.findViewById(R.id.layout_tts_voice)
        val voiceText: TextView = root.findViewById(R.id.tv_tts_voice)
        val voicePrev: MaterialButton = root.findViewById(R.id.btn_tts_voice_prev)
        val voiceNext: MaterialButton = root.findViewById(R.id.btn_tts_voice_next)
        val preview: MaterialButton = root.findViewById(R.id.btn_tts_voice_preview)
        val download: MaterialButton = root.findViewById(R.id.btn_tts_model_download)
        val use: MaterialButton = root.findViewById(R.id.btn_tts_model_use)
        val delete: MaterialButton = root.findViewById(R.id.btn_tts_model_delete)
    }

    private val cards = LinkedHashMap<String, Card>()
    private val downloader by lazy { ModelDownloader(this) }
    private var pendingDownload: String? = null
    private var previewEngine: TtsEngine? = null

    private val notifPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingDownload?.let { beginDownload(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_tts)
        com.ping.verbead.ui.GlassStage.install(this)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            HapticUtil.click(this)
            finish()
        }
        buildCards()
        observeDownloadState()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        stopPreview()
        super.onDestroy()
    }

    // ── Cards ───────────────────────────────────────────────────────────

    private fun buildCards() {
        val container = findViewById<LinearLayout>(R.id.container_tts_models)
        val inflater = LayoutInflater.from(this)

        // the system voice: always there, nothing to download
        val systemRoot = inflater.inflate(R.layout.item_tts_model, container, false)
        container.addView(systemRoot)
        val system = Card(null, systemRoot)
        systemRoot.findViewById<TextView>(R.id.tv_tts_model_name).setText(R.string.tts_system_voice_name)
        systemRoot.findViewById<TextView>(R.id.tv_tts_model_desc).setText(R.string.tts_system_voice_desc)
        system.tags.visibility = View.GONE
        system.download.visibility = View.GONE
        system.delete.visibility = View.GONE
        system.use.setOnClickListener {
            HapticUtil.click(this)
            ModelConfig.setSelectedTtsEngine(this, TtsCatalog.SYSTEM)
            refresh()
        }
        cards[TtsCatalog.SYSTEM] = system

        for (model in TtsCatalog.models) {
            val root = inflater.inflate(R.layout.item_tts_model, container, false)
            container.addView(root)
            val card = Card(model, root)
            root.findViewById<TextView>(R.id.tv_tts_model_name).text = model.name
            root.findViewById<TextView>(R.id.tv_tts_model_desc).text = model.descriptionFor(Locale.getDefault())
            card.tags.text = tagsFor(model)
            card.download.setOnClickListener { onDownloadClicked(model) }
            card.use.setOnClickListener {
                HapticUtil.click(this)
                ModelConfig.setSelectedTtsEngine(this, model.id)
                refresh()
            }
            card.delete.setOnClickListener { confirmDelete(model) }
            card.voicePrev.setOnClickListener { stepVoice(model, -1) }
            card.voiceNext.setOnClickListener { stepVoice(model, +1) }
            card.preview.setOnClickListener { previewVoice(model, card) }
            cards[model.id] = card
        }
    }

    /** "Chinese · English · 1 voice · 159 MB · ≈290 MB memory · ≈2.5× real time · MIT" */
    private fun tagsFor(model: TtsModel): String {
        val locale = Locale.getDefault()
        val parts = ArrayList<String>()
        parts += model.languages.joinToString(" · ") { Locale.forLanguageTag(it).getDisplayLanguage(locale).ifBlank { it } }
        parts += getString(R.string.tts_tag_voices, model.voices)
        parts += getString(R.string.tts_tag_size, model.sizeMb)
        if (model.ramMb > 0) parts += getString(R.string.tts_tag_ram, model.ramMb)
        if (model.rtf > 0) parts += getString(R.string.tts_tag_speed, 1.0 / model.rtf)
        parts += getString(
            when (model.tier) {
                TtsTier.LIGHT -> R.string.tts_tier_light
                TtsTier.BALANCED -> R.string.tts_tier_balanced
                TtsTier.BEST -> R.string.tts_tier_best
            }
        )
        if (model.license.isNotBlank()) parts += model.license
        if (model.source != "official") parts += getString(R.string.tts_tag_custom)
        return parts.joinToString("  ·  ")
    }

    private fun refresh() {
        val selected = ModelConfig.selectedTtsEngine(this)
        val active = ModelDownloadState.active.value
        for ((id, card) in cards) {
            val model = card.model
            val isSelected = id == selected
            if (model == null) {
                card.state.text = if (isSelected) getString(R.string.tts_state_in_use) else ""
                card.use.isEnabled = !isSelected
                continue
            }
            val ready = ModelConfig.isTtsModelReady(this, model)
            val downloading = active != null && active.engine == id
            card.state.text = getString(
                when {
                    isSelected -> R.string.tts_state_in_use
                    ready -> R.string.common_ready
                    else -> R.string.common_not_downloaded
                }
            )
            card.download.text = getString(
                when {
                    downloading -> R.string.common_cancel_download
                    ready -> R.string.common_redownload
                    else -> R.string.common_download_model
                }
            )
            card.use.visibility = if (ready) View.VISIBLE else View.GONE
            card.use.isEnabled = ready && !isSelected
            card.delete.visibility = if (ready && !downloading) View.VISIBLE else View.GONE
            card.voiceRow.visibility = if (ready && model.voices > 1) View.VISIBLE else View.GONE
            updateVoiceText(model, card)
            if (!downloading) {
                card.progress.visibility = View.GONE
                card.progressText.visibility = View.GONE
            }
        }
    }

    // ── Voice ───────────────────────────────────────────────────────────

    private fun updateVoiceText(model: TtsModel, card: Card) {
        val sid = ModelConfig.ttsVoiceSid(this, model.id)
        card.voiceText.text = if (sid == null) getString(R.string.tts_voice_auto) else getString(R.string.tts_voice_number, sid + 1)
        card.voicePrev.isEnabled = sid != null
        card.voiceNext.isEnabled = sid == null || sid < model.voices - 1
    }

    /** Auto → voice 1 → voice 2 ...; stepping back from voice 1 returns to Auto. */
    private fun stepVoice(model: TtsModel, delta: Int) {
        HapticUtil.click(this)
        val current = ModelConfig.ttsVoiceSid(this, model.id)
        val next = when {
            current == null -> if (delta > 0) 0 else null
            current + delta < 0 -> null
            else -> (current + delta).coerceAtMost(model.voices - 1)
        }
        ModelConfig.setTtsVoiceSid(this, model.id, next)
        cards[model.id]?.let { updateVoiceText(model, it) }
        previewVoice(model, cards[model.id] ?: return)
    }

    private fun previewVoice(model: TtsModel, card: Card) {
        stopPreview()
        val text = getString(R.string.tts_preview_text)
        val engine = SherpaTtsEngine(model, ModelConfig.ttsModelDir(this, model), sidFor = { lang: SpeechLang ->
            ModelConfig.ttsVoiceSid(this, model.id) ?: model.defaultSidFor(lang.code)
        })
        previewEngine = engine
        card.preview.isEnabled = false
        val finish = {
            if (previewEngine === engine) {
                stopPreview()
                card.preview.isEnabled = true
            }
        }
        engine.init(object : TtsEngine.Callback {
            override fun onStart(utteranceId: Int) = Unit
            override fun onDone(utteranceId: Int) = finish()
            override fun onError(utteranceId: Int) = finish()
        }) { ok ->
            if (!ok) finish() else engine.speak(1, text, LanguageGuess.of(text), 1.0f)
        }
    }

    private fun stopPreview() {
        previewEngine?.shutdown()
        previewEngine = null
        for (card in cards.values) card.preview.isEnabled = true
    }

    // ── Download and delete ─────────────────────────────────────────────

    private fun onDownloadClicked(model: TtsModel) {
        val active = ModelDownloadState.active.value
        if (active != null && active.engine == model.id) {
            ModelDownloadService.cancel(this)
            refresh()
            return
        }
        val card = cards[model.id] ?: return
        val target = ModelDownloadSpec.forTts(model)
        card.download.isEnabled = false
        card.download.text = getString(R.string.settings_ocr_checking_size)

        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { downloader.estimateTotalBytes(target) }
            card.download.isEnabled = true
            refresh()
            val size = if (bytes < 0) getString(R.string.common_size_unknown) else ModelDownloader.formatBytes(bytes)
            AlertDialog.Builder(this@TtsSettingsActivity)
                .setTitle(getString(R.string.settings_speech_download_title, model.name))
                .setMessage(getString(R.string.settings_tts_download_message, size, model.ramMb))
                .setPositiveButton(R.string.settings_ocr_download_start) { _, _ ->
                    HapticUtil.click(this@TtsSettingsActivity)
                    pendingDownload = model.id
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(this@TtsSettingsActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        beginDownload(model.id)
                    }
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }
    }

    private fun beginDownload(engine: String) {
        ModelDownloadService.start(this, engine)
        val card = cards[engine] ?: return
        card.download.text = getString(R.string.common_cancel_download)
        card.progress.visibility = View.VISIBLE
        card.progress.isIndeterminate = true
        card.progressText.visibility = View.VISIBLE
        card.progressText.text = getString(R.string.common_connecting)
    }

    private fun confirmDelete(model: TtsModel) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.tts_delete_title, model.name))
            .setMessage(R.string.tts_delete_message)
            .setPositiveButton(R.string.tts_btn_delete) { _, _ ->
                HapticUtil.click(this)
                if (ModelConfig.selectedTtsEngine(this) == model.id) ModelConfig.setSelectedTtsEngine(this, TtsCatalog.SYSTEM)
                ModelConfig.deleteTtsModel(this, model)
                refresh()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun observeDownloadState() {
        lifecycleScope.launch {
            ModelDownloadState.active.collect { active ->
                val card = active?.let { cards[it.engine] }
                if (active != null && card != null) {
                    card.download.text = getString(R.string.common_cancel_download)
                    card.progress.visibility = View.VISIBLE
                    card.progressText.visibility = View.VISIBLE
                    card.progress.isIndeterminate = active.progress.percent < 0
                    if (active.progress.percent >= 0) card.progress.progress = active.progress.percent
                    card.progressText.text = "${active.progress.label(this@TtsSettingsActivity)} (${active.progress.percent}%)"
                } else if (active == null) {
                    refresh()
                }
            }
        }
        lifecycleScope.launch {
            ModelDownloadState.results.collect { (engine, _) ->
                if (cards.containsKey(engine)) refresh()
            }
        }
    }
}
