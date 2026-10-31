package com.ping.verbead.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Lfm3AudioEngineTest {

    @Test
    fun testAudioHyperparameters() {
        assertEquals(16_000, Lfm3AudioEngine.SAMPLE_RATE)
        assertEquals(80, Lfm3AudioEngine.NUM_MELS)
        assertEquals(400, Lfm3AudioEngine.N_FFT)
        assertEquals(160, Lfm3AudioEngine.HOP_LENGTH)
        assertEquals(2048, Lfm3AudioEngine.HIDDEN_SIZE)
        assertEquals(24, Lfm3AudioEngine.NUM_LAYERS)
        assertEquals(7, Lfm3AudioEngine.CONV_KERNEL_SIZE)
    }

    @Test
    fun testLogMelExtractionShapeAndValues() {
        val sampleRate = Lfm3AudioEngine.SAMPLE_RATE
        val testSignal = FloatArray(sampleRate) { i ->
            kotlin.math.sin(2.0 * kotlin.math.PI * 440.0 * i / sampleRate).toFloat() * 0.5f
        }

        val mels = Lfm3AudioEngine.extractLogMel(testSignal)
        assertNotNull(mels)
        assertTrue(mels.isNotEmpty())

        val numMels = Lfm3AudioEngine.NUM_MELS
        val numFrames = mels.size / numMels
        assertTrue("Number of frames should be around 100 for 1s of audio", numFrames in 95..105)

        for (v in mels) {
            assertFalse("Mel values must not be NaN", v.isNaN())
            assertFalse("Mel values must not be infinite", v.isInfinite())
            assertTrue("Mel values should be reasonable: $v", v in -25.0f..15.0f)
        }
    }

    @Test
    fun testEmptySignalHandling() {
        val emptySignal = FloatArray(0)
        val mels = Lfm3AudioEngine.extractLogMel(emptySignal)
        assertNotNull(mels)
        assertEquals(0, mels.size)
    }

    @Test
    fun testEngineSessionLifecycle() {
        assertFalse(Lfm3AudioEngine.isSessionReady())
        Lfm3AudioEngine.onTrimMemory()
        assertFalse(Lfm3AudioEngine.isSessionReady())
        Lfm3AudioEngine.release()
        assertFalse(Lfm3AudioEngine.isSessionReady())
    }

    @Test
    fun testChatMessageDataStructures() {
        val userMsg = Lfm3ChatMessage(isUser = true, text = "你好！")
        assertTrue(userMsg.isUser)
        assertEquals("你好！", userMsg.text)

        val assistantMsg = Lfm3ChatMessage(
            isUser = false,
            text = "您好，請問有什麼我可以協助的？",
            audioSamples = FloatArray(2400) { 0.1f }
        )
        assertFalse(assistantMsg.isUser)
        assertNotNull(assistantMsg.audioSamples)
        assertEquals(2400, assistantMsg.audioSamples?.size)

        val response = Lfm3ChatResponse(
            userText = "現在幾點？",
            replyText = "現在是下午三點。",
            replyAudioSamples = FloatArray(4800) { 0.05f },
            audioSampleRate = 24_000,
            latencyMs = 45L
        )
        assertEquals("現在幾點？", response.userText)
        assertEquals(24_000, response.audioSampleRate)
        assertEquals(45L, response.latencyMs)
    }
}
