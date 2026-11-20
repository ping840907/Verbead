package com.ping.verbead.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** How the files of a read-aloud model are loaded by sherpa-onnx. */
enum class TtsKind(val key: String) {
    /** VITS as converted from MeloTTS: model, lexicon, tokens, jieba dictionary, number/date/phone rules */
    VITS_MELO("vits_melo"),

    /** Kokoro multi-language: model, voices, tokens, two lexicons, espeak data, jieba dictionary, rules */
    KOKORO("kokoro");

    companion object {
        fun parse(key: String): TtsKind? = entries.firstOrNull { it.key == key.lowercase() }
    }
}

/** Same meaning as [AsrTier]: how heavy the model is for the phone. */
enum class TtsTier(val key: String) {
    LIGHT("light"), BALANCED("balanced"), BEST("best");

    companion object {
        fun parse(key: String): TtsTier? = entries.firstOrNull { it.key == key.lowercase() }
    }
}

/**
 * One downloadable read-aloud model, an entry of `tts_models.json`. The numbers that describe it
 * ([ramMb], [rtf]) were measured on a phone (see TTS_PHASE0.md) and are shown on its card.
 *
 * [rtf] is the real-time factor: seconds of work per second of speech, so 0.4 means a sentence is
 * ready in 40% of the time it takes to say it.
 */
data class TtsModel(
    val id: String,
    /** Shown as is (a product name, not translated) */
    val name: String,
    val kind: TtsKind,
    /** Folder under `<models>/tts/` */
    val dir: String,
    val languages: List<String>,
    val tier: TtsTier,
    val voices: Int,
    val sampleRate: Int,
    /** Download size in MB */
    val sizeMb: Int,
    /** Memory the loaded model adds, in MB */
    val ramMb: Int,
    val rtf: Double,
    val license: String,
    /** "official" (the sherpa-onnx project) or "custom" */
    val source: String,
    val archiveUrl: String,
    val archiveSha256: String?,
    /** Files (or folders, written with a trailing slash) that must exist after extraction */
    val requiredFiles: List<String>,
    /** Speaker id to use per language when the user has not picked a voice */
    val defaultSid: Map<String, Int> = emptyMap(),
    val description: Map<String, String> = emptyMap(),
) {
    fun supportsLanguage(tag: String): Boolean = languages.any { AsrModel.languageMatches(it, tag) }

    /** The description in the best matching language: exact tag, same language, English, then anything. */
    fun descriptionFor(locale: Locale): String {
        val tag = locale.toLanguageTag()
        return description[tag]
            ?: description.entries.firstOrNull { it.key.substringBefore('-').equals(locale.language, true) }?.value
            ?: description["en"]
            ?: description.values.firstOrNull()
            ?: ""
    }

    /** The speaker to use for [lang] ("zh", "en" ...): the model's default for it, else its default for English, else 0. */
    fun defaultSidFor(lang: String): Int = defaultSid[lang] ?: defaultSid["en"] ?: 0

    /** True when every required file is there and not empty (a folder must hold something). */
    fun isInstalled(root: File): Boolean = requiredFiles.all { name ->
        if (name.endsWith("/")) {
            File(root, name.removeSuffix("/")).let { it.isDirectory && !it.list().isNullOrEmpty() }
        } else {
            File(root, name).let { it.isFile && it.length() > 0 }
        }
    }
}

/** The catalog of read-aloud models the app can download, read from the bundled `tts_models.json`. */
object TtsCatalog {

    /** Chooses the system speech engine instead of a downloaded model. */
    const val SYSTEM = "system"

    private val builtIn: List<TtsModel> by lazy {
        val text = TtsCatalog::class.java.getResourceAsStream("/tts_models.json")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("tts_models.json is missing from the app")
        parse(text).models
    }

    val models: List<TtsModel> get() = builtIn

    fun byId(id: String): TtsModel? = models.firstOrNull { it.id == id }

    data class ParseResult(val models: List<TtsModel>, val problems: List<String>)

    /** Reads a manifest text. A broken entry is skipped with a message; it never makes the others fail. */
    fun parse(text: String): ParseResult {
        val models = ArrayList<TtsModel>()
        val problems = ArrayList<String>()
        val array: JSONArray = try {
            val root = text.trim()
            if (root.startsWith("[")) JSONArray(root) else JSONObject(root).getJSONArray("models")
        } catch (e: Exception) {
            return ParseResult(emptyList(), listOf("manifest: ${e.message}"))
        }
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i)
            if (o == null) { problems += "#$i: not an object"; continue }
            try {
                models += parseModel(o)
            } catch (e: Exception) {
                problems += "${o.optString("id", "#$i")}: ${e.message}"
            }
        }
        return ParseResult(models, problems)
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.getString(it) }

    private fun parseModel(o: JSONObject): TtsModel {
        val id = o.getString("id")
        require(Regex("[A-Za-z0-9_\\-]+").matches(id) && id != SYSTEM) { "bad id" }
        val dir = o.optString("dir", id)
        // the folder is joined to the models path: it must stay inside it
        require(Regex("[A-Za-z0-9_.\\-]+").matches(dir) && dir != "." && dir != "..") { "bad dir" }
        val kind = TtsKind.parse(o.getString("kind")) ?: error("unknown kind")
        val languages = strings(o.optJSONArray("languages"))
        require(languages.isNotEmpty()) { "no languages" }
        val archive = o.optJSONObject("archive") ?: error("no download source")
        val required = strings(o.optJSONArray("requiredFiles"))
        require(required.isNotEmpty()) { "no requiredFiles" }
        require(required.none { it.contains("..") || it.startsWith("/") }) { "bad file path" }
        val sid = o.optJSONObject("defaultSid")?.let { s -> s.keys().asSequence().associateWith { s.getInt(it) } } ?: emptyMap()
        val desc = o.optJSONObject("description")?.let { d -> d.keys().asSequence().associateWith { d.getString(it) } } ?: emptyMap()
        return TtsModel(
            id = id,
            name = o.getString("name"),
            kind = kind,
            dir = dir,
            languages = languages,
            tier = TtsTier.parse(o.optString("tier", "balanced")) ?: TtsTier.BALANCED,
            voices = o.optInt("voices", 1).coerceAtLeast(1),
            sampleRate = o.optInt("sampleRate", 24000),
            sizeMb = o.optInt("sizeMb", 0),
            ramMb = o.optInt("ramMb", 0),
            rtf = o.optDouble("rtf", 0.0),
            license = o.optString("license"),
            source = o.optString("source", "official"),
            archiveUrl = archive.getString("url"),
            archiveSha256 = archive.optString("sha256").ifBlank { null },
            requiredFiles = required,
            defaultSid = sid,
            description = desc,
        )
    }
}
