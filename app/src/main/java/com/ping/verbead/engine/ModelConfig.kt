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

    // ── Floating Bubble Mode ──────────────────────────────────────────────────
    private const val PREF_BUBBLE = "bubble_settings"
    private const val KEY_BUBBLE_ENABLED = "bubble_enabled"

    fun isFloatingBubbleEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_BUBBLE, Context.MODE_PRIVATE)
            .getBoolean(KEY_BUBBLE_ENABLED, false)

    fun setFloatingBubbleEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREF_BUBBLE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BUBBLE_ENABLED, enabled).apply()

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
        if (pref != null && isOcrReady(context, pref)) return pref
        if (isOcrReady(context, OCR_MODEL_SMALL)) return OCR_MODEL_SMALL
        if (isOcrReady(context, OCR_MODEL_TINY)) return OCR_MODEL_TINY
        return pref ?: OCR_MODEL_SMALL
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

    fun findOcrPaths(context: Context, preferredModel: String? = null): OcrPaths? {
        val targetModel = preferredModel ?: run {
            val pref = context.getSharedPreferences(PREF_OCR, Context.MODE_PRIVATE)
                .getString(KEY_OCR_MODEL_SELECTION, null)
            pref ?: OCR_MODEL_SMALL
        }
        val modelsToCheck = if (targetModel == OCR_MODEL_TINY) {
            listOf(OCR_MODEL_TINY, OCR_MODEL_SMALL)
        } else {
            listOf(OCR_MODEL_SMALL, OCR_MODEL_TINY)
        }

        val searchDirs = buildList {
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

        // 1. Check exact standard filenames (case-insensitive)
        for (m in modelsToCheck) {
            val detName = "${m}_det.onnx"
            val recName = "${m}_rec.onnx"
            val dictName = "${m}_dict.txt"

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
                    return OcrPaths(detFile.absolutePath, recFile.absolutePath, dictFile.absolutePath, m)
                }
            }
        }

        // 2. Flexible detection / recognition filename pattern matching
        for (dir in searchDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val files = dir.listFiles() ?: continue
            val detFile = files.firstOrNull {
                val n = it.name.lowercase()
                n.endsWith(".onnx") && (n.contains("det") || n.contains("detect"))
            }
            val recFile = files.firstOrNull {
                val n = it.name.lowercase()
                n.endsWith(".onnx") && (n.contains("rec") || n.contains("recogn"))
            }
            if (detFile != null && recFile != null) {
                val dictFile = files.firstOrNull {
                    val n = it.name.lowercase()
                    (n.endsWith(".txt") || n.endsWith(".yml") || n.endsWith(".yaml")) && (n.contains("dict") || n.contains("inference"))
                } ?: File(dir, "pp_ocrv6_small_dict.txt")
                val detectedModel = if (detFile.name.contains("tiny", ignoreCase = true)) OCR_MODEL_TINY else OCR_MODEL_SMALL
                return OcrPaths(detFile.absolutePath, recFile.absolutePath, dictFile.absolutePath, detectedModel)
            }
        }

        return null
    }

    fun ocrDetPath(context: Context, model: String = selectedOcrModel(context)): String =
        findOcrPaths(context, model)?.detPath ?: "${ocrDir(context)}/${model}_det.onnx"

    fun ocrRecPath(context: Context, model: String = selectedOcrModel(context)): String =
        findOcrPaths(context, model)?.recPath ?: "${ocrDir(context)}/${model}_rec.onnx"

    fun ocrDictPath(context: Context, model: String = selectedOcrModel(context)): String =
        findOcrPaths(context, model)?.dictPath ?: "${ocrDir(context)}/${model}_dict.txt"

    fun isOcrReady(context: Context, model: String = selectedOcrModel(context)): Boolean {
        if (findOcrPaths(context, model) != null) return true
        return java.io.File("${ocrDir(context)}/${model}_det.onnx").exists() &&
               java.io.File("${ocrDir(context)}/${model}_rec.onnx").exists()
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
}
