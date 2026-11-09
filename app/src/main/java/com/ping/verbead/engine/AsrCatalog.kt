package com.ping.verbead.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * How a speech model delivers its text:
 *  - [LIVE]: the words appear while the user is still speaking (a streaming model).
 *  - [FINAL]: the whole sentence is recognized after the user stops (an offline model), usually more accurate.
 */
enum class AsrMode(val key: String) {
    LIVE("live"), FINAL("final");

    companion object {
        fun parse(key: String): AsrMode? = entries.firstOrNull { it.key == key.lowercase() }
    }
}

/**
 * How the files of a model are loaded. This is the only place where the app knows about a model "kind";
 * everything else about a model (files, languages, options, download) is data in the manifest.
 */
enum class AsrLoader(val key: String) {
    /** sherpa-onnx streaming transducer (zipformer, NeMo / Nemotron cache-aware RNNT ...): encoder, decoder, joiner, tokens */
    ONLINE_TRANSDUCER("online_transducer"),

    /** sherpa-onnx offline transducer (NeMo Parakeet ...): encoder, decoder, joiner, tokens */
    OFFLINE_TRANSDUCER("offline_transducer"),

    /** Moonshine v2: encoder + merged decoder, tokens */
    MOONSHINE("moonshine"),

    /** Qwen3-ASR: has its own loader (conv frontend, tokenizer folder, hot words) */
    QWEN3("qwen3");

    companion object {
        fun parse(key: String): AsrLoader? = entries.firstOrNull { it.key == key.lowercase() }
    }
}

/**
 * One downloadable speech model, as described by an entry of the manifest (see `asr_models.json`).
 *
 * [languages] are the languages the model claims to support, as BCP-47 tags ("en", "zh-TW", "fil" ...);
 * a model that lists "zh" also serves "zh-TW" (see [supportsLanguage]).
 */
data class AsrModel(
    val id: String,
    /** Shown as is (a product name, not translated) */
    val name: String,
    val mode: AsrMode,
    val loader: AsrLoader,
    /** Folder under the models directory */
    val dir: String,
    val languages: List<String>,
    /** Approximate download size in MB, or 0 when not known */
    val sizeMb: Int = 0,
    /** tar.bz2 archive of the model (extracted into [dir]), or null when the model is downloaded file by file */
    val archiveUrl: String? = null,
    val archiveSha256: String? = null,
    /** Individual files to download (used when there is no archive) */
    val files: List<ModelDownloadSpec.RemoteFile> = emptyList(),
    /** Explicit file names (encoder / decoder / joiner / tokens); when empty they are found by their prefix */
    val fileNames: Map<String, String> = emptyMap(),
    /** Options set on every stream of a streaming model, e.g. language = auto */
    val streamOptions: Map<String, String> = emptyMap(),
    /** Silence fed at the end of a streaming recording so the last chunk is completed (models with long chunks) */
    val tailSilenceSec: Float = 0f,
    /** sherpa-onnx model type for an offline transducer (e.g. nemo_transducer) */
    val offlineModelType: String? = null,
    /** The model writes everything in capital letters without punctuation (some English zipformers do) */
    val outputsUppercase: Boolean = false,
    /** The model writes Traditional Chinese by itself, so only Taiwan wording rules are needed */
    val nativeTraditional: Boolean = false,
    /** Execution providers to try in order */
    val providers: List<String> = listOf("cpu"),
    val threads: Int = 4,
    /** Short description by language tag ("en", "zh-TW" ...) */
    val description: Map<String, String> = emptyMap(),
    /** Models of the same family (one per language or size) share a group and are shown as one card with a variant choice */
    val group: String? = null,
    /** What tells this model apart inside its group besides the language, e.g. Tiny or Base */
    val variant: String? = null,
) {
    fun supportsLanguage(tag: String): Boolean = languages.any { languageMatches(it, tag) }

    /** The description in the best matching language: exact tag, same language, English, then anything. */
    fun descriptionFor(locale: Locale): String {
        val tag = locale.toLanguageTag()
        return description[tag]
            ?: description.entries.firstOrNull { it.key.substringBefore('-').equals(locale.language, true) }?.value
            ?: description["en"]
            ?: description.values.firstOrNull()
            ?: ""
    }

    /**
     * Turns the all-capitals English of such a model into ordinary writing: lower case, with a capital at the start of
     * a sentence and for the word "I". Chinese and other text around it is left as it is, and output that already has
     * lower-case English is not touched.
     */
    fun normalizeText(text: String): String {
        if (!outputsUppercase || text.none { it in 'A'..'Z' } || text.any { it in 'a'..'z' }) return text
        val sb = StringBuilder(text.lowercase())
        var sentenceStart = true
        for (i in sb.indices) {
            val c = sb[i]
            when {
                c in 'a'..'z' -> { if (sentenceStart) sb.setCharAt(i, c.uppercaseChar()); sentenceStart = false }
                c in ".!?\n。！？" -> sentenceStart = true
                c.isLetter() -> sentenceStart = false
            }
        }
        return sb.toString().replace(Regex("\\bi\\b"), "I")
    }

    companion object {
        /** "zh" covers "zh-TW"; "zh-TW" does not cover "zh-CN". */
        fun languageMatches(modelTag: String, wanted: String): Boolean {
            if (modelTag.equals(wanted, ignoreCase = true)) return true
            return !modelTag.contains('-') && wanted.substringBefore('-').equals(modelTag, ignoreCase = true)
        }
    }
}

