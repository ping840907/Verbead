package com.ping.verbead.engine

import android.content.Context
import com.github.houbb.opencc4j.util.ZhConverterUtil
import java.io.File

object ModelConfig {

    // ── Engine selection ──────────────────────────────────────────────────────
    const val PREF_ENGINE   = "asr_engine_selection"
    const val KEY_ENGINE    = "engine"
    const val ENGINE_QWEN3  = "qwen3"
    const val ENGINE_X_ASR  = "x_asr"

    // ── Qwen3-ASR-0.6B-int8 (offline, Simplified→Traditional via OpenCC) ─────
    const val QWEN3_ASR_DIR           = "qwen3_asr"
    const val QWEN3_ASR_CONV_FRONTEND = "conv_frontend.onnx"
    const val QWEN3_ASR_ENCODER       = "encoder.int8.onnx"
    const val QWEN3_ASR_DECODER       = "decoder.int8.onnx"
    const val QWEN3_ASR_TOKENIZER_DIR = "tokenizer"
    const val QWEN3_ASR_THREADS       = 4

    // ── X-ASR streaming transducer (online, native Traditional Chinese) ───────
    // Model files from: https://huggingface.co/Luigi/x-asr-zh-tw-en-streaming-ft75m
    // Place under: <modelsDir>/x_asr/
    //   encoder.int8.onnx   decoder.onnx   joiner.int8.onnx   tokens.txt
    const val X_ASR_DIR     = "x_asr"
    const val X_ASR_ENCODER = "encoder.int8.onnx"
    const val X_ASR_DECODER = "decoder.onnx"
    const val X_ASR_JOINER  = "joiner.int8.onnx"
    const val X_ASR_TOKENS  = "tokens.txt"
    const val X_ASR_THREADS = 4

    // ── Shared ────────────────────────────────────────────────────────────────
    const val ASR_SAMPLE_RATE = 16_000
    val ASR_PROVIDER_PRIORITY = listOf("nnapi", "cpu")

    // Recording / VAD
    const val MAX_RECORD_SECONDS          = 15f
    const val MIN_RECORD_SECONDS          = 0.4f
    const val VAD_SILENCE_SECONDS         = 1.5f
    const val VAD_SILENCE_THRESHOLD       = 0.012f
    const val VAD_SPEECH_THRESHOLD        = 0.018f
    const val VAD_INITIAL_TIMEOUT_SECONDS = 5.0f
    const val VAD_SILENCE_MIN             = 0.5f
    const val VAD_SILENCE_MAX             = 3.0f

    private const val PREF_VAD      = "vad_settings"
    private const val KEY_VAD_SILENCE = "silence_seconds"

    /** User-configurable pause length (seconds) before Qwen3 offline recording auto-stops. */
    fun vadSilenceSeconds(context: Context): Float =
        context.getSharedPreferences(PREF_VAD, Context.MODE_PRIVATE)
            .getFloat(KEY_VAD_SILENCE, VAD_SILENCE_SECONDS)

    fun setVadSilenceSeconds(context: Context, seconds: Float) =
        context.getSharedPreferences(PREF_VAD, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_VAD_SILENCE, seconds.coerceIn(VAD_SILENCE_MIN, VAD_SILENCE_MAX)).apply()

    fun getPrimaryModelsDir(context: Context): File {
        return context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
    }

    fun modelsDir(context: Context): String {
        val primary = context.getExternalFilesDir("models")
        if (primary != null && (File(primary, OCR_DIR).exists() || File(primary, X_ASR_DIR).exists() || File(primary, QWEN3_ASR_DIR).exists())) {
            return primary.absolutePath
        }
        val altPaths = listOf(
            File(context.filesDir, "models"),
            File("/storage/emulated/0/Android/data/com.ping.verbead/files/models"),
            File("/sdcard/Android/data/com.ping.verbead/files/models")
        )
        for (alt in altPaths) {
            if (File(alt, OCR_DIR).exists() || File(alt, X_ASR_DIR).exists() || File(alt, QWEN3_ASR_DIR).exists()) {
                return alt.absolutePath
            }
        }
        return primary?.absolutePath ?: (context.filesDir.absolutePath + "/models")
    }

