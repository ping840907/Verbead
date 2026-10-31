package com.ping.verbead.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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
        // Generate 1 second of 16kHz sine wave test signal (440 Hz)
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

        // Verify values are finite and within reasonable log-mel dynamic range (-20 to 10)
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
}
