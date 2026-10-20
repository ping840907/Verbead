package com.ping.verbead.slm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlmInferenceEngineTest {

    @Test
    fun testParseStreamContentWithCompletedThinking() {
        val raw = "<think>\n分析用戶需求...\n重點簡潔扼要。\n</think>這是最終的扼要答覆。"
        val (thinking, answer) = SlmInferenceEngine.parseStreamContent(raw)
        assertEquals("分析用戶需求...\n重點簡潔扼要。", thinking)
        assertEquals("這是最終的扼要答覆。", answer)
    }

    @Test
    fun testParseStreamContentWhileThinking() {
        val raw = "<think>\n正在推導中尚未結束"
        val (thinking, answer) = SlmInferenceEngine.parseStreamContent(raw)
        assertEquals("正在推導中尚未結束", thinking)
        assertEquals("", answer)
    }

    @Test
    fun testParseStreamContentWithoutThinking() {
        val raw = "直接給出的簡潔回答，沒有思考標籤。"
        val (thinking, answer) = SlmInferenceEngine.parseStreamContent(raw)
        assertNull(thinking)
        assertEquals("直接給出的簡潔回答，沒有思考標籤。", answer)
    }

    @Test
    fun testCleanOutputStripsSpecialTokens() {
        val raw = "你好！這是一段回覆。<|im_end|>"
        assertEquals("你好！這是一段回覆。", SlmInferenceEngine.cleanOutput(raw))

        val streamingIncomplete = "正在生成文字<|im_"
        assertEquals("正在生成文字", SlmInferenceEngine.cleanOutput(streamingIncomplete))
    }

    @Test
    fun testSlmChatMessageCleanAnswerExtraction() {
        val msgWithThink = SlmChatMessage(
            role = SlmChatMessage.Role.ASSISTANT,
            content = "<think>思考推導草稿</think>乾淨的回覆內容"
        )
        assertEquals("乾淨的回覆內容", msgWithThink.getCleanAnswer())

        val msgClean = SlmChatMessage(
            role = SlmChatMessage.Role.ASSISTANT,
            content = "  純粹的答案  "
        )
        assertEquals("純粹的答案", msgClean.getCleanAnswer())
    }

    @Test
    fun testChatMlPromptAssemblyExcludesThinkingFromHistory() {
        val systemPrompt = "你是端側 AI 助手。"
        val history = listOf(
            SlmChatMessage(role = SlmChatMessage.Role.USER, content = "你好"),
            SlmChatMessage(role = SlmChatMessage.Role.ASSISTANT, content = "您好！有什麼我可以幫忙？", thinkingContent = "分析問候草稿")
        )
        val prompt = SlmInferenceEngine.buildChatMlPrompt(systemPrompt, history, "幫我寫信")

        assertTrue(prompt.contains("<|im_start|>system\n你是端側 AI 助手。<|im_end|>"))
        assertTrue(prompt.contains("<|im_start|>user\n你好<|im_end|>"))
        // CRITICAL: History MUST NOT contain <think> to prevent context window explosion
        assertFalse(prompt.contains("<think>分析問候草稿</think>"))
        assertTrue(prompt.contains("您好！有什麼我可以幫忙？<|im_end|>"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun testParseStreamContentSuppressThinkingWhenDisabled() {
        val raw = "<think>\n內部推導不應外洩\n</think>最終回答。"
        val (thinking, answer) = SlmInferenceEngine.parseStreamContent(raw, thinkingEnabled = false)
        assertNull(thinking)
        assertEquals("最終回答。", answer)
    }

    @Test
    fun testSlmOutputModesInSystemPrompt() {
        val fastPrompt = SlmModelConfig.buildSystemPrompt(thinkingEnabled = false, mode = SlmModelConfig.OUTPUT_MODE_FAST)
        assertTrue(fastPrompt.contains("極簡快答"))
        assertTrue(fastPrompt.contains("開門見山"))
        assertTrue(fastPrompt.contains("禁止思考"))

        val bulletPrompt = SlmModelConfig.buildSystemPrompt(thinkingEnabled = true, mode = SlmModelConfig.OUTPUT_MODE_BULLET)
        assertTrue(bulletPrompt.contains("重點條列"))
        assertTrue(bulletPrompt.contains("Markdown"))
        assertTrue(bulletPrompt.contains("<think>"))

        val refinePrompt = SlmModelConfig.buildSystemPrompt(thinkingEnabled = false, mode = SlmModelConfig.OUTPUT_MODE_REFINE)
        assertTrue(refinePrompt.contains("潤飾與翻譯"))
        assertTrue(refinePrompt.contains("僅輸出處理後的目標文字結果"))
    }

    @Test
    fun testSlmFourQwen35ModelsConfigAndCycling() {
        val promptThinkingOn = SlmModelConfig.buildSystemPrompt(thinkingEnabled = true)
        assertTrue(promptThinkingOn.contains("<think>"))
        assertTrue(promptThinkingOn.contains("簡短"))

        val promptThinkingOff = SlmModelConfig.buildSystemPrompt(thinkingEnabled = false)
        assertTrue(promptThinkingOff.contains("嚴禁輸出任何 <think> 標籤") || promptThinkingOff.contains("直接輸出最終結果"))

        // Verify exactly 4 models
        assertEquals(4, SlmModelConfig.AVAILABLE_MODELS.size)

        // 1. 0.8B
        val model08B = SlmModelConfig.getModelMetadata(SlmModelConfig.MODEL_QWEN_0_8B)
        assertNotNull(model08B)
        assertEquals("Qwen3.5-0.8B", model08B.displayName)
        assertEquals("0.8B 能效", model08B.chipLabel)
        assertTrue(model08B.fileName.endsWith(".gguf"))

        // 2. 2B
        val model2B = SlmModelConfig.getModelMetadata(SlmModelConfig.MODEL_QWEN_2B)
        assertNotNull(model2B)
        assertEquals("Qwen3.5-2B", model2B.displayName)
        assertEquals("2B 平衡", model2B.chipLabel)
        assertTrue(model2B.fileName.endsWith(".gguf"))

        // 3. 4B
        val model4B = SlmModelConfig.getModelMetadata(SlmModelConfig.MODEL_QWEN_4B)
        assertNotNull(model4B)
        assertEquals("Qwen3.5-4B", model4B.displayName)
        assertEquals("4B 聰明", model4B.chipLabel)
        assertTrue(model4B.fileName.endsWith(".gguf"))

        // 4. 9B
        val model9B = SlmModelConfig.getModelMetadata(SlmModelConfig.MODEL_QWEN_9B)
        assertNotNull(model9B)
        assertEquals("Qwen3.5-9B", model9B.displayName)
        assertEquals("9B 旗艦", model9B.chipLabel)
        assertTrue(model9B.fileName.endsWith(".gguf"))

        // Verify cycling: 0.8B -> 2B -> 4B -> 9B -> 0.8B
        assertEquals(SlmModelConfig.MODEL_QWEN_2B, SlmModelConfig.getNextModel(SlmModelConfig.MODEL_QWEN_0_8B).id)
        assertEquals(SlmModelConfig.MODEL_QWEN_4B, SlmModelConfig.getNextModel(SlmModelConfig.MODEL_QWEN_2B).id)
        assertEquals(SlmModelConfig.MODEL_QWEN_9B, SlmModelConfig.getNextModel(SlmModelConfig.MODEL_QWEN_4B).id)
        assertEquals(SlmModelConfig.MODEL_QWEN_0_8B, SlmModelConfig.getNextModel(SlmModelConfig.MODEL_QWEN_9B).id)
    }

    @Test
    fun testHardwareAccelerationDefaultsAndOffload() {
        assertTrue(SlmModelConfig.DEFAULT_HARDWARE_ACCEL_ENABLED)
        assertEquals(99, SlmModelConfig.DEFAULT_GPU_LAYERS)

        val spec = SlmModelConfig.SlmInferenceHardwareSpec(
            isHardwareAccel = true,
            gpuLayers = SlmModelConfig.DEFAULT_GPU_LAYERS,
            backendName = "GPU/NPU (Vulkan/NNAPI)",
            accelTag = "GPU/NPU"
        )
        assertTrue(spec.isHardwareAccel)
        assertEquals(99, spec.gpuLayers)
        assertEquals("GPU/NPU", spec.accelTag)
        assertTrue(spec.backendName.contains("GPU") || spec.backendName.contains("NPU"))
    }

    @Test
    fun testMaxMultiBubbleCountIsFive() {
        assertEquals(5, com.ping.verbead.engine.ModelConfig.MAX_MULTI_BUBBLE_COUNT)
        assertTrue(com.ping.verbead.engine.ModelConfig.MAX_MULTI_BUBBLE_COUNT >= 5)
    }

    @Test
    fun testSlmModelDownloadSpecForEngine() {
        for (meta in SlmModelConfig.AVAILABLE_MODELS) {
            val target = com.ping.verbead.engine.ModelDownloadSpec.forEngine(meta.id)
            assertEquals(meta.id, target.engine)
            assertEquals(1, target.files.size)
            val file = target.files.first()
            assertEquals(meta.fileName, file.relativePath)
            assertEquals(meta.downloadUrl, file.url)
            assertEquals(meta.sha256, file.sha256)
            assertNotNull(file.sha256)
            assertEquals(64, file.sha256!!.length)
            assertTrue(file.sha256!!.matches(Regex("^[0-9a-fA-F]{64}$")))
            assertTrue(file.url.startsWith("https://huggingface.co/"))
            assertTrue(file.relativePath.endsWith(".gguf"))
        }
    }

    @Test
    fun testSlmMemoryStateAndUnload() {
        SlmInferenceEngine.unloadModel()
        assertNull(SlmInferenceEngine.loadedModelId)
        assertEquals(0L, SlmInferenceEngine.loadedMemoryBytes)
        assertFalse(SlmInferenceEngine.isModelLoaded(SlmModelConfig.MODEL_QWEN_0_8B))
        assertFalse(SlmInferenceEngine.isModelLoaded(SlmModelConfig.MODEL_QWEN_2B))
        assertEquals("未載入記憶體", SlmInferenceEngine.getLoadedMemoryInfo())
    }

    @Test
    fun testInputTaskPromptsForExistingTextAndEmptyBox() {
        val sysWithTarget = SlmModelConfig.buildInputTaskSystemPrompt(
            hasTargetText = true,
            thinkingEnabled = false,
            mode = SlmModelConfig.OUTPUT_MODE_FAST
        )
        assertTrue(sysWithTarget.contains("<source_text>"))
        assertTrue(sysWithTarget.contains("<voice_command>"))
        assertTrue(sysWithTarget.contains("絕不可回答它"))

        val userWithTarget = SlmModelConfig.buildInputTaskUserPrompt(
            targetText = "今天下午三點開會，你來不來？",
            voiceInput = "改成禮貌商務語氣",
            thinkingEnabled = false
        )
        assertTrue(userWithTarget.contains("<source_text>今天下午三點開會，你來不來？</source_text>"))
        assertTrue(userWithTarget.contains("<voice_command>改成禮貌商務語氣</voice_command>"))
        assertTrue(userWithTarget.endsWith("/no_think"))

        val sysEmptyBox = SlmModelConfig.buildInputTaskSystemPrompt(
            hasTargetText = false,
            thinkingEnabled = false,
            mode = SlmModelConfig.OUTPUT_MODE_BULLET
        )
        assertTrue(sysEmptyBox.contains("<voice_input>"))
        assertTrue(sysEmptyBox.contains("去除口頭禪與語氣贅字"))

        val userEmptyBox = SlmModelConfig.buildInputTaskUserPrompt(
            targetText = "",
            voiceInput = "跟老闆說我今天感冒請假一天，幫我翻成英文",
            thinkingEnabled = true
        )
        assertTrue(userEmptyBox.contains("<voice_input>跟老闆說我今天感冒請假一天，幫我翻成英文</voice_input>"))
        assertFalse(userEmptyBox.contains("/no_think"))
    }

    @Test
    fun testBuildInputTaskChatMlPromptPrefillsEmptyThinkWhenDisabled() {
        val promptNoThink = SlmInferenceEngine.buildInputTaskChatMlPrompt(
            targetText = "原本的句子",
            voiceInput = "翻成英文",
            thinkingEnabled = false
        )
        assertTrue(promptNoThink.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n"))

        val promptWithThink = SlmInferenceEngine.buildInputTaskChatMlPrompt(
            targetText = "原本的句子",
            voiceInput = "翻成英文",
            thinkingEnabled = true
        )
        assertTrue(promptWithThink.endsWith("<|im_start|>assistant\n<think>\n"))
    }

    @Test
    fun testParseStreamContentWithPrefilledThinkStreamingAndCompletion() {
        // 1. While model is emitting thinking tokens (prompt prefilled <think>\n)
        val stream1 = "正在分析原句語境，準備改寫為商務委婉用語"
        val (thinking1, answer1) = SlmInferenceEngine.parseStreamContent(
            raw = stream1,
            thinkingEnabled = true,
            isPrefilledThink = true
        )
        assertEquals("正在分析原句語境，準備改寫為商務委婉用語", thinking1)
        assertEquals("", answer1)

        // 2. Model outputs </think> and starts generating target answer
        val stream2 = "正在分析原句語境，準備改寫為商務委婉用語\n</think>\n不好意思，明日會議我無法出席，敬請見諒。"
        val (thinking2, answer2) = SlmInferenceEngine.parseStreamContent(
            raw = stream2,
            thinkingEnabled = true,
            isPrefilledThink = true
        )
        assertEquals("正在分析原句語境，準備改寫為商務委婉用語", thinking2)
        assertEquals("不好意思，明日會議我無法出席，敬請見諒。", answer2)
    }

    @Test
    fun testExtractFallbackAnswerFromThinkingWhenUnclosed() {
        val thinkingWithMarker = "分析：原句較直接。建議改寫為：明天下午三點在線上討論專案進度。"
        val salvaged1 = SlmInferenceEngine.extractFallbackAnswerFromThinking(thinkingWithMarker)
        assertEquals("明天下午三點在線上討論專案進度。", salvaged1)

        val thinkingMultiLine = "1. 分析語氣問題\n2. 調整為敬語\n不好意思，因身體不適，今日需請假一天。"
        val salvaged2 = SlmInferenceEngine.extractFallbackAnswerFromThinking(thinkingMultiLine)
        assertEquals("不好意思，因身體不適，今日需請假一天。", salvaged2)
    }

    @Test
    fun testSanitizeInputTaskOutputStripsChatterTagsAndUnwantedQuotes() {
        // 1. 剝除開頭廢話前綴與多餘外層引號
        val chatterOutput = "好的，修改後的文字如下：\n「您好，今日下午三點將舉行會議，請問您是否方便出席？」"
        val cleaned1 = SlmInferenceEngine.sanitizeInputTaskOutput(
            rawAnswer = chatterOutput,
            sourceText = "今天下午三點開會，你來不來？"
        )
        assertEquals("您好，今日下午三點將舉行會議，請問您是否方便出席？", cleaned1)

        // 2. 剝除殘留 XML 標籤與英文前綴
        val xmlLeaked = "Translated text: <source_text>Where is the nearest MRT station?</source_text><|im_end|>"
        val cleaned2 = SlmInferenceEngine.sanitizeInputTaskOutput(
            rawAnswer = xmlLeaked,
            sourceText = "請問最近的捷運站在哪裡？"
        )
        assertEquals("Where is the nearest MRT station?", cleaned2)

        // 3. 若原文已有引號，則保留外層引號
        val quotedSource = "「原本就有引號」"
        val cleaned3 = SlmInferenceEngine.sanitizeInputTaskOutput(
            rawAnswer = "「改寫後依然保留引號」",
            sourceText = quotedSource
        )
        assertEquals("「改寫後依然保留引號」", cleaned3)
    }
}