/**
 * The catalog of every speech model the app can download and use.
 *
 * The models are data: the bundled manifest (`asr_models.json`) plus an optional `custom_models.json` in the models
 * folder. A model that uses one of the known [AsrLoader]s can be added by writing an entry, with no change to the app.
 */
object AsrCatalog {
    const val X_ASR = "x_asr"
    const val QWEN3 = "qwen3"

    const val CUSTOM_MANIFEST = "custom_models.json"

    private val builtIn: List<AsrModel> by lazy {
        val text = AsrCatalog::class.java.getResourceAsStream("/asr_models.json")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("asr_models.json is missing from the app")
        parse(text).models
    }

    @Volatile private var custom: List<AsrModel> = emptyList()

    /** Why entries of the custom manifest were skipped (shown in the settings page), empty when all were fine. */
    @Volatile var customProblems: List<String> = emptyList()
        private set

    val models: List<AsrModel> get() = builtIn + custom

    /** Result of reading a manifest: the models that are valid and a message for each entry that is not. */
    data class ParseResult(val models: List<AsrModel>, val problems: List<String>)

    /** Reads a manifest text. A broken entry is skipped with a message; it never makes the others fail. */
    fun parse(text: String): ParseResult {
        val models = ArrayList<AsrModel>()
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

    private fun stringMap(o: JSONObject?): Map<String, String> =
        if (o == null) emptyMap() else o.keys().asSequence().associateWith { o.getString(it) }

    private fun parseModel(o: JSONObject): AsrModel {
        val id = o.getString("id")
        require(Regex("[A-Za-z0-9_\\-]+").matches(id)) { "bad id" }
        val dir = o.optString("dir", id)
        // the folder is joined to the models path: it must stay inside it
        require(Regex("[A-Za-z0-9_.\\-]+").matches(dir) && dir != "." && dir != "..") { "bad dir" }
        val mode = AsrMode.parse(o.getString("mode")) ?: error("unknown mode")
        val loader = AsrLoader.parse(o.getString("loader")) ?: error("unknown loader")
        val languages = strings(o.optJSONArray("languages"))
        require(languages.isNotEmpty()) { "no languages" }
        val archive = o.optJSONObject("archive")
        val files = o.optJSONArray("files")?.let { a ->
            List(a.length()) {
                val f = a.getJSONObject(it)
                val path = f.getString("path")
                require(!path.contains("..") && !path.startsWith("/")) { "bad file path" }
                ModelDownloadSpec.RemoteFile(f.getString("url"), path, f.optString("sha256").ifBlank { null })
            }
        } ?: emptyList()
        require(archive != null || files.isNotEmpty()) { "no download source" }
        return AsrModel(
            id = id,
            name = o.getString("name"),
            mode = mode,
            loader = loader,
            dir = dir,
            languages = languages,
            sizeMb = o.optInt("sizeMb", 0),
            archiveUrl = archive?.getString("url"),
            archiveSha256 = archive?.optString("sha256")?.ifBlank { null },
            files = files,
            fileNames = stringMap(o.optJSONObject("fileNames")),
            streamOptions = stringMap(o.optJSONObject("streamOptions")),
            tailSilenceSec = o.optDouble("tailSilenceSec", 0.0).toFloat(),
            offlineModelType = o.optString("offlineModelType").ifBlank { null },
            outputsUppercase = o.optBoolean("outputsUppercase", false),
            nativeTraditional = o.optBoolean("nativeTraditional", false),
            providers = strings(o.optJSONArray("providers")).ifEmpty { listOf("cpu") },
            threads = o.optInt("threads", 4).coerceIn(1, 8),
            description = stringMap(o.optJSONObject("description")),
            group = o.optString("group").ifBlank { null },
            variant = o.optString("variant").ifBlank { null },
        )
    }

    /** Reads `custom_models.json` from the given models folders (the first that has one wins). Built-in ids cannot be replaced. */
    fun reloadCustom(modelsDirs: List<File>) {
        val file = modelsDirs.map { File(it, CUSTOM_MANIFEST) }.firstOrNull { it.isFile }
        if (file == null) {
            custom = emptyList()
            customProblems = emptyList()
            return
        }
        val result = parse(runCatching { file.readText(Charsets.UTF_8) }.getOrDefault(""))
        val taken = builtIn.map { it.id }.toMutableSet()
        val usable = ArrayList<AsrModel>()
        val problems = ArrayList(result.problems)
        for (m in result.models) {
            if (m.id in taken || builtIn.any { it.dir == m.dir }) { problems += "${m.id}: id or folder already used"; continue }
            taken += m.id
            usable += m
        }
        custom = usable
        customProblems = problems
    }

    /** The models of a filtered list, in order, gathered by group: a model without a group is a group of its own. */
    fun grouped(list: List<AsrModel>): List<List<AsrModel>> =
        list.groupBy { it.group ?: it.id }.values.toList()

    fun byId(id: String?): AsrModel? = models.firstOrNull { it.id == id }

    fun ofMode(mode: AsrMode): List<AsrModel> = models.filter { it.mode == mode }

    /** Every language any model claims, as tags sorted by their display name in [locale]. */
    fun allLanguages(locale: Locale): List<String> =
        models.flatMap { it.languages }.distinct().sortedBy { languageName(it, locale).lowercase(locale) }

    /**
     * The models that match a filter: [mode] null = both kinds, [language] null = any language. A model that
     * the user has downloaded can be required with [downloadedOnly].
     */
    fun filter(mode: AsrMode?, language: String?, isDownloaded: (AsrModel) -> Boolean, downloadedOnly: Boolean = false): List<AsrModel> =
        models.filter { m ->
            (mode == null || m.mode == mode) &&
                (language == null || m.supportsLanguage(language)) &&
                (!downloadedOnly || isDownloaded(m))
        }

    /** Display name of a language tag in [locale] (the platform has the names, so they come translated for free). */
    fun languageName(tag: String, locale: Locale): String {
        val l = Locale.forLanguageTag(tag)
        val name = l.getDisplayName(locale)
        return if (name.isBlank()) tag else name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }
}

/** Finds the files of a model inside its folder, using the manifest's file names when it gives them. */
object AsrModelFiles {
    data class Transducer(val encoder: String, val decoder: String, val joiner: String, val tokens: String)

