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
    fun testChatMlPromptAssembly() {
        val systemPrompt = "你是端側 AI 助手。"
        val history = listOf(
            SlmChatMessage(role = SlmChatMessage.Role.USER, content = "你好"),
            SlmChatMessage(role = SlmChatMessage.Role.ASSISTANT, content = "您好！有什麼我可以幫忙？", thinkingContent = "分析問候")
        )
        val prompt = SlmInferenceEngine.buildChatMlPrompt(systemPrompt, history, "幫我寫信")

        assertTrue(prompt.contains("<|im_start|>system\n你是端側 AI 助手。<|im_end|>"))
        assertTrue(prompt.contains("<|im_start|>user\n你好<|im_end|>"))
        assertTrue(prompt.contains("<think>分析問候</think>"))
        assertTrue(prompt.contains("您好！有什麼我可以幫忙？<|im_end|>"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun testSlmFourQwen35ModelsConfigAndCycling() {
        val promptThinkingOn = SlmModelConfig.buildSystemPrompt(thinkingEnabled = true)
        assertTrue(promptThinkingOn.contains("<think>"))
        assertTrue(promptThinkingOn.contains("簡短"))

        val promptThinkingOff = SlmModelConfig.buildSystemPrompt(thinkingEnabled = false)
        assertTrue(promptThinkingOff.contains("禁止輸出任何思考標籤") || promptThinkingOff.contains("直接給出答案"))

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
        assertEquals("vulkan", SlmModelConfig.BACKEND_VULKAN)
        assertEquals("nnapi", SlmModelConfig.BACKEND_NNAPI)
        assertEquals("qnn", SlmModelConfig.BACKEND_QNN)

        val spec = SlmModelConfig.SlmInferenceHardwareSpec(
            isHardwareAccel = true,
            gpuLayers = SlmModelConfig.DEFAULT_GPU_LAYERS,
            backendName = "GPU/NPU (Vulkan/NNAPI)",
            accelTag = "⚡ GPU/NPU"
        )
        assertTrue(spec.isHardwareAccel)
        assertEquals(99, spec.gpuLayers)
        assertEquals("⚡ GPU/NPU", spec.accelTag)
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
}