    // Qwen3
    fun qwen3AsrDir(context: Context)             = "${modelsDir(context)}/$QWEN3_ASR_DIR"
    fun qwen3AsrConvFrontendPath(context: Context) = "${qwen3AsrDir(context)}/$QWEN3_ASR_CONV_FRONTEND"
    fun qwen3AsrEncoderPath(context: Context)      = "${qwen3AsrDir(context)}/$QWEN3_ASR_ENCODER"
    fun qwen3AsrDecoderPath(context: Context)      = "${qwen3AsrDir(context)}/$QWEN3_ASR_DECODER"
    fun qwen3AsrTokenizerDir(context: Context)     = "${qwen3AsrDir(context)}/$QWEN3_ASR_TOKENIZER_DIR"

    // X-ASR
    fun xAsrDir(context: Context)         = "${modelsDir(context)}/$X_ASR_DIR"
    fun xAsrEncoderPath(context: Context) = "${xAsrDir(context)}/$X_ASR_ENCODER"
    fun xAsrDecoderPath(context: Context) = "${xAsrDir(context)}/$X_ASR_DECODER"
    fun xAsrJoinerPath(context: Context)  = "${xAsrDir(context)}/$X_ASR_JOINER"
    fun xAsrTokensPath(context: Context)  = "${xAsrDir(context)}/$X_ASR_TOKENS"

    // Engine selection helpers
    fun selectedEngine(context: Context): String =
        context.getSharedPreferences(PREF_ENGINE, Context.MODE_PRIVATE)
            .getString(KEY_ENGINE, ENGINE_QWEN3) ?: ENGINE_QWEN3

    fun setSelectedEngine(context: Context, engine: String) =
        context.getSharedPreferences(PREF_ENGINE, Context.MODE_PRIVATE)
            .edit().putString(KEY_ENGINE, engine).apply()


    // ── Dual Engine Mode ──────────────────────────────────────────────────────
    private const val PREF_DUAL_ENGINE = "dual_engine_settings"
    private const val KEY_DUAL_ENGINE_TOGGLE = "dual_engine_toggle"

