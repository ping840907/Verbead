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
    fun testSlmModelConfigAndPrompts() {
        val promptThinkingOn = SlmModelConfig.buildSystemPrompt(thinkingEnabled = true)
        assertTrue(promptThinkingOn.contains("<think>"))
        assertTrue(promptThinkingOn.contains("簡短"))

        val promptThinkingOff = SlmModelConfig.buildSystemPrompt(thinkingEnabled = false)
        assertTrue(promptThinkingOff.contains("禁止輸出任何思考標籤") || promptThinkingOff.contains("直接給出答案"))

        val model15B = SlmModelConfig.getModelMetadata(SlmModelConfig.MODEL_QWEN_1_5B)
        assertNotNull(model15B)
        assertEquals("Qwen2.5-1.5B", model15B.displayName)
        assertTrue(model15B.fileName.endsWith(".gguf"))

        val model05B = SlmModelConfig.getModelMetadata(SlmModelConfig.MODEL_QWEN_0_5B)
        assertNotNull(model05B)
        assertEquals("Qwen2.5-0.5B", model05B.displayName)
        assertTrue(model05B.fileName.endsWith(".gguf"))
    }
}