    data class Moonshine(val encoder: String, val mergedDecoder: String, val tokens: String)

    private fun pick(dir: File, prefix: String, exts: List<String>, mustContain: String? = null): File? {
        val files = dir.listFiles()?.filter { f ->
            f.isFile && f.length() > 0 && f.name.startsWith(prefix) &&
                exts.any { f.name.endsWith(it) } && (mustContain == null || f.name.contains(mustContain))
        } ?: return null
        // the int8 file when there is one
        return files.firstOrNull { it.name.contains("int8") } ?: files.minByOrNull { it.name }
    }

    private fun named(dir: File, name: String?): File? =
        name?.let { File(dir, it) }?.takeIf { it.isFile && it.length() > 0 }

    fun transducer(dir: File, names: Map<String, String> = emptyMap()): Transducer? {
        if (!dir.isDirectory) return null
        val enc = (if ("encoder" in names) named(dir, names["encoder"]) else pick(dir, "encoder", listOf(".onnx"))) ?: return null
        val dec = (if ("decoder" in names) named(dir, names["decoder"]) else pick(dir, "decoder", listOf(".onnx"))) ?: return null
        val join = (if ("joiner" in names) named(dir, names["joiner"]) else pick(dir, "joiner", listOf(".onnx"))) ?: return null
        val tokens = named(dir, names["tokens"] ?: "tokens.txt") ?: return null
        return Transducer(enc.absolutePath, dec.absolutePath, join.absolutePath, tokens.absolutePath)
    }

    fun moonshine(dir: File): Moonshine? {
        if (!dir.isDirectory) return null
        val enc = pick(dir, "encoder", listOf(".ort", ".onnx")) ?: return null
        val dec = pick(dir, "decoder", listOf(".ort", ".onnx"), "merged") ?: return null
        val tokens = named(dir, "tokens.txt") ?: return null
        return Moonshine(enc.absolutePath, dec.absolutePath, tokens.absolutePath)
    }

    /** Whether the folder holds everything the model's loader needs (Qwen3 has its own check in [ModelConfig]). */
    fun isComplete(model: AsrModel, dir: File): Boolean = when (model.loader) {
        AsrLoader.ONLINE_TRANSDUCER, AsrLoader.OFFLINE_TRANSDUCER -> transducer(dir, model.fileNames) != null
        AsrLoader.MOONSHINE -> moonshine(dir) != null
        AsrLoader.QWEN3 -> true
    }
}
