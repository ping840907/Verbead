package com.ping.verbead.slm

import android.content.Context
import java.io.File

object SlmModelConfig {

    const val PREF_SLM = "slm_settings"
    const val KEY_SLM_MODEL = "selected_slm_model"
    const val KEY_SLM_THINKING = "slm_thinking_enabled"

    // 1. 最聰明 (Smartest / High reasoning): Qwen2.5-1.5B-Instruct
    const val MODEL_QWEN_1_5B = "qwen2.5_1.5b"
    // 2. 能效最高 (Highest Energy Efficiency & Speed): Qwen2.5-0.5B-Instruct
    const val MODEL_QWEN_0_5B = "qwen2.5_0.5b"

    const val DEFAULT_MODEL = MODEL_QWEN_1_5B
    const val DEFAULT_THINKING_ENABLED = true

    const val SLM_DIR = "slm"
    const val FILE_QWEN_1_5B_GGUF = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
    const val FILE_QWEN_0_5B_GGUF = "qwen2.5-0.5b-instruct-q4_k_m.gguf"

    data class ModelMetadata(
        val id: String,
        val displayName: String,
        val shortTag: String,
        val description: String,
        val parameterCount: String,
        val quantizedSize: String,
        val fileName: String,
        val downloadUrl: String
    )

    val AVAILABLE_MODELS = listOf(
        ModelMetadata(
            id = MODEL_QWEN_1_5B,
            displayName = "Qwen2.5-1.5B",
            shortTag = "聰明精準",
            description = "當前手機端側綜合智力最高之開源小模型，具備優秀邏輯推理、中文理解與精確摘要能力",
            parameterCount = "1.54B",
            quantizedSize = "約 940 MB (Q4_K_M)",
            fileName = FILE_QWEN_1_5B_GGUF,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"
        ),
        ModelMetadata(
            id = MODEL_QWEN_0_5B,
            displayName = "Qwen2.5-0.5B",
            shortTag = "極速高能效",
            description = "極致省電與毫秒級極速回應，記憶體開銷小於 500MB，適合即時潤飾、語句快答與筆記總結",
            parameterCount = "0.49B",
            quantizedSize = "約 350 MB (Q4_K_M)",
            fileName = FILE_QWEN_0_5B_GGUF,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
        )
    )

    fun getSelectedModel(context: Context): String =
        context.getSharedPreferences(PREF_SLM, Context.MODE_PRIVATE)
            .getString(KEY_SLM_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL

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

    fun getModelFile(context: Context, modelId: String): File {
        val baseDir = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        val slmDir = File(baseDir, SLM_DIR)
        val meta = getModelMetadata(modelId)
        return File(slmDir, meta.fileName)
    }

    fun isModelReady(context: Context, modelId: String): Boolean =
        getModelFile(context, modelId).exists()

    /**
     * Builds the injected system prompt tailored for concise mobile answers and thinking state.
     */
    fun buildSystemPrompt(thinkingEnabled: Boolean): String {
        return if (thinkingEnabled) {
            "你是由 Verbead 驅動的端側離線 AI 助手。請在 <think> 與 </think> 標籤之間進行簡要的核心邏輯推導，思考完成後請務必直接給出簡短、精準、扼要的最終答覆，避免冗長寒暄與無關贅詞。"
        } else {
            "你是由 Verbead 驅動的端側離線 AI 助手。請務必直接提供簡短、精準、扼要的最終回答，禁止輸出任何思考標籤、推理草稿或開場白，直接給出答案。"
        }
    }
}
