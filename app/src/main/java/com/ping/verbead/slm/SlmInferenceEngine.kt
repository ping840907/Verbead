package com.ping.verbead.slm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.codeshipping.llamakotlin.LlamaModel
import org.codeshipping.llamakotlin.exception.LlamaException

/**
 * Native inference engine for mobile on-device Small Language Models (SLM) powered by llama.cpp.
 * Fully loads Qwen3.5 GGUF weights into memory and offloads 99 layers to GPU/NPU (Vulkan/NNAPI),
 * with automatic CPU fallback.
 * Handles ChatML prompt assembly, real-time token streaming, <think> tag separation,
 * and concise answer delivery.
 */
class SlmInferenceEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "SlmInferenceEngine"

        @Volatile
        private var activeLlamaModel: LlamaModel? = null

        @Volatile
        var loadedModelId: String? = null
            private set

        @Volatile
        var loadedMemoryBytes: Long = 0L
            private set

        fun isModelLoaded(modelId: String): Boolean =
            loadedModelId == modelId && (activeLlamaModel != null)

        suspend fun loadModel(context: Context, modelId: String): Result<Unit> = withContext(Dispatchers.IO) {
            try {
                val file = SlmModelConfig.getModelFile(context, modelId)
                if (!file.exists() || file.length() == 0L) {
                    return@withContext Result.failure(java.io.FileNotFoundException("模型檔案尚未下載：${file.name}"))
                }
                if (loadedModelId == modelId && activeLlamaModel?.isLoaded == true) {
                    return@withContext Result.success(Unit)
                }

                unloadModel(context)

                val hwSpec = SlmModelConfig.getHardwareSpec(context)
                val availableCores = Runtime.getRuntime().availableProcessors()
                val threadsCount = (availableCores - 2).coerceIn(2, 6)

                Log.i(TAG, "Loading native SLM model $modelId into memory (${file.absolutePath}, ${file.length()} bytes) on ${hwSpec.backendName} with ${hwSpec.gpuLayers} GPU layers, $threadsCount threads...")

                // Attempt GPU layers loading first; if it fails (e.g. Vulkan initialization error), fallback to CPU
                val model = try {
                    LlamaModel.load(file.absolutePath) {
                        gpuLayers = hwSpec.gpuLayers
                        contextSize = 2048
                        batchSize = 512
                        threads = threadsCount
                        threadsBatch = threadsCount
                        useMmap = true
                        useMlock = false
                        temperature = 0.25f
                        topP = 0.7f
                        topK = 20
                        repeatPenalty = 1.18f
                        presencePenalty = 0.25f
                        maxTokens = 256
                        stopSequences = listOf("<|im_end|>", "<|endoftext|>", "</s>", "\n\n用戶：", "\n\nUser:", "\n\n使用者：", "\n\nQ:")
                    }
                } catch (gpuErr: Throwable) {
                    Log.w(TAG, "GPU/NPU loading failed, falling back to CPU: ${gpuErr.message}", gpuErr)
                    LlamaModel.load(file.absolutePath) {
                        gpuLayers = 0
                        contextSize = 2048
                        batchSize = 512
                        threads = threadsCount
                        threadsBatch = threadsCount
                        useMmap = true
                        useMlock = false
                        temperature = 0.25f
                        topP = 0.7f
                        topK = 20
                        repeatPenalty = 1.18f
                        presencePenalty = 0.25f
                        maxTokens = 256
                        stopSequences = listOf("<|im_end|>", "<|endoftext|>", "</s>", "\n\n用戶：", "\n\nUser:", "\n\n使用者：", "\n\nQ:")
                    }
                }

                activeLlamaModel = model
                loadedModelId = modelId
                loadedMemoryBytes = file.length()
                SlmModelConfig.setLoadedModelId(context, modelId)
                Log.i(TAG, "SLM model $modelId loaded successfully into native engine.")
                Result.success(Unit)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load SLM model $modelId into memory", e)
                unloadModel(context)
                Result.failure(e)
            }
        }

        fun unloadModel(context: Context? = null) {
            try {
                activeLlamaModel?.cancelGeneration()
                activeLlamaModel?.close()
            } catch (e: Throwable) {
                Log.w(TAG, "Error closing active LlamaModel", e)
            } finally {
                activeLlamaModel = null
                val prev = loadedModelId
                loadedModelId = null
                loadedMemoryBytes = 0L
                if (context != null) {
                    SlmModelConfig.setLoadedModelId(context, null)
                }
                System.gc()
                Log.i(TAG, "Unloaded SLM model $prev from memory")
            }
        }

        fun getLoadedMemoryInfo(): String {
            return if (loadedModelId != null) {
                val mb = loadedMemoryBytes / (1024 * 1024)
                if (mb > 1024) "已載入記憶體 (%.1f GB)".format(mb / 1024.0) else "已載入記憶體 (${mb} MB)"
            } else {
                "未載入記憶體"
            }
        }

        /**
         * Cleans trailing stop tokens or partial control tags from output text.
         */
        fun cleanOutput(text: String): String {
            var cleaned = text.replace("<|im_end|>", "")
                .replace("<|endoftext|>", "")
                .replace("<|im_start|>", "")
                .replace("</s>", "")

            // Strip trailing incomplete stop tags while streaming (e.g. "<|im_")
            val lastTagIdx = cleaned.lastIndexOf("<|")
            if (lastTagIdx != -1 && !cleaned.substring(lastTagIdx).contains(">")) {
                cleaned = cleaned.substring(0, lastTagIdx)
            }
            return cleaned
        }

        /**
         * Parses a raw text stream into separated (thinkingContent, answerContent).
         * If thinking is disabled, thinkingContent is suppressed.
         */
        fun parseStreamContent(raw: String, thinkingEnabled: Boolean = true): Pair<String?, String> {
            val trimmedLeading = raw.trimStart()
            // Avoid leaking partial opening tag like "<th" or "<think" into answer
            if (trimmedLeading.startsWith("<") && !trimmedLeading.contains(">")) {
                return Pair(null, "")
            }
            val thinkStartIdx = raw.indexOf("<think>")
            if (thinkStartIdx != -1) {
                val afterStart = raw.substring(thinkStartIdx + "<think>".length)
                val thinkEndIdx = afterStart.indexOf("</think>")
                return if (thinkEndIdx != -1) {
                    val thinking = afterStart.substring(0, thinkEndIdx).trim()
                    val answer = afterStart.substring(thinkEndIdx + "</think>".length).trimStart()
                    Pair(if (thinkingEnabled) thinking else null, answer)
                } else {
                    // Still inside <think> tag
                    if (thinkingEnabled) Pair(afterStart.trim(), "") else Pair(null, "")
                }
            }
            return Pair(null, raw)
        }

        /**
         * Formats conversation turns into standard ChatML format used by Qwen models.
         * Prunes multi-turn history to recent turns and strictly excludes <think> tags
         * to prevent context window explosion and in-context conversational rambling.
         */
        fun buildChatMlPrompt(
            systemPrompt: String,
            history: List<SlmChatMessage>,
            newPrompt: String,
            mode: String = SlmModelConfig.OUTPUT_MODE_FAST
        ): String {
            val sb = StringBuilder()
            sb.append("<|im_start|>system\n").append(systemPrompt).append("<|im_end|>\n")

            // Prune history: keep only last 6 messages (3 conversation turns) to avoid context exhaustion
            val prunedHistory = if (history.size > 6) history.takeLast(6) else history
            for (msg in prunedHistory) {
                val roleStr = when (msg.role) {
                    SlmChatMessage.Role.USER -> "user"
                    SlmChatMessage.Role.ASSISTANT -> "assistant"
                    SlmChatMessage.Role.SYSTEM -> "system"
                }
                sb.append("<|im_start|>").append(roleStr).append("\n")
                // CRITICAL: NEVER include <think>...</think> in multi-turn history!
                // This prevents in-context mimicry of verbose internal monologues and stops context explosion.
                val cleanContent = msg.getCleanAnswer().ifBlank { msg.content }
                sb.append(cleanContent).append("<|im_end|>\n")
            }
            sb.append("<|im_start|>user\n").append(newPrompt).append("<|im_end|>\n")
            sb.append("<|im_start|>assistant\n")
            return sb.toString()
        }
    }

    interface StreamCallback {
        fun onThinkingUpdate(thinking: String)
        fun onAnswerUpdate(answer: String)
        fun onComplete(fullMessage: SlmChatMessage)
        fun onError(error: Throwable)
    }

    private var activeJob: Job? = null

    fun isLoaded(modelId: String? = null): Boolean {
        val target = modelId ?: SlmModelConfig.getSelectedModel(context)
        return isModelLoaded(target)
    }

    suspend fun loadModel(modelId: String = SlmModelConfig.getSelectedModel(context)): Result<Unit> =
        loadModel(context, modelId)

    fun unloadModel() = unloadModel(context)

    fun cancelCurrent() {
        try {
            activeLlamaModel?.cancelGeneration()
        } catch (e: Throwable) {
            Log.w(TAG, "Error cancelling native generation", e)
        }
        activeJob?.cancel()
        activeJob = null
    }

    /**
     * Executes generation for a user prompt with streaming feedback.
     */
    fun generate(
        userPrompt: String,
        history: List<SlmChatMessage>,
        callback: StreamCallback
    ) {
        cancelCurrent()

        val selectedModel = SlmModelConfig.getSelectedModel(context)
        val thinkingEnabled = SlmModelConfig.isThinkingEnabled(context)
        val outputMode = SlmModelConfig.getOutputMode(context)
        val meta = SlmModelConfig.getModelMetadata(selectedModel)
        val hwSpec = SlmModelConfig.getHardwareSpec(context)
        val systemPrompt = SlmModelConfig.buildSystemPrompt(thinkingEnabled, outputMode)
        val modeLabel = SlmModelConfig.getOutputModeLabel(outputMode)

        val assistantMessage = SlmChatMessage(
            role = SlmChatMessage.Role.ASSISTANT,
            content = "",
            thinkingContent = null,
            isStreaming = true,
            modelTag = "${meta.displayName} · $modeLabel · ${hwSpec.accelTag}"
        )

        activeJob = scope.launch {
            try {
                // Ensure model is downloaded and loaded into memory
                val isDownloaded = SlmModelConfig.isModelDownloaded(context, selectedModel)
                if (!isDownloaded) {
                    withContext(Dispatchers.Main) {
                        assistantMessage.isStreaming = false
                        assistantMessage.content = "模型「${meta.displayName}」尚未下載。請至設定或點擊頂部模型按鈕進行下載。"
                        callback.onError(IllegalStateException("模型尚未下載"))
                    }
                    return@launch
                }

                if (!isLoaded(selectedModel)) {
                    val loadRes = loadModel(selectedModel)
                    if (loadRes.isFailure) {
                        val err = loadRes.exceptionOrNull() ?: IllegalStateException("模型載入失敗")
                        withContext(Dispatchers.Main) {
                            assistantMessage.isStreaming = false
                            assistantMessage.content = "模型載入至記憶體失敗：${err.message}"
                            callback.onError(err)
                        }
                        return@launch
                    }
                }

                val model = activeLlamaModel ?: throw IllegalStateException("推論模型未就緒")

                val chatMlPrompt = buildChatMlPrompt(
                    systemPrompt = systemPrompt,
                    history = history,
                    newPrompt = userPrompt,
                    mode = outputMode
                )

                Log.i(TAG, "Starting native SLM inference with prompt (${chatMlPrompt.length} chars) on ${hwSpec.backendName} [mode=$outputMode, thinking=$thinkingEnabled]")

                streamInference(
                    model = model,
                    chatMlPrompt = chatMlPrompt,
                    assistantMessage = assistantMessage,
                    callback = callback,
                    thinkingEnabled = thinkingEnabled,
                    outputMode = outputMode
                )
            } catch (e: Throwable) {
                if (e !is CancellationException && e !is LlamaException.GenerationCancelled) {
                    Log.e(TAG, "Inference error", e)
                    withContext(Dispatchers.Main) {
                        assistantMessage.isStreaming = false
                        callback.onError(e)
                    }
                }
            }
        }
    }

    fun getHardwareConfig(): SlmModelConfig.SlmInferenceHardwareSpec =
        SlmModelConfig.getHardwareSpec(context)

    private suspend fun streamInference(
        model: LlamaModel,
        chatMlPrompt: String,
        assistantMessage: SlmChatMessage,
        callback: StreamCallback,
        thinkingEnabled: Boolean,
        outputMode: String
    ) = withContext(Dispatchers.Default) {
        val rawBuffer = StringBuilder()

        val genConfig = model.config.copy {
            maxTokens = when (outputMode) {
                SlmModelConfig.OUTPUT_MODE_DEEP -> 512
                SlmModelConfig.OUTPUT_MODE_BULLET -> 320
                else -> 256
            }
            temperature = when (outputMode) {
                SlmModelConfig.OUTPUT_MODE_DEEP -> 0.35f
                SlmModelConfig.OUTPUT_MODE_REFINE -> 0.15f
                else -> 0.25f
            }
            repeatPenalty = 1.18f
            presencePenalty = 0.25f
        }

        val flow = model.generateStream(chatMlPrompt, genConfig)
        flow.collect { token ->
            rawBuffer.append(token)

            val (thinking, answer) = parseStreamContent(rawBuffer.toString(), thinkingEnabled)
            val cleanedAnswer = cleanOutput(answer)
            assistantMessage.thinkingContent = if (thinkingEnabled) thinking?.let { cleanOutput(it) } else null
            assistantMessage.content = cleanedAnswer

            withContext(Dispatchers.Main) {
                if (thinking != null && thinkingEnabled) {
                    callback.onThinkingUpdate(cleanOutput(thinking))
                }
                if (cleanedAnswer.isNotEmpty()) {
                    callback.onAnswerUpdate(cleanedAnswer)
                }
            }
        }

        // Finalize completed output
        val (finalThinking, finalAnswer) = parseStreamContent(rawBuffer.toString(), thinkingEnabled)
        val cleanFinalAnswer = cleanOutput(finalAnswer).trim()
        assistantMessage.thinkingContent = if (thinkingEnabled) finalThinking?.let { cleanOutput(it).trim() } else null
        assistantMessage.content = cleanFinalAnswer
        assistantMessage.isStreaming = false

        withContext(Dispatchers.Main) {
            callback.onComplete(assistantMessage)
        }
    }
}
