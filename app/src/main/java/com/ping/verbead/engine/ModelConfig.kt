package com.ping.verbead.engine

import android.content.Context

import androidx.annotation.StringRes
import com.ping.verbead.R
import com.ping.verbead.ocr.OcrShapes
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
        val internal = File(context.filesDir, "models")
        if (File(internal, OCR_DIR).exists() || File(internal, X_ASR_DIR).exists() || File(internal, QWEN3_ASR_DIR).exists()) {
            return internal.absolutePath
        }
        return primary?.absolutePath ?: internal.absolutePath
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

    // ── Hotwords switch ───────────────────────────────────────────────────────
    private const val KEY_HOTWORDS_ENABLED = "hotwords_enabled"

    /**
     * 是否把自訂詞庫當成熱詞提示詞送給 Qwen3-ASR。預設開啟。
     * 不分輸入語言：詞庫裡的詞可以是中、英、日、韓文。熱詞會被插入模型的提示詞，
     * 靜音或底噪時模型可能把熱詞當內容輸出；關閉後詞庫的取代規則仍照常運作。
     */
    fun isHotwordsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_QWEN3_SETTINGS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HOTWORDS_ENABLED, true)



    fun setHotwordsEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREF_QWEN3_SETTINGS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_HOTWORDS_ENABLED, enabled).apply()

    // ── Spoken numbers switch ─────────────────────────────────────────────────
    private const val KEY_SPOKEN_NUMBERS = "spoken_numbers_to_digits"

    /** 是否把口語中文數字與加減乘除、百分比、分數、時間等轉成阿拉伯數字。預設關閉，避免改變既有輸出。 */
    fun isSpokenNumbersEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_QWEN3_SETTINGS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SPOKEN_NUMBERS, false)

    fun setSpokenNumbersEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREF_QWEN3_SETTINGS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SPOKEN_NUMBERS, enabled).apply()

    // ── Traditional Chinese conversion switch ─────────────────────────────────
    private const val KEY_CONVERT_TRADITIONAL = "convert_to_traditional"

    /** 關閉時語音輸出不做簡繁轉換與台灣用詞規則，原樣保留辨識結果。預設開啟。 */
    fun isConvertToTraditionalEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_QWEN3_SETTINGS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CONVERT_TRADITIONAL, true)

    fun setConvertToTraditionalEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREF_QWEN3_SETTINGS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CONVERT_TRADITIONAL, enabled).apply()

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
    const val KEY_OCR_SMART_STITCH = "ocr_smart_stitch"

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
            .edit().putString(KEY_OCR_MODEL_SELECTION, model).apply()

    fun isOcrAutoEnterEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_OCR, Context.MODE_PRIVATE)
            .getBoolean(KEY_OCR_AUTO_ENTER, false)

    fun setOcrAutoEnterEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREF_OCR, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_OCR_AUTO_ENTER, enabled).apply()

    fun isOcrSmartStitchEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_OCR, Context.MODE_PRIVATE)
            .getBoolean(KEY_OCR_SMART_STITCH, true)

    fun setOcrSmartStitchEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREF_OCR, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_OCR_SMART_STITCH, enabled).apply()

    const val KEY_OCR_PANGU_SPACING = "ocr_pangu_spacing"

    fun isOcrPanguSpacingEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_OCR, Context.MODE_PRIVATE)
            .getBoolean(KEY_OCR_PANGU_SPACING, false)

    fun setOcrPanguSpacingEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREF_OCR, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_OCR_PANGU_SPACING, enabled).apply()

    const val KEY_OCR_TO_TRADITIONAL = "ocr_to_traditional"

    fun isOcrToTraditionalEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_OCR, Context.MODE_PRIVATE)
            .getBoolean(KEY_OCR_TO_TRADITIONAL, true)

    fun setOcrToTraditionalEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREF_OCR, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_OCR_TO_TRADITIONAL, enabled).apply()

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
                    it.name.endsWith(".tflite", ignoreCase = true) ||
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

    /** A folder that holds every file of one OCR model ([dir]), plus the path of its dictionary. */
    data class OcrPaths(
        val dir: String,
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
     * File names a complete OCR model needs: the detector at each fixed size, the recognizer at each width of the
     * model's ladder, and the dictionary. All of them are LiteRT files (see [OcrModelManifest]).
     */
    fun requiredOcrFiles(model: String): List<String> {
        val tiny = model == OCR_MODEL_TINY
        return buildList {
            for ((w, h) in OcrShapes.DET_SIZES) add("${model}_det_${w}x${h}.tflite")
            for (w in OcrShapes.recWidths(tiny)) add("${model}_rec_${w}.tflite")
            add("${model}_dict.txt")
        }
    }

    /**
     * 嚴格尋找指定 OCR 模型（Tiny 或 Small）：同一個資料夾內必須備齊 [requiredOcrFiles] 的每個檔案，絕不跨模型回退。
     */
    fun findSpecificOcrPathsInDirs(searchDirs: List<File>, model: String): OcrPaths? {
        val required = requiredOcrFiles(model)
        for (dir in searchDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            if (required.all { name -> File(dir, name).let { it.isFile && it.length() > 0L } }) {
                return OcrPaths(dir.absolutePath, File(dir, "${model}_dict.txt").absolutePath, model)
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

    /** 嚴格判斷特定 OCR 模型（Tiny 或 Small）是否已下載並完整就緒。 */
    fun isOcrModelStrictlyReady(context: Context, model: String): Boolean {
        return findSpecificOcrPaths(context, model) != null
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
                name.contains("tiny") && (name.endsWith(".tflite") || name.endsWith(".onnx") || name.endsWith(".txt") || name.endsWith(".yml") || name.endsWith(".part"))
            } else if (target == OCR_MODEL_SMALL) {
                name.contains("small") && (name.endsWith(".tflite") || name.endsWith(".onnx") || name.endsWith(".txt") || name.endsWith(".yml") || name.endsWith(".part"))
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

    /**
     * Older versions ran PP-OCR on ONNX Runtime and downloaded pp_ocrv6_*_det.onnx / _rec.onnx. They are useless now;
     * delete them (and unfinished .part downloads of them) from the app-private model folders. Returns the bytes freed.
     */
    fun deleteLegacyOcrOnnx(context: Context): Long {
        val dirs = listOfNotNull(
            context.getExternalFilesDir("models")?.let { File(it, OCR_DIR) },
            File(context.filesDir, "models/$OCR_DIR"),
            File(context.filesDir, OCR_DIR),
            context.getExternalFilesDir(null)?.let { File(it, "models/$OCR_DIR") }
        ).distinctBy { it.absolutePath }
        var freed = 0L
        for (dir in dirs) {
            for (f in dir.listFiles() ?: continue) {
                val n = f.name.lowercase()
                if (n.startsWith("pp_ocrv6_") && f.isFile && (n.endsWith(".onnx") || n.endsWith(".onnx.part"))) {
                    val size = f.length()
                    if (f.delete()) freed += size
                }
            }
        }
        return freed
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

    // ── Multi Bubble Preferences ──────────────────────────────────────────────
    const val KEY_MULTI_BUBBLE_ENABLED = "multi_bubble_enabled"
    const val KEY_MULTI_BUBBLE_COUNT = "multi_bubble_count"
    const val DEFAULT_MULTI_BUBBLE_COUNT = 4
    const val MIN_MULTI_BUBBLE_COUNT = 2
    const val MAX_MULTI_BUBBLE_COUNT = 4

    fun isMultiBubbleEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_BUBBLE_SETTINGS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MULTI_BUBBLE_ENABLED, false)

    fun setMultiBubbleEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREF_BUBBLE_SETTINGS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MULTI_BUBBLE_ENABLED, enabled).apply()

    /**
     * Resolves the maximum number of multi-bubbles permitted given the number of enabled modes.
     * Guaranteed to fall within 1..MAX_MULTI_BUBBLE_COUNT.
     */
    fun resolveMaxMultiBubbleCount(enabledModesCount: Int): Int =
        enabledModesCount.coerceIn(1, MAX_MULTI_BUBBLE_COUNT)

    fun getMaxMultiBubbleCountForEnabledModes(context: Context): Int =
        resolveMaxMultiBubbleCount(getEnabledBubbleModes(context).size)

    fun getMultiBubbleCount(context: Context): Int {
        val maxAllowed = getMaxMultiBubbleCountForEnabledModes(context)
        val saved = context.getSharedPreferences(PREF_BUBBLE_SETTINGS, Context.MODE_PRIVATE)
            .getInt(KEY_MULTI_BUBBLE_COUNT, DEFAULT_MULTI_BUBBLE_COUNT)
            .coerceIn(MIN_MULTI_BUBBLE_COUNT, MAX_MULTI_BUBBLE_COUNT)
        return if (maxAllowed >= MIN_MULTI_BUBBLE_COUNT) saved.coerceAtMost(maxAllowed) else MIN_MULTI_BUBBLE_COUNT
    }

    fun setMultiBubbleCount(context: Context, count: Int) {
        val maxAllowed = getMaxMultiBubbleCountForEnabledModes(context)
        val safeMin = minOf(MIN_MULTI_BUBBLE_COUNT, maxAllowed)
        val safeMax = maxOf(MIN_MULTI_BUBBLE_COUNT, maxAllowed)
        context.getSharedPreferences(PREF_BUBBLE_SETTINGS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MULTI_BUBBLE_COUNT, count.coerceIn(safeMin, safeMax)).apply()
    }

    // ── Bubble Modes Enable/Disable & Custom Ordering ─────────────────────────
    const val BUBBLE_MODE_VOICE = 0
    const val BUBBLE_MODE_OCR = 1
    const val BUBBLE_MODE_BARCODE = 2
    const val BUBBLE_MODE_PHRASES = 3

    const val KEY_BUBBLE_MODE_ORDER = "bubble_mode_order"
    const val KEY_BUBBLE_DISABLED_MODES = "bubble_disabled_modes"

    val DEFAULT_BUBBLE_MODE_ORDER = listOf(
        BUBBLE_MODE_VOICE,
        BUBBLE_MODE_BARCODE,
        BUBBLE_MODE_OCR,
        BUBBLE_MODE_PHRASES
    )

    data class BubbleModeMeta(
        val mode: Int,
        @StringRes val titleRes: Int,
        @StringRes val shortNameRes: Int,
        @StringRes val descriptionRes: Int
    )

    val BUBBLE_MODE_METADATA = mapOf(
        BUBBLE_MODE_VOICE to BubbleModeMeta(
            mode = BUBBLE_MODE_VOICE,
            titleRes = R.string.bubble_mode_voice_title,
            shortNameRes = R.string.bubble_mode_meta_voice_short,
            descriptionRes = R.string.bubble_mode_meta_voice_desc
        ),
        BUBBLE_MODE_BARCODE to BubbleModeMeta(
            mode = BUBBLE_MODE_BARCODE,
            titleRes = R.string.floating_scanner_title,
            shortNameRes = R.string.bubble_mode_meta_barcode_short,
            descriptionRes = R.string.bubble_mode_meta_barcode_desc
        ),
        BUBBLE_MODE_OCR to BubbleModeMeta(
            mode = BUBBLE_MODE_OCR,
            titleRes = R.string.bubble_mode_meta_ocr_title,
            shortNameRes = R.string.bubble_mode_meta_ocr_short,
            descriptionRes = R.string.bubble_mode_meta_ocr_desc
        ),
        BUBBLE_MODE_PHRASES to BubbleModeMeta(
            mode = BUBBLE_MODE_PHRASES,
            titleRes = R.string.capsule_phrases_drawer_desc,
            shortNameRes = R.string.common_quick_phrases,
            descriptionRes = R.string.bubble_mode_meta_phrases_desc
        )
    )

    fun getBubbleModeMeta(mode: Int): BubbleModeMeta =
        BUBBLE_MODE_METADATA[mode] ?: BUBBLE_MODE_METADATA.getValue(BUBBLE_MODE_VOICE)

    fun parseModeOrder(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) return DEFAULT_BUBBLE_MODE_ORDER
        val parsed = raw.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in DEFAULT_BUBBLE_MODE_ORDER }
            .distinct()
            .toMutableList()
        for (m in DEFAULT_BUBBLE_MODE_ORDER) {
            if (m !in parsed) {
                parsed.add(m)
            }
        }
        return parsed
    }

    fun serializeModeOrder(order: List<Int>): String =
        parseModeOrder(order.joinToString(",")).joinToString(",")

    fun parseDisabledModes(raw: String?, currentOrder: List<Int> = DEFAULT_BUBBLE_MODE_ORDER): Set<Int> {
        if (raw.isNullOrBlank()) return emptySet()
        val parsed = raw.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in DEFAULT_BUBBLE_MODE_ORDER }
            .toMutableSet()
        val normalizedOrder = parseModeOrder(currentOrder.joinToString(","))
        if (parsed.size >= normalizedOrder.size) {
            // Ensure at least one mode remains enabled
            parsed.remove(normalizedOrder.first())
        }
        return parsed
    }

    fun computeEnabledModes(order: List<Int>, disabledModes: Set<Int>): List<Int> {
        val normalizedOrder = parseModeOrder(order.joinToString(","))
        val enabled = normalizedOrder.filter { it !in disabledModes }
        return enabled.ifEmpty { listOf(normalizedOrder.first()) }
    }

    fun moveModeInOrder(currentOrder: List<Int>, fromIndex: Int, toIndex: Int): List<Int> {
        val mutable = parseModeOrder(currentOrder.joinToString(",")).toMutableList()
        if (fromIndex !in mutable.indices || toIndex !in mutable.indices || fromIndex == toIndex) {
            return mutable
        }
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        return mutable
    }

    fun getBubbleModeOrder(context: Context): List<Int> {
        val raw = context.getSharedPreferences(PREF_BUBBLE_SETTINGS, Context.MODE_PRIVATE)
            .getString(KEY_BUBBLE_MODE_ORDER, null)
        return parseModeOrder(raw)
    }

    fun setBubbleModeOrder(context: Context, order: List<Int>) {
        val serialized = serializeModeOrder(order)
        context.getSharedPreferences(PREF_BUBBLE_SETTINGS, Context.MODE_PRIVATE)
            .edit().putString(KEY_BUBBLE_MODE_ORDER, serialized).apply()
    }

    fun getDisabledBubbleModes(context: Context): Set<Int> {
        val raw = context.getSharedPreferences(PREF_BUBBLE_SETTINGS, Context.MODE_PRIVATE)
            .getString(KEY_BUBBLE_DISABLED_MODES, null)
        return parseDisabledModes(raw, getBubbleModeOrder(context))
    }

    fun isBubbleModeEnabled(context: Context, mode: Int): Boolean =
        mode !in getDisabledBubbleModes(context)

    fun getEnabledBubbleModes(context: Context): List<Int> =
        computeEnabledModes(getBubbleModeOrder(context), getDisabledBubbleModes(context))

    /**
     * Enables or disables a specific bubble mode.
     * Returns false if the user attempts to disable the last remaining enabled mode.
     */
    fun setBubbleModeEnabled(context: Context, mode: Int, enabled: Boolean): Boolean {
        if (mode !in DEFAULT_BUBBLE_MODE_ORDER) return false
        val currentOrder = getBubbleModeOrder(context)
        val disabled = getDisabledBubbleModes(context).toMutableSet()
        if (enabled) {
            disabled.remove(mode)
        } else {
            val currentlyEnabledCount = currentOrder.count { it !in disabled && it != mode }
            if (currentlyEnabledCount < 1) {
                return false
            }
            disabled.add(mode)
        }
        context.getSharedPreferences(PREF_BUBBLE_SETTINGS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BUBBLE_DISABLED_MODES, disabled.joinToString(","))
            .apply()

        val newEnabledCount = currentOrder.count { it !in disabled }
        if (newEnabledCount < MIN_MULTI_BUBBLE_COUNT && isMultiBubbleEnabled(context)) {
            setMultiBubbleEnabled(context, false)
        } else if (newEnabledCount >= MIN_MULTI_BUBBLE_COUNT) {
            val currentCount = context.getSharedPreferences(PREF_BUBBLE_SETTINGS, Context.MODE_PRIVATE)
                .getInt(KEY_MULTI_BUBBLE_COUNT, DEFAULT_MULTI_BUBBLE_COUNT)
            if (currentCount > newEnabledCount) {
                setMultiBubbleCount(context, newEnabledCount)
            }
        }
        return true
    }

    fun resetBubbleModesToDefault(context: Context) {
        context.getSharedPreferences(PREF_BUBBLE_SETTINGS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_BUBBLE_MODE_ORDER)
            .remove(KEY_BUBBLE_DISABLED_MODES)
            .apply()
    }

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
    const val KEY_PENDING_TEXT_COUNT = "pending_text_count"
    const val DEFAULT_PENDING_TEXT_COUNT = 3
    const val MAX_PENDING_TEXT_COUNT = 5

    fun isHistoryEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_BUBBLE_SETTINGS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HISTORY_ENABLED, true)

    fun setHistoryEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREF_BUBBLE_SETTINGS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_HISTORY_ENABLED, enabled).apply()

    /**
     * How many recognized results may wait as floating quick-fill text when nothing can be filled in
     * (0 = the feature is off; the clipboard fallback still happens).
     */
    fun getPendingTextCount(context: Context): Int =
        context.getSharedPreferences(PREF_BUBBLE_SETTINGS, Context.MODE_PRIVATE)
            .getInt(KEY_PENDING_TEXT_COUNT, DEFAULT_PENDING_TEXT_COUNT)
            .coerceIn(0, MAX_PENDING_TEXT_COUNT)

    fun setPendingTextCount(context: Context, count: Int) =
        context.getSharedPreferences(PREF_BUBBLE_SETTINGS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_PENDING_TEXT_COUNT, count.coerceIn(0, MAX_PENDING_TEXT_COUNT)).apply()

    fun getHistoryLimit(context: Context): Int =
        context.getSharedPreferences(PREF_BUBBLE_SETTINGS, Context.MODE_PRIVATE)
            .getInt(KEY_HISTORY_LIMIT, DEFAULT_HISTORY_LIMIT)
            .coerceIn(MIN_HISTORY_LIMIT, MAX_HISTORY_LIMIT)

    fun setHistoryLimit(context: Context, limit: Int) =
        context.getSharedPreferences(PREF_BUBBLE_SETTINGS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_HISTORY_LIMIT, limit.coerceIn(MIN_HISTORY_LIMIT, MAX_HISTORY_LIMIT)).apply()
}