    fun isDualEngineEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_DUAL_ENGINE, Context.MODE_PRIVATE)
            .getBoolean(KEY_DUAL_ENGINE_TOGGLE, false)

    fun setDualEngineEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREF_DUAL_ENGINE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DUAL_ENGINE_TOGGLE, enabled).apply()

    fun isDualEngineActive(context: Context): Boolean =
        isXAsrReady(context) &&
                isQwen3Ready(context) &&
                selectedEngine(context) == ENGINE_QWEN3 &&
                isDualEngineEnabled(context)

    // ── Qwen3 Punctuation Filter ──────────────────────────────────────────────
    private const val PREF_QWEN3_SETTINGS = "qwen3_settings"
    private const val KEY_FILTER_PUNCTUATION = "filter_punctuation_regex"

    fun isFilterPunctuationEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_QWEN3_SETTINGS, Context.MODE_PRIVATE)
            .getBoolean(KEY_FILTER_PUNCTUATION, false)

    fun setFilterPunctuationEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREF_QWEN3_SETTINGS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FILTER_PUNCTUATION, enabled).apply()

    // ── External Audio Routing ────────────────────────────────────────────────
    private const val PREF_AUDIO = "audio_routing_settings"
    private const val KEY_PREFER_EXTERNAL_AUDIO = "prefer_external_audio"
    private const val KEY_EXTERNAL_AUDIO_GAIN   = "external_audio_gain"

    const val DEFAULT_EXTERNAL_AUDIO_GAIN = 2.5f
    const val MIN_EXTERNAL_AUDIO_GAIN = 1.0f
    const val MAX_EXTERNAL_AUDIO_GAIN = 6.0f
    const val STEP_EXTERNAL_AUDIO_GAIN = 0.5f

    fun isPreferExternalAudio(context: Context): Boolean =
        context.getSharedPreferences(PREF_AUDIO, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREFER_EXTERNAL_AUDIO, false)

    fun setPreferExternalAudio(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREF_AUDIO, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PREFER_EXTERNAL_AUDIO, enabled).apply()

    fun externalAudioGain(context: Context): Float =
        context.getSharedPreferences(PREF_AUDIO, Context.MODE_PRIVATE)
            .getFloat(KEY_EXTERNAL_AUDIO_GAIN, DEFAULT_EXTERNAL_AUDIO_GAIN)

    fun setExternalAudioGain(context: Context, gain: Float) =
        context.getSharedPreferences(PREF_AUDIO, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_EXTERNAL_AUDIO_GAIN, gain).apply()

    private val CHINESE_PUNCTUATION_REGEX = Regex("[，。！？、…：；「」『』—～（）《》〈〉【】〔〕]")

    fun filterChinesePunctuation(text: String): String =
        text.replace(CHINESE_PUNCTUATION_REGEX, "")

    // ── Traditional Chinese (Taiwan MOE Standard & Pure Character Conversion) ──
    /**
     * Converts text to Traditional Chinese using pure character-level conversion (s2t)
     * without vocabulary substitution, preserving the speaker's exact original phrasing.
     * Then applies point-to-point correction for archaic, rare, and non-standard variants.
     */
    fun toTaiwanTraditional(raw: String): String {
        val converted = try {
            ZhConverterUtil.toTraditional(raw)
        } catch (_: Exception) {
            raw
        }
        return normalizeTaiwanVariants(converted)
    }

    /**
     * Point-to-point correction table for archaic / rare variant characters output by s2t,
     * converting them to modern standard Traditional Chinese characters.
     */
    private val VARIANT_REPLACEMENTS = mapOf(
        '纔' to '才',
        '裏' to '裡',
        '着' to '著',
        '麪' to '麵',
        '靣' to '麵',
        '衹' to '只',
        '喫' to '吃',
        '綫' to '線',
        '銹' to '鏽',
        '擡' to '抬',
        '粧' to '妝',
        '鑒' to '鑑',
        '讃' to '讚',
        '盃' to '杯',
        '牀' to '床',
        '佔' to '占',
        '剋' to '克',
        '樑' to '梁',
        '羣' to '群',
        '峯' to '峰',
        '綉' to '繡',
        '祕' to '秘',
        '獃' to '呆',
        '艷' to '豔',
        '踊' to '踴',
        '昇' to '升',
        '衆' to '眾',
        '啓' to '啟',
        '爲' to '為',
        '鉢' to '缽',
        '糉' to '粽',
        '賬' to '帳',
        '脣' to '唇'
    )

    // ── Word & Character Normalization ────────────────────────────────────────
    /**
     * 正則表達式字詞替換規則架構：
     * 實作要點：詞彙規則先執行，單字異體字後執行，全部在 OpenCC 轉換之後執行。
     * 1. 詞彙替換：
     *    - 拼命 -> 拚命、打拼 -> 打拚、拼搏 -> 拚搏（拼圖、拼貼等其他詞彙不變）
     *    - 迴應 -> 回應：除「巡迴/輪迴/迂迴/徘迴」等複合詞外，替換為「回應」
     * 2. 單字正則替換：
     *    - 蘸 -> 沾：除「蘸甲」、「蘸火」外，替換為常用字「沾」
     *    - 泄 -> 洩：除「排泄」維持原字外，替換為常用字「洩」（(?<!排)泄）
     */
    data class WordReplacementRule(
        val name: String,
        val fastCheck: (String) -> Boolean,
        val regex: Regex,
        val replacement: String
    )

    private val VOCABULARY_RULES = listOf(
        WordReplacementRule(
            name = "拼命 -> 拚命",
            fastCheck = { it.contains("拼命") },
            regex = Regex("拼命"),
            replacement = "拚命"
        ),
        WordReplacementRule(
            name = "打拼 -> 打拚",
            fastCheck = { it.contains("打拼") },
            regex = Regex("打拼"),
            replacement = "打拚"
        ),
        WordReplacementRule(
            name = "拼搏 -> 拚搏",
            fastCheck = { it.contains("拼搏") },
            regex = Regex("拼搏"),
            replacement = "拚搏"
        ),
        WordReplacementRule(
            name = "迴應 -> 回應",
            fastCheck = { it.contains("迴應") },
            regex = Regex("""(?<![巡輪迂徘])迴應"""),
            replacement = "回應"
        )
    )

    private val CHARACTER_RULES = listOf(
        WordReplacementRule(
            name = "蘸 -> 沾",
            fastCheck = { it.contains('蘸') },
            regex = Regex("""(?!(?:蘸甲|蘸火))蘸"""),
            replacement = "沾"
        ),
        WordReplacementRule(
            name = "泄 -> 洩",
            fastCheck = { it.contains('泄') },
            regex = Regex("""(?<!排)泄"""),
            replacement = "洩"
        )
    )

    fun applyVocabularyRules(text: String): String {
        if (text.isEmpty()) return text
        var result = text
        for (rule in VOCABULARY_RULES) {
            if (rule.fastCheck(result)) {
                result = result.replace(rule.regex, rule.replacement)
            }
        }
        return result
    }

    fun applyCharacterRules(text: String): String {
        if (text.isEmpty()) return text
        var result = text
        for (rule in CHARACTER_RULES) {
            if (rule.fastCheck(result)) {
                result = result.replace(rule.regex, rule.replacement)
            }
        }
        return result
    }

    fun replaceZhan(text: String): String {
        val rule = CHARACTER_RULES[0]
        if (text.isEmpty() || !rule.fastCheck(text)) return text
        return text.replace(rule.regex, rule.replacement)
    }

    fun replaceHuiYing(text: String): String {
        val rule = VOCABULARY_RULES[3]
        if (text.isEmpty() || !rule.fastCheck(text)) return text
        return text.replace(rule.regex, rule.replacement)
    }

    fun replaceXie(text: String): String {
        val rule = CHARACTER_RULES[1]
        if (text.isEmpty() || !rule.fastCheck(text)) return text
        return text.replace(rule.regex, rule.replacement)
    }

    fun replacePin(text: String): String {
        if (text.isEmpty() || !text.contains('拼')) return text
        var result = text
        for (i in 0..2) {
            val rule = VOCABULARY_RULES[i]
            if (rule.fastCheck(result)) {
                result = result.replace(rule.regex, rule.replacement)
            }
        }
        return result
    }

    fun applyPostRegexReplacements(text: String): String {
        if (text.isEmpty()) return text
        // 詞彙規則先執行
        val vocabApplied = applyVocabularyRules(text)
        // 單字正則替換
        return applyCharacterRules(vocabApplied)
    }

    fun normalizeTaiwanVariants(text: String): String {
        if (text.isEmpty()) return text
        // 1. 詞彙規則先執行
        val vocabApplied = applyVocabularyRules(text)
        // 2. 單字異體字替換
        val sb = java.lang.StringBuilder(vocabApplied.length)
        for (ch in vocabApplied) {
            sb.append(VARIANT_REPLACEMENTS[ch] ?: ch)
        }
        // 3. 單字正則規則
        return applyCharacterRules(sb.toString())
    }

    // ── PP-OCRv6 (Det & Rec) ────────────────────────────────────────────────
    const val OCR_DIR = "ocr"
    const val OCR_MODEL_TINY = "pp_ocrv6_tiny"
    const val OCR_MODEL_SMALL = "pp_ocrv6_small"
    const val ENGINE_PP_OCR_TINY = OCR_MODEL_TINY
    const val ENGINE_PP_OCR_SMALL = OCR_MODEL_SMALL

    const val PREF_OCR = "ocr_settings"
    const val KEY_OCR_MODEL_SELECTION = "ocr_model_selection"
    const val KEY_OCR_AUTO_ENTER = "ocr_auto_enter"
    const val KEY_OCR_SEPARATOR = "ocr_separator"

    fun selectedOcrModel(context: Context): String {
        val pref = context.getSharedPreferences(PREF_OCR, Context.MODE_PRIVATE)
            .getString(KEY_OCR_MODEL_SELECTION, null)
        if (pref != null) return pref
        if (isOcrSmallReady(context)) return OCR_MODEL_SMALL
        if (isOcrTinyReady(context)) return OCR_MODEL_TINY
        return OCR_MODEL_SMALL
    }

    fun setSelectedOcrModel(context: Context, model: String) =
        context.getSharedPreferences(PREF_OCR, Context.MODE_PRIVATE)
            .edit().putString(KEY_OCR_MODEL_SELECTION, model).commit()

    fun isOcrAutoEnterEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_OCR, Context.MODE_PRIVATE)
            .getBoolean(KEY_OCR_AUTO_ENTER, false)

    fun setOcrAutoEnterEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREF_OCR, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_OCR_AUTO_ENTER, enabled).apply()

    fun ocrSeparator(context: Context): String =
        context.getSharedPreferences(PREF_OCR, Context.MODE_PRIVATE)
            .getString(KEY_OCR_SEPARATOR, "\n") ?: "\n"

    fun setOcrSeparator(context: Context, sep: String) =
        context.getSharedPreferences(PREF_OCR, Context.MODE_PRIVATE)
            .edit().putString(KEY_OCR_SEPARATOR, sep).apply()

    fun ocrDir(context: Context): String {
        val candidates = listOfNotNull(
            context.getExternalFilesDir("models")?.let { File(it, OCR_DIR) },
            File(context.filesDir, "models/$OCR_DIR"),
            File(context.filesDir, OCR_DIR),
            context.getExternalFilesDir(OCR_DIR),
            context.getExternalFilesDir(null)?.let { File(it, "models/$OCR_DIR") },
            File("/storage/emulated/0/Android/data/${context.packageName}/files/models/$OCR_DIR"),
            File("/sdcard/Android/data/${context.packageName}/files/models/$OCR_DIR")
        )
        for (dir in candidates) {
            if (dir.exists() && dir.isDirectory) {
                val hasFiles = dir.listFiles()?.any {
                    it.name.endsWith(".onnx", ignoreCase = true) ||
                    it.name.endsWith(".txt", ignoreCase = true) ||
                    it.name.endsWith(".yml", ignoreCase = true)
                } == true
                if (hasFiles) return dir.absolutePath
            }
        }
        for (dir in candidates) {
            if (dir.exists()) return dir.absolutePath
        }
        return "${modelsDir(context)}/$OCR_DIR"
    }

    data class OcrPaths(
        val detPath: String,
        val recPath: String,
        val dictPath: String,
        val model: String
    )

    fun getOcrSearchDirs(context: Context): List<File> {
        return buildList {
            add(File(ocrDir(context)))
            context.getExternalFilesDir("models")?.let { add(File(it, OCR_DIR)); add(it) }
            add(File(context.filesDir, "models/$OCR_DIR"))
            add(File(context.filesDir, OCR_DIR))
            add(File(context.filesDir, "models"))
            add(context.filesDir)
            context.getExternalFilesDir(null)?.let { add(File(it, "models/$OCR_DIR")); add(File(it, OCR_DIR)); add(it) }
            add(File("/storage/emulated/0/Android/data/${context.packageName}/files/models/$OCR_DIR"))
            add(File("/sdcard/Android/data/${context.packageName}/files/models/$OCR_DIR"))
            add(File("/storage/emulated/0/Download/verbead_models/$OCR_DIR"))
            add(File("/sdcard/Download/verbead_models/$OCR_DIR"))
            add(File("/storage/emulated/0/Download"))
            add(File("/sdcard/Download"))
        }.distinctBy { it.canonicalPath }
    }

    /**
     * 嚴格尋找指定 OCR 模型（Tiny 或 Small）檔案路徑，絕不跨模型回退。
     */
    fun findSpecificOcrPathsInDirs(searchDirs: List<File>, model: String): OcrPaths? {
        val isTiny = (model == OCR_MODEL_TINY)
        val detName = "${model}_det.onnx"
        val recName = "${model}_rec.onnx"
        val dictName = "${model}_dict.txt"

        // 1. 標準檔案命名比對（不分大小寫）
        for (dir in searchDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val files = dir.listFiles() ?: continue
            val detFile = files.firstOrNull { it.name.equals(detName, ignoreCase = true) }
            val recFile = files.firstOrNull { it.name.equals(recName, ignoreCase = true) }
            if (detFile != null && recFile != null) {
                val dictFile = files.firstOrNull {
                    it.name.equals(dictName, ignoreCase = true) ||
                    it.name.equals("inference.yml", ignoreCase = true) ||
                    it.name.equals("dict.txt", ignoreCase = true)
                } ?: File(dir, dictName)
                return OcrPaths(detFile.absolutePath, recFile.absolutePath, dictFile.absolutePath, model)
            }
        }

        // 2. 寬鬆檔名特徵匹配（嚴格比對 tiny / small 關鍵字）
        for (dir in searchDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val files = dir.listFiles() ?: continue
            val detFile = files.firstOrNull {
                val n = it.name.lowercase()
                n.endsWith(".onnx") && (n.contains("det") || n.contains("detect")) &&
                    (if (isTiny) n.contains("tiny") else (!n.contains("tiny") || n.contains("small")))
            }
            val recFile = files.firstOrNull {
                val n = it.name.lowercase()
                n.endsWith(".onnx") && (n.contains("rec") || n.contains("recogn")) &&
                    (if (isTiny) n.contains("tiny") else (!n.contains("tiny") || n.contains("small")))
            }
            if (detFile != null && recFile != null) {
                val dictFile = files.firstOrNull {
                    val n = it.name.lowercase()
                    (n.endsWith(".txt") || n.endsWith(".yml") || n.endsWith(".yaml")) && (n.contains("dict") || n.contains("inference"))
                } ?: File(dir, dictName)
                return OcrPaths(detFile.absolutePath, recFile.absolutePath, dictFile.absolutePath, model)
            }
        }

        return null
    }

    fun findSpecificOcrPaths(context: Context, model: String): OcrPaths? =
        findSpecificOcrPathsInDirs(getOcrSearchDirs(context), model)

    /**
     * 搜尋可用 OCR 模型路徑。
     * 若指定 [preferredModel]，僅針對該模型搜尋；若未指定，優先依用戶選取設定搜尋，再依已下載的模型搜尋。
     */
    fun findOcrPaths(context: Context, preferredModel: String? = null): OcrPaths? {
        if (preferredModel != null) {
            return findSpecificOcrPaths(context, preferredModel)
        }
        val pref = context.getSharedPreferences(PREF_OCR, Context.MODE_PRIVATE)
            .getString(KEY_OCR_MODEL_SELECTION, null)
        if (pref != null) {
            val paths = findSpecificOcrPaths(context, pref)
            if (paths != null) return paths
        }
        return findSpecificOcrPaths(context, OCR_MODEL_SMALL)
            ?: findSpecificOcrPaths(context, OCR_MODEL_TINY)
    }

    fun ocrDetPath(context: Context, model: String = selectedOcrModel(context)): String =
        findSpecificOcrPaths(context, model)?.detPath ?: "${ocrDir(context)}/${model}_det.onnx"

    fun ocrRecPath(context: Context, model: String = selectedOcrModel(context)): String =
        findSpecificOcrPaths(context, model)?.recPath ?: "${ocrDir(context)}/${model}_rec.onnx"

    fun ocrDictPath(context: Context, model: String = selectedOcrModel(context)): String =
        findSpecificOcrPaths(context, model)?.dictPath ?: "${ocrDir(context)}/${model}_dict.txt"

    /** 嚴格判斷特定 OCR 模型（Tiny 或 Small）是否已下載並完整就緒。 */
    fun isOcrModelStrictlyReady(context: Context, model: String): Boolean {
        if (findSpecificOcrPaths(context, model) != null) return true
        val det = File("${ocrDir(context)}/${model}_det.onnx")
        val rec = File("${ocrDir(context)}/${model}_rec.onnx")
        return det.exists() && det.length() > 0L && rec.exists() && rec.length() > 0L
    }

    fun isOcrTinyReady(context: Context): Boolean = isOcrModelStrictlyReady(context, OCR_MODEL_TINY)

    fun isOcrSmallReady(context: Context): Boolean = isOcrModelStrictlyReady(context, OCR_MODEL_SMALL)

    /**
     * OCR 功能總體就緒判斷：其中任一種（Tiny 或 Small）備齊便可供使用。
     */
    fun isOcrReady(context: Context): Boolean = isOcrTinyReady(context) || isOcrSmallReady(context)

    /**
     * 特定模型是否就緒判斷。
     */
    fun isOcrReady(context: Context, model: String): Boolean = isOcrModelStrictlyReady(context, model)

    /**
     * 從指定資料夾刪除目標 OCR 模型的檔案。
     */
    fun deleteOcrFilesFromDir(dir: File, model: String) {
        val target = model.lowercase()
        if (!dir.exists() || !dir.isDirectory) return
        val files = dir.listFiles() ?: return
        for (file in files) {
            val name = file.name.lowercase()
            val isMatch = if (target == OCR_MODEL_TINY) {
                name.contains("tiny") && (name.endsWith(".onnx") || name.endsWith(".txt") || name.endsWith(".yml") || name.endsWith(".part"))
            } else if (target == OCR_MODEL_SMALL) {
                name.contains("small") && (name.endsWith(".onnx") || name.endsWith(".txt") || name.endsWith(".yml") || name.endsWith(".part"))
            } else {
                name.startsWith("${target}_")
            }
            if (isMatch) {
                try {
                    file.delete()
                } catch (e: Exception) {
                    android.util.Log.w("ModelConfig", "Failed to delete ${file.absolutePath}: ${e.message}")
                }
            }
        }
    }

    /**
     * 刪除指定的 OCR 模型核心檔案（互斥替換使用）。
     */
    fun deleteOcrModel(context: Context, model: String) {
        val dirs = getOcrSearchDirs(context)
        for (dir in dirs) {
            deleteOcrFilesFromDir(dir, model)
        }
    }

    // ── Camera & Scanner Settings ─────────────────────────────────────────────
    private const val PREF_CAMERA = "camera_settings"
    private const val KEY_CAMERA_ZOOM_RATIO = "camera_zoom_ratio"
    private const val KEY_CAMERA_ZOOM_OCR = "camera_zoom_ocr"
    private const val KEY_CAMERA_ZOOM_SCANNER = "camera_zoom_scanner"
    private const val KEY_OCR_ENLARGED = "ocr_enlarged"
    private const val KEY_SCANNER_ENLARGED = "scanner_enlarged"

    fun getCameraZoom(context: Context, mode: Int): Float {
        val prefs = context.getSharedPreferences(PREF_CAMERA, Context.MODE_PRIVATE)
        val key = if (mode == 1) KEY_CAMERA_ZOOM_OCR else KEY_CAMERA_ZOOM_SCANNER
        if (prefs.contains(key)) {
            return prefs.getFloat(key, 0f).coerceIn(0f, 1f)
        }
        return prefs.getFloat(KEY_CAMERA_ZOOM_RATIO, 0f).coerceIn(0f, 1f)
    }

    fun setCameraZoom(context: Context, mode: Int, zoom: Float) {
        val clamped = zoom.coerceIn(0f, 1f)
        val key = if (mode == 1) KEY_CAMERA_ZOOM_OCR else KEY_CAMERA_ZOOM_SCANNER
        context.getSharedPreferences(PREF_CAMERA, Context.MODE_PRIVATE)
            .edit()
            .putFloat(key, clamped)
            .putFloat(KEY_CAMERA_ZOOM_RATIO, clamped)
            .apply()
    }

    fun isCameraEnlarged(context: Context, mode: Int): Boolean =
        context.getSharedPreferences(PREF_CAMERA, Context.MODE_PRIVATE)
            .getBoolean(if (mode == 1) KEY_OCR_ENLARGED else KEY_SCANNER_ENLARGED, false)

    fun setCameraEnlarged(context: Context, mode: Int, enlarged: Boolean) =
        context.getSharedPreferences(PREF_CAMERA, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(if (mode == 1) KEY_OCR_ENLARGED else KEY_SCANNER_ENLARGED, enlarged)
            .apply()

    // ── Model Readiness Checks ────────────────────────────────────────────────
    fun isXAsrReady(context: Context): Boolean {
        val files = listOf(
            xAsrEncoderPath(context),
            xAsrDecoderPath(context),
            xAsrJoinerPath(context),
            xAsrTokensPath(context),
        )
        return files.all { java.io.File(it).exists() }
    }

    fun isQwen3Ready(context: Context): Boolean {
        val files = listOf(
            qwen3AsrConvFrontendPath(context),
            qwen3AsrEncoderPath(context),
            qwen3AsrDecoderPath(context),
        )
        return files.all { java.io.File(it).exists() } && java.io.File(qwen3AsrTokenizerDir(context)).isDirectory
    }

    fun isModelReady(context: Context, engine: String): Boolean =
        when (engine) {
            ENGINE_X_ASR -> isXAsrReady(context)
            OCR_MODEL_TINY, OCR_MODEL_SMALL -> isOcrReady(context, engine)
            else -> isQwen3Ready(context)
        }

    fun areAllModelsReady(context: Context): Boolean =
        isXAsrReady(context) && isQwen3Ready(context) && isOcrReady(context)

    // ── Onboarding / Setup Wizard ─────────────────────────────────────────────
    const val MODE_BUBBLE = "bubble"

    private const val PREF_ONBOARDING = "onboarding_settings"
    private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    private const val KEY_ONBOARDING_MODE = "onboarding_mode"

    fun isOnboardingCompleted(context: Context): Boolean =
        context.getSharedPreferences(PREF_ONBOARDING, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDING_COMPLETED, false)

    fun setOnboardingCompleted(context: Context, completed: Boolean) =
        context.getSharedPreferences(PREF_ONBOARDING, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply()

    fun getOnboardingMode(context: Context): String =
        context.getSharedPreferences(PREF_ONBOARDING, Context.MODE_PRIVATE)
            .getString(KEY_ONBOARDING_MODE, MODE_BUBBLE) ?: MODE_BUBBLE

    fun setOnboardingMode(context: Context, mode: String = MODE_BUBBLE) =
        context.getSharedPreferences(PREF_ONBOARDING, Context.MODE_PRIVATE)
            .edit().putString(KEY_ONBOARDING_MODE, mode).apply()

    // ── Floating Bubble Preferences ───────────────────────────────────────────
    const val PREF_BUBBLE_SETTINGS = "bubble_settings"
    const val KEY_SHOW_ONLY_ON_KEYBOARD = "show_only_on_keyboard"

    fun isShowOnlyOnKeyboard(context: Context): Boolean =
        context.getSharedPreferences(PREF_BUBBLE_SETTINGS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_ONLY_ON_KEYBOARD, false)

    fun setShowOnlyOnKeyboard(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREF_BUBBLE_SETTINGS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_ONLY_ON_KEYBOARD, enabled).apply()

    // ── Text Injection Settings ───────────────────────────────────────────────
    private const val PREF_INJECTION_SETTINGS = "injection_settings"
    const val KEY_TEXT_INJECTION_METHOD = "text_injection_method"
    const val INJECTION_SET_TEXT = "set_text"
    const val INJECTION_PASTE = "text_paste"

    fun getTextInjectionMethod(context: Context): String =
        context.getSharedPreferences(PREF_INJECTION_SETTINGS, Context.MODE_PRIVATE)
            .getString(KEY_TEXT_INJECTION_METHOD, INJECTION_SET_TEXT) ?: INJECTION_SET_TEXT

    fun setTextInjectionMethod(context: Context, method: String) =
        context.getSharedPreferences(PREF_INJECTION_SETTINGS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TEXT_INJECTION_METHOD, method).apply()

    fun isPasteModeEnabled(context: Context): Boolean =
        getTextInjectionMethod(context) == INJECTION_PASTE

    fun setPasteModeEnabled(context: Context, enabled: Boolean) =
        setTextInjectionMethod(context, if (enabled) INJECTION_PASTE else INJECTION_SET_TEXT)

    // ── Quick Phrases & History Settings ──────────────────────────────────────
    const val DEFAULT_HISTORY_LIMIT = 6
    const val MIN_HISTORY_LIMIT = 1
    const val MAX_HISTORY_LIMIT = 12
    const val KEY_HISTORY_ENABLED = "history_enabled"
    const val KEY_HISTORY_LIMIT = "history_limit"

    fun isHistoryEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_BUBBLE_SETTINGS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HISTORY_ENABLED, true)

    fun setHistoryEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREF_BUBBLE_SETTINGS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_HISTORY_ENABLED, enabled).apply()

    fun getHistoryLimit(context: Context): Int =
        context.getSharedPreferences(PREF_BUBBLE_SETTINGS, Context.MODE_PRIVATE)
            .getInt(KEY_HISTORY_LIMIT, DEFAULT_HISTORY_LIMIT)
            .coerceIn(MIN_HISTORY_LIMIT, MAX_HISTORY_LIMIT)

    fun setHistoryLimit(context: Context, limit: Int) =
        context.getSharedPreferences(PREF_BUBBLE_SETTINGS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_HISTORY_LIMIT, limit.coerceIn(MIN_HISTORY_LIMIT, MAX_HISTORY_LIMIT)).apply()
}
