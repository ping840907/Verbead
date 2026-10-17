package com.ping.verbead.slm

import android.content.Context
import java.io.File

object SlmModelConfig {

    const val PREF_SLM = "slm_settings"
    const val KEY_SLM_MODEL = "selected_slm_model"
    const val KEY_SLM_THINKING = "slm_thinking_enabled"
    const val KEY_SLM_OUTPUT_MODE = "slm_output_mode"
    const val KEY_SLM_HARDWARE_ACCEL = "slm_hardware_accel"
    const val KEY_SLM_BACKEND = "slm_backend"

    // 輸出模式設定：極簡快答 (預設)、重點條列、潤飾/翻譯、深度推論
    const val OUTPUT_MODE_FAST = "fast"
    const val OUTPUT_MODE_BULLET = "bullet"
    const val OUTPUT_MODE_REFINE = "refine"
    const val OUTPUT_MODE_DEEP = "deep"

    const val DEFAULT_OUTPUT_MODE = OUTPUT_MODE_FAST
    const val DEFAULT_THINKING_ENABLED = false // 手機端側預設快答直出，避免小模型思維外溢延遲

    // 硬體加速設定：預設確保運算完全 offload 跑在 GPU / NPU 上 (99 層全卸載)
    const val DEFAULT_HARDWARE_ACCEL_ENABLED = true
    const val DEFAULT_GPU_LAYERS = 99
    const val BACKEND_AUTO = "auto"
    const val BACKEND_VULKAN = "vulkan"
    const val BACKEND_NNAPI = "nnapi"
    const val BACKEND_QNN = "qnn"
    const val BACKEND_CPU = "cpu"

    // 4 個 Qwen 3.5 模型選項 (0.8B, 2B, 4B, 9B)
    const val MODEL_QWEN_0_8B = "qwen3.5_0.8b"
    const val MODEL_QWEN_2B = "qwen3.5_2b"
    const val MODEL_QWEN_4B = "qwen3.5_4b"
    const val MODEL_QWEN_9B = "qwen3.5_9b"

    const val DEFAULT_MODEL = MODEL_QWEN_2B

    const val SLM_DIR = "slm"
    const val FILE_QWEN_0_8B_GGUF = "Qwen3.5-0.8B-Q4_K_M.gguf"
    const val FILE_QWEN_2B_GGUF = "Qwen3.5-2B-Q4_K_M.gguf"
    const val FILE_QWEN_4B_GGUF = "Qwen3.5-4B-Q4_K_M.gguf"
    const val FILE_QWEN_9B_GGUF = "Qwen3.5-9B-Q4_K_M.gguf"

    data class ModelMetadata(
        val id: String,
        val displayName: String,
        val shortTag: String,
        val chipLabel: String,
        val description: String,
        val parameterCount: String,
        val quantizedSize: String,
        val fileName: String,
        val downloadUrl: String
    )

    val AVAILABLE_MODELS = listOf(
        ModelMetadata(
            id = MODEL_QWEN_0_8B,
            displayName = "Qwen3.5-0.8B",
            shortTag = "極致能效",
            chipLabel = "0.8B 能效",
            description = "極低功耗與超高速回應，記憶體開銷小於 600MB，適合即時潤飾、語音快答與快速總結",
            parameterCount = "0.8B",
            quantizedSize = "約 503 MB (Q4_K_M)",
            fileName = FILE_QWEN_0_8B_GGUF,
            downloadUrl = "https://huggingface.co/lmstudio-community/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf"
        ),
        ModelMetadata(
            id = MODEL_QWEN_2B,
            displayName = "Qwen3.5-2B",
            shortTag = "平衡通用",
            chipLabel = "2B 平衡",
            description = "兼顧速度與優秀語言理解力，日常對話、郵件草擬與精準摘要的最佳平衡點",
            parameterCount = "2.0B",
            quantizedSize = "約 1.2 GB (Q4_K_M)",
            fileName = FILE_QWEN_2B_GGUF,
            downloadUrl = "https://huggingface.co/lmstudio-community/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf"
        ),
        ModelMetadata(
            id = MODEL_QWEN_4B,
            displayName = "Qwen3.5-4B",
            shortTag = "聰明精準",
            chipLabel = "4B 聰明",
            description = "具備強大邏輯推理與多步驟思維能力，適合複雜問題分析、編程協助與深度思考",
            parameterCount = "4.0B",
            quantizedSize = "約 2.5 GB (Q4_K_M)",
            fileName = FILE_QWEN_4B_GGUF,
            downloadUrl = "https://huggingface.co/lmstudio-community/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf"
        ),
        ModelMetadata(
            id = MODEL_QWEN_9B,
            displayName = "Qwen3.5-9B",
            shortTag = "旗艦智能",
            chipLabel = "9B 旗艦",
            description = "旗艦級端側巨量參數量，最高等級多領域通識與智力水準，適合大 RAM 旗艦手機",
            parameterCount = "9.0B",
            quantizedSize = "約 5.2 GB (Q4_K_M)",
            fileName = FILE_QWEN_9B_GGUF,
            downloadUrl = "https://huggingface.co/lmstudio-community/Qwen3.5-9B-GGUF/resolve/main/Qwen3.5-9B-Q4_K_M.gguf"
        )
    )

