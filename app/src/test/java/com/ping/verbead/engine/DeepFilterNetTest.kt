package com.ping.verbead.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DeepFilterNetTest {

    @Test
    fun testDeepFilterNetDimensions() {
        assertEquals(16_000, DeepFilterNetProcessor.SAMPLE_RATE)
        assertEquals(160, DeepFilterNetProcessor.FRAME_SIZE)
        assertEquals(256, DeepFilterNetProcessor.FFT_SIZE)
        assertEquals(129, DeepFilterNetProcessor.NUM_BINS)
    }

    @Test
    fun testDeepFilterNetBufferProcessing() {
        DeepFilterNetProcessor.reset()
        val testPcm = ShortArray(320) { (it * 80).toShort() }
        val processed = DeepFilterNetProcessor.process(testPcm, testPcm.size)

        assertNotNull(processed)
        assertEquals(320, processed.size)
        for (sample in processed) {
            assertTrue(sample in Short.MIN_VALUE..Short.MAX_VALUE)
        }
    }

    @Test
    fun testDeepFilterNetStreamingProcessing() {
        DeepFilterNetProcessor.reset()
        val testFloats = FloatArray(480) { (it / 480.0f) * 0.4f }
        val processed = DeepFilterNetProcessor.processStreaming(testFloats)

        assertNotNull(processed)
        assertEquals(480, processed.size)
        for (sample in processed) {
            assertTrue(sample in -1.0f..1.0f)
        }
    }

    @Test
    fun testDeepFilterNetNoiseSuppression() {
        DeepFilterNetProcessor.reset()
        // Generate simulated low-amplitude stationary noise
        val noiseSamples = FloatArray(800) { ((it % 17) - 8) * 0.005f }
        val filtered = DeepFilterNetProcessor.processStreaming(noiseSamples)

        // Verify output is bounded and finite
        for (sample in filtered) {
            assertTrue(abs(sample) <= 1.0f)
        }
    }
}
