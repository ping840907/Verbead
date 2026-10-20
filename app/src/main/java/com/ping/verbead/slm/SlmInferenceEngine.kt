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

        private val XML_CONTROL_TAG_REGEX = Regex(
            "</?(?:source_text|voice_command|voice_input|output|result|target_text)>",
            RegexOption.IGNORE_CASE
        )

        private val META_PREFIX_REGEX = Regex(
            "^(?:(?:好的|沒問題|了解|當然|收到)[，,。！!、\\s]*)*(?:(?:修改後(?:的文字)?|改寫後(?:的文字)?|潤飾後(?:的文字)?|翻譯結果|翻譯後|處理後(?:的文字)?|轉換後|整合後|目標文字|輸出結果|最終結果|最終文字|結果|回覆|答案|譯文|這裡是[^\\n:：]{0,12}|以下為[^\\n:：]{0,12}|以下是[^\\n:：]{0,12}|Translated(?:\\s+text)?|Rewritten(?:\\s+text)?|Output|Result)(?:如下)?\\s*[:：]\\s*)+",
            RegexOption.IGNORE_CASE
        )

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
         * Strictly sanitizes SLM output for direct text-box injection:
         * 1. Strips special tokens (`<|im_end|>`, etc.) and leaked XML prompt tags (`<source_text>`, `<output>`, etc.).
         * 2. Strips conversational prefixes or labels (`好的，修改後：`, `翻譯結果：`, `Output:`, etc.).
         * 3. Strips unwanted outer wrapping quotes if the original source text was not quoted.
         */
        fun sanitizeInputTaskOutput(rawAnswer: String, sourceText: String = ""): String {
            var cleaned = cleanOutput(rawAnswer)
            cleaned = XML_CONTROL_TAG_REGEX.replace(cleaned, "")

            // Strip trailing incomplete XML tags during streaming (e.g. "</source_t")
            val lastOpenAngle = cleaned.lastIndexOf('<')
            if (lastOpenAngle != -1 && !cleaned.substring(lastOpenAngle).contains('>')) {
                val tail = cleaned.substring(lastOpenAngle)
                if (tail.startsWith("</") || tail.startsWith("<source") || tail.startsWith("<voice") || tail.startsWith("<output") || tail.startsWith("<result")) {
                    cleaned = cleaned.substring(0, lastOpenAngle)
                }
            }

            cleaned = cleaned.trim()
            cleaned = META_PREFIX_REGEX.replace(cleaned, "").trim()

            // Strip outer wrapping quotes if sourceText did not have them
            val trimmedSource = sourceText.trim()
            if (cleaned.length >= 2) {
                val quotePairs = listOf(
                    '「' to '」',
                    '『' to '』',
                    '"' to '"',
                    '“' to '”'
                )
                for ((openQ, closeQ) in quotePairs) {
                    val sourceHadQuotes = trimmedSource.startsWith(openQ) && trimmedSource.endsWith(closeQ)
                    if (!sourceHadQuotes && cleaned.startsWith(openQ) && cleaned.endsWith(closeQ)) {
                        val inner = cleaned.substring(1, cleaned.length - 1)
                        // Only unwrap if there isn't an unclosed internal pair (e.g. 「A」與「B」)
                        if (!inner.contains(closeQ)) {
                            cleaned = inner.trim()
                            break
                        }
                    }
                }
            }

            return cleaned
        }

        /**
         * Parses a raw text stream into separated (thinkingContent, answerContent).
         * Supports prefilled <think>\n prompts, model-generated <think> tags,
         * <thought> aliases, orphan </think> tags, and streaming unclosed thinking tokens.
         *
         * @param raw The raw accumulated token stream emitted by the model.
         * @param thinkingEnabled Whether thinking mode is turned on.
         * @param isPrefilledThink Whether <think>\n was prefilled at the end of the assistant prompt.
         */
        fun parseStreamContent(
            raw: String,
            thinkingEnabled: Boolean = true,
            isPrefilledThink: Boolean = false
        ): Pair<String?, String> {
            val normalized = raw.replace("<thought>", "<think>").replace("</thought>", "</think>")
            val trimmedLeading = normalized.trimStart()

            // 1. Explicit <think> tag in the raw generated stream
            val thinkStartIdx = normalized.indexOf("<think>")
            if (thinkStartIdx != -1) {
                val afterStart = normalized.substring(thinkStartIdx + "<think>".length)
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

            // 2. Orphan </think> tag (occurs when <think>\n was prefilled in the prompt)
            val orphanThinkEndIdx = normalized.indexOf("</think>")
            if (orphanThinkEndIdx != -1) {
                val thinking = normalized.substring(0, orphanThinkEndIdx).trim()
                val answer = normalized.substring(orphanThinkEndIdx + "</think>".length).trimStart()
                return Pair(if (thinkingEnabled) thinking else null, answer)
            }

            // 3. Neither <think> nor </think> present in the generated stream
            if (isPrefilledThink && thinkingEnabled) {
                // Since <think>\n was prefilled in the prompt, every incoming token until </think> is THINKING
                return Pair(normalized.trim(), "")
            }

            // Avoid leaking partial opening tag like "<th" or "<think" into answer
            if (trimmedLeading.startsWith("<") && !trimmedLeading.contains(">")) {
                return Pair(null, "")
            }

            return Pair(null, normalized)
        }

        /**
         * Fallback extractor in case a model in thinking mode terminated or was cut off
         * without closing </think>. Salvages the final answer from concluding reasoning markers.
         */
        fun extractFallbackAnswerFromThinking(thinking: String): String {
            if (thinking.isBlank()) return ""
            val markers = listOf(
                "最終輸出[：:\\s]+",
                "最終結果[：:\\s]+",
                "最終文字[：:\\s]+",
                "建議改寫為[：:\\s]+",
                "改寫為[：:\\s]+",
                "修改為[：:\\s]+",
                "修改後[：:\\s]+",
                "潤飾後[：:\\s]+",
                "翻譯結果[：:\\s]+",
                "譯文[：:\\s]+",
                "輸出[：:\\s]+"
            )
            for (pattern in markers) {
                val match = Regex(pattern, RegexOption.IGNORE_CASE).find(thinking)
                if (match != null) {
                    val candidate = thinking.substring(match.range.last + 1).trim()
                    if (candidate.isNotBlank()) {
                        return candidate
                    }
                }
            }
            val lines = thinking.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size >= 2) {
                val lastLine = lines.last()
                if (!lastLine.startsWith("分析") && !lastLine.startsWith("思考") && !lastLine.startsWith("首先") && !lastLine.startsWith("1.") && !lastLine.startsWith("-")) {
                    return lastLine
                }
            }
            return ""
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
            mode: String = SlmModelConfig.OUTPUT_MODE_FAST,
            thinkingEnabled: Boolean = false
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
                val cleanContent = msg.getCleanAnswer().ifBlank { msg.content }
                sb.append(cleanContent).append("<|im_end|>\n")
            }
            sb.append("<|im_start|>user\n").append(newPrompt).append("<|im_end|>\n")
            sb.append("<|im_start|>assistant\n")
            if (thinkingEnabled) {
                sb.append("<think>\n")
            }
            return sb.toString()
        }

        /**
         * Builds a stateless, single-turn ChatML prompt specifically for IME voice-driven text processing.
         * When [thinkingEnabled] is true, prefills `<think>\n` so the model seamlessly outputs its thinking
         * tokens and closes with `</think>\n` before providing the final text.
         * When [thinkingEnabled] is false, prefills an empty closed `<think>\n\n</think>\n` block
         * so the model immediately skips reasoning and directly outputs the text.
         */
        fun buildInputTaskChatMlPrompt(
            targetText: String?,
            voiceInput: String,
            thinkingEnabled: Boolean = false,
            mode: String = SlmModelConfig.OUTPUT_MODE_REFINE
        ): String {
            val hasTarget = !targetText.isNullOrBlank()
            val systemPrompt = SlmModelConfig.buildInputTaskSystemPrompt(
                hasTargetText = hasTarget,
                thinkingEnabled = thinkingEnabled,
                mode = mode
            )
            val userPrompt = SlmModelConfig.buildInputTaskUserPrompt(
                targetText = targetText,
                voiceInput = voiceInput,
                thinkingEnabled = thinkingEnabled
            )
            val sb = StringBuilder()
            sb.append("<|im_start|>system\n").append(systemPrompt).append("<|im_end|>\n")
            sb.append("<|im_start|>user\n").append(userPrompt).append("<|im_end|>\n")
            sb.append("<|im_start|>assistant\n")
            if (thinkingEnabled) {
                sb.append("<think>\n")
            } else {
                sb.append("<think>\n\n</think>\n")
            }
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
     * Executes stateless, context-aware text transformation or generation for the AI floating bubble.
     * - [targetText]: Current text extracted from the focused input box (or empty if the input box is empty).
     * - [voiceInput]: User's spoken instruction or content recognized by ASR.
     */
    fun processInputTask(
        targetText: String?,
        voiceInput: String,
        callback: StreamCallback
    ) {
        cancelCurrent()

        val selectedModel = SlmModelConfig.resolveAvailableModelId(context)
            ?: SlmModelConfig.getSelectedModel(context)
        val thinkingEnabled = SlmModelConfig.isThinkingEnabled(context)
        val outputMode = SlmModelConfig.getOutputMode(context)
        val meta = SlmModelConfig.getModelMetadata(selectedModel)
        val hwSpec = SlmModelConfig.getHardwareSpec(context)
        val modeLabel = SlmModelConfig.getOutputModeLabel(outputMode)
        val cleanSource = targetText?.trim() ?: ""

        val assistantMessage = SlmChatMessage(
            role = SlmChatMessage.Role.ASSISTANT,
            content = "",
            thinkingContent = null,
            isStreaming = true,
            modelTag = "${meta.displayName} · $modeLabel · ${hwSpec.accelTag}"
        )

        activeJob = scope.launch {
            try {
                val isDownloaded = SlmModelConfig.isModelDownloaded(context, selectedModel)
                if (!isDownloaded) {
                    withContext(Dispatchers.Main) {
                        assistantMessage.isStreaming = false
                        assistantMessage.content = "模型「${meta.displayName}」尚未下載。"
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
                val chatMlPrompt = buildInputTaskChatMlPrompt(
                    targetText = cleanSource,
                    voiceInput = voiceInput,
                    thinkingEnabled = thinkingEnabled,
                    mode = outputMode
                )

                Log.i(TAG, "Starting SLM input task inference [hasTarget=${cleanSource.isNotEmpty()}, mode=$outputMode, thinking=$thinkingEnabled] on ${hwSpec.backendName}")

                streamInference(
                    model = model,
                    chatMlPrompt = chatMlPrompt,
                    assistantMessage = assistantMessage,
                    callback = callback,
                    thinkingEnabled = thinkingEnabled,
                    outputMode = if (cleanSource.isNotEmpty()) SlmModelConfig.OUTPUT_MODE_REFINE else outputMode,
                    isInputTask = true,
                    sourceText = cleanSource
                )
            } catch (e: Throwable) {
                if (e !is CancellationException && e !is LlamaException.GenerationCancelled) {
                    Log.e(TAG, "Input task inference error", e)
                    withContext(Dispatchers.Main) {
                        assistantMessage.isStreaming = false
                        callback.onError(e)
                    }
                }
            }
        }
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
                    mode = outputMode,
                    thinkingEnabled = thinkingEnabled
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

    private suspend fun streamInference(
        model: LlamaModel,
        chatMlPrompt: String,
        assistantMessage: SlmChatMessage,
        callback: StreamCallback,
        thinkingEnabled: Boolean,
        outputMode: String,
        isInputTask: Boolean = false,
        sourceText: String = ""
    ) = withContext(Dispatchers.Default) {
        val rawBuffer = StringBuilder()

        val genConfig = model.config.copy {
            maxTokens = when {
                thinkingEnabled -> {
                    // Thinking mode requires sufficient token budget for reasoning chain + target answer
                    if (isInputTask && sourceText.isNotEmpty()) {
                        (sourceText.length * 3 + 512).coerceIn(768, 1024)
                    } else {
                        768
                    }
                }
                isInputTask && sourceText.isNotEmpty() -> (sourceText.length * 3).coerceIn(256, 512)
                outputMode == SlmModelConfig.OUTPUT_MODE_DEEP -> 512
                outputMode == SlmModelConfig.OUTPUT_MODE_BULLET -> 320
                else -> 256
            }
            temperature = when {
                isInputTask -> 0.15f
                outputMode == SlmModelConfig.OUTPUT_MODE_DEEP -> 0.35f
                outputMode == SlmModelConfig.OUTPUT_MODE_REFINE -> 0.15f
                else -> 0.25f
            }
            repeatPenalty = 1.18f
            presencePenalty = if (isInputTask) 0.10f else 0.25f
            stopSequences = listOf(
                "<|im_end|>",
                "<|endoftext|>",
                "</s>",
                "</source_text>",
                "</voice_command>",
                "</voice_input>",
                "</output>",
                "\n\n用戶：",
                "\n\nUser:",
                "\n\n使用者：",
                "\n\nQ:",
                "\n\n【"
            )
        }

        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var lastShownThinking = ""
        var lastShownAnswer = ""

        val flow = model.generateStream(chatMlPrompt, genConfig)
        flow.collect { token ->
            rawBuffer.append(token)

            val (thinking, answer) = parseStreamContent(
                raw = rawBuffer.toString(),
                thinkingEnabled = thinkingEnabled,
                isPrefilledThink = thinkingEnabled
            )
            val cleanedThinking = if (thinkingEnabled) thinking?.let { cleanOutput(it) } else null
            val cleanedAnswer = if (isInputTask) {
                sanitizeInputTaskOutput(answer, sourceText)
            } else {
                cleanOutput(answer)
            }
            assistantMessage.thinkingContent = cleanedThinking
            assistantMessage.content = cleanedAnswer

            if (cleanedThinking != null && cleanedThinking.isNotBlank() && cleanedThinking != lastShownThinking) {
                lastShownThinking = cleanedThinking
                mainHandler.post {
                    callback.onThinkingUpdate(cleanedThinking)
                }
            }
            if (cleanedAnswer.isNotBlank() && cleanedAnswer != lastShownAnswer) {
                lastShownAnswer = cleanedAnswer
                mainHandler.post {
                    callback.onAnswerUpdate(cleanedAnswer)
                }
            }
        }

        // Finalize completed output
        val (finalThinking, finalAnswer) = parseStreamContent(
            raw = rawBuffer.toString(),
            thinkingEnabled = thinkingEnabled,
            isPrefilledThink = thinkingEnabled
        )

        var resolvedAnswer = finalAnswer
        // Fallback recovery if model generated thinking but concluded or was cut off without </think>
        if (resolvedAnswer.isBlank() && thinkingEnabled && !finalThinking.isNullOrBlank()) {
            val salvaged = extractFallbackAnswerFromThinking(finalThinking)
            if (salvaged.isNotBlank()) {
                Log.w(TAG, "Model finished without closing </think>, salvaged answer from thinking: $salvaged")
                resolvedAnswer = salvaged
            }
        }

        val cleanFinalAnswer = if (isInputTask) {
            sanitizeInputTaskOutput(resolvedAnswer, sourceText)
        } else {
            cleanOutput(resolvedAnswer).trim()
        }
        assistantMessage.thinkingContent = if (thinkingEnabled) finalThinking?.let { cleanOutput(it).trim() } else null
        assistantMessage.content = cleanFinalAnswer
        assistantMessage.isStreaming = false

        mainHandler.post {
            callback.onComplete(assistantMessage)
        }
    }
}