    fun getSelectedModel(context: Context): String {
        val saved = context.getSharedPreferences(PREF_SLM, Context.MODE_PRIVATE)
            .getString(KEY_SLM_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        return if (AVAILABLE_MODELS.any { it.id == saved }) saved else DEFAULT_MODEL
    }

    fun setSelectedModel(context: Context, modelId: String) =
        context.getSharedPreferences(PREF_SLM, Context.MODE_PRIVATE)
            .edit().putString(KEY_SLM_MODEL, modelId).apply()

    fun isThinkingEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_SLM, Context.MODE_PRIVATE)
            .getBoolean(KEY_SLM_THINKING, DEFAULT_THINKING_ENABLED)

    fun setThinkingEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREF_SLM, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SLM_THINKING, enabled).apply()

    fun getModelMetadata(modelId: String): ModelMetadata =
        AVAILABLE_MODELS.find { it.id == modelId } ?: AVAILABLE_MODELS.first()

    /**
     * Cycles to the next available model in the list.
     */
    fun getNextModel(currentModelId: String): ModelMetadata {
        val currentIndex = AVAILABLE_MODELS.indexOfFirst { it.id == currentModelId }
        val nextIndex = if (currentIndex != -1) (currentIndex + 1) % AVAILABLE_MODELS.size else 0
        return AVAILABLE_MODELS[nextIndex]
    }

    fun getModelFile(context: Context, modelId: String): File {
        val baseDir = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        val slmDir = File(baseDir, SLM_DIR)
        val meta = getModelMetadata(modelId)
        val primary = File(slmDir, meta.fileName)
        if (primary.exists() && primary.length() > 0L) return primary
        val alt = File(slmDir, meta.fileName.lowercase())
        if (alt.exists() && alt.length() > 0L) return alt
        return primary
    }

    fun isModelReady(context: Context, modelId: String): Boolean =
        isModelDownloaded(context, modelId)

    fun isModelDownloaded(context: Context, modelId: String): Boolean {
        val file = getModelFile(context, modelId)
        return file.exists() && file.length() > 0L
    }

    fun deleteModel(context: Context, modelId: String): Boolean {
        val file = getModelFile(context, modelId)
        return if (file.exists()) file.delete() else false
    }

    const val KEY_LOADED_SLM_MODEL = "loaded_slm_model"

    fun getLoadedModelId(context: Context): String? =
        context.getSharedPreferences(PREF_SLM, Context.MODE_PRIVATE)
            .getString(KEY_LOADED_SLM_MODEL, null)

    fun setLoadedModelId(context: Context, modelId: String?) {
        context.getSharedPreferences(PREF_SLM, Context.MODE_PRIVATE)
            .edit().apply {
                if (modelId != null) putString(KEY_LOADED_SLM_MODEL, modelId)
                else remove(KEY_LOADED_SLM_MODEL)
            }.apply()
    }

    fun getOutputMode(context: Context): String {
        val saved = context.getSharedPreferences(PREF_SLM, Context.MODE_PRIVATE)
            .getString(KEY_SLM_OUTPUT_MODE, DEFAULT_OUTPUT_MODE) ?: DEFAULT_OUTPUT_MODE
        return when (saved) {
            OUTPUT_MODE_FAST, OUTPUT_MODE_BULLET, OUTPUT_MODE_REFINE, OUTPUT_MODE_DEEP -> saved
            else -> DEFAULT_OUTPUT_MODE
        }
    }

    fun setOutputMode(context: Context, mode: String) =
        context.getSharedPreferences(PREF_SLM, Context.MODE_PRIVATE)
            .edit().putString(KEY_SLM_OUTPUT_MODE, mode).apply()

    fun getOutputModeLabel(mode: String): String = when (mode) {
        OUTPUT_MODE_FAST -> "⚡ 快答"
        OUTPUT_MODE_BULLET -> "📝 條列"
        OUTPUT_MODE_REFINE -> "💡 潤飾"
        OUTPUT_MODE_DEEP -> "🔍 深度"
        else -> "⚡ 快答"
    }

    /**
     * Builds the injected system prompt tailored for concise mobile answers,
     * enforcing negative constraints (no filler, no greetings) and few-shot grounding.
     */
    fun buildSystemPrompt(context: Context): String {
        val thinking = isThinkingEnabled(context)
        val mode = getOutputMode(context)
        return buildSystemPrompt(thinking, mode)
    }

    fun buildSystemPrompt(thinkingEnabled: Boolean, mode: String = DEFAULT_OUTPUT_MODE): String {
        val modeInstruction = when (mode) {
            OUTPUT_MODE_FAST -> """
                【模式：⚡ 極簡快答】
                - 第一句直接給出最終答案或核心結論，嚴禁任何客套話、引言或問候語。
                - 總長度嚴格控制在 100 字以內，不說多餘廢話，直接了當。
            """.trimIndent()
            OUTPUT_MODE_BULLET -> """
                【模式：📝 重點條列】
                - 必須使用 Markdown 條列式（- 重點內容），最多列出 3 點，每點不超過 25 字。
                - 禁止任何前言、引言或總結贅詞，第一行直接以「- 」開始輸出重點。
            """.trimIndent()
            OUTPUT_MODE_REFINE -> """
                【模式：💡 潤飾與翻譯】
                - 若為翻譯、錯字修正或潤飾任務：【僅輸出處理後的目標文字結果】。
                - 絕對禁止附加任何解說、音標、字詞分析、前後對比或思考過程。
            """.trimIndent()
            OUTPUT_MODE_DEEP -> """
                【模式：🔍 深度推論】
                - 著重邏輯縝密與多角度分析，結構條理分明。
                - 核心重點加粗，避免無意義口語廢話。
            """.trimIndent()
            else -> ""
        }

        val thinkRule = if (thinkingEnabled) {
            "【思考約束】：若需在 <think> 與 </think> 間思考，請嚴格限制在 30 字以內簡短分析，思考結束後立即輸出最終精確回答。"
        } else {
            "【禁止思考】：嚴禁輸出任何 <think> 標籤或思考草稿，直接輸出最終結果。"
        }

        return """
            你是由 Verbead 驅動的端側離線極速 AI 助理。請嚴格遵守以下輸出契約：
            1. 【開門見山】：嚴禁任何寒暄客套（如「您好」、「好的」、「很高興為您解答」）與引言（如「以下為您整理」）。
            2. 【杜絕贅詞】：嚴禁重複用戶問題，嚴禁結尾客套（如「希望對您有幫助」、「若有其他問題歡迎提問」）。
            3. $modeInstruction
            4. $thinkRule

            【少樣本示範 1：日常問答】
            用戶：明天出門要帶傘嗎？降雨機率 80%
            助手：需要。明日降雨機率達 80%，出門請務必攜帶雨具。

            【少樣本示範 2：翻譯與潤飾】
            用戶：今天中午吃水餃翻譯成英文
            助手：I ate dumplings for lunch today.

            【少樣本示範 3：條列總結】
            用戶：請總結多喝水的好處
            助手：- 促進新陳代謝，排出體內廢物
            - 維持體溫調節與血液循環
            - 滋潤肌膚並預防便秘
        """.trimIndent()
    }

    // ── GPU / NPU Hardware Acceleration ────────────────────────────────────────
    fun isHardwareAccelEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_SLM, Context.MODE_PRIVATE)
            .getBoolean(KEY_SLM_HARDWARE_ACCEL, DEFAULT_HARDWARE_ACCEL_ENABLED)

    fun setHardwareAccelEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREF_SLM, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SLM_HARDWARE_ACCEL, enabled).apply()

    fun getGpuLayers(context: Context): Int =
        if (isHardwareAccelEnabled(context)) DEFAULT_GPU_LAYERS else 0

    fun getHardwareBackend(context: Context): String =
        context.getSharedPreferences(PREF_SLM, Context.MODE_PRIVATE)
            .getString(KEY_SLM_BACKEND, BACKEND_AUTO) ?: BACKEND_AUTO

    fun setHardwareBackend(context: Context, backend: String) =
        context.getSharedPreferences(PREF_SLM, Context.MODE_PRIVATE)
            .edit().putString(KEY_SLM_BACKEND, backend).apply()

    fun getHardwareBackendName(context: Context): String =
        if (isHardwareAccelEnabled(context)) "GPU/NPU (Vulkan/NNAPI)" else "CPU"

    fun getHardwareAccelTag(context: Context): String =
        if (isHardwareAccelEnabled(context)) "⚡ GPU/NPU" else "CPU"

    fun getHardwareAccelDescription(context: Context): String =
        if (isHardwareAccelEnabled(context)) {
            "硬體加速已就緒：Qwen 對話 SLM 99 層運算全數卸載至 GPU (Vulkan) / NPU (NNAPI)"
        } else {
            "純 CPU 運算模式 (低能效 / 備用)"
        }

    data class SlmInferenceHardwareSpec(
        val isHardwareAccel: Boolean,
        val gpuLayers: Int,
        val backendName: String,
        val accelTag: String,
        val threads: Int = 4,
        val contextWindow: Int = 2048
    )

    fun getHardwareSpec(context: Context): SlmInferenceHardwareSpec =
        SlmInferenceHardwareSpec(
            isHardwareAccel = isHardwareAccelEnabled(context),
            gpuLayers = getGpuLayers(context),
            backendName = getHardwareBackendName(context),
            accelTag = getHardwareAccelTag(context)
        )
}
