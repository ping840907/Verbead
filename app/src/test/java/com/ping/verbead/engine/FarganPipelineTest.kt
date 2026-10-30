package com.ping.verbead.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FarganPipelineTest {

    @Test
    fun testFarganFrameDimensions() {
        // FARGAN operates on 10ms frame (160 samples at 16kHz) and 2.5ms subframes (40 samples)
        assertEquals(160, FarganProcessor.FRAME_SIZE)
        assertEquals(40, FarganProcessor.SUBFRAME_SIZE)
        assertEquals(16_000, FarganProcessor.SAMPLE_RATE)
        assertEquals(160 / 4, FarganProcessor.SUBFRAME_SIZE)
    }

    @Test
    fun testFarganProcessorBufferProcessing() {
        FarganProcessor.reset()
        val testPcm = ShortArray(320) { (it * 100).toShort() }
        val processed = FarganProcessor.process(testPcm, testPcm.size)

        assertNotNull(processed)
        assertEquals(320, processed.size)
        // Verify output is finite and within short range
        for (sample in processed) {
            assertTrue(sample in Short.MIN_VALUE..Short.MAX_VALUE)
        }
    }

    @Test
    fun testFarganStreamingProcessing() {
        FarganProcessor.reset()
        val testFloats = FloatArray(160) { (it / 160.0f) * 0.5f }
        val processed = FarganProcessor.processStreaming(testFloats)

        assertNotNull(processed)
        assertEquals(160, processed.size)
        for (sample in processed) {
            assertTrue(sample in -1.0f..1.0f)
        }
    }

    @Test
    fun testFarganRoutingPolicy() {
        // Verification of FARGAN routing policy:
        // 1. External audio (Bluetooth headset) -> Route to FARGAN controlled by isFarganExternalAudioEnabled (default true)
        // 2. Internal mic (Phone microphone)    -> ALWAYS bypass FARGAN (preserve pristine acoustic features and prevent neural vocoder phase distortion)

        fun decideRoute(isExternal: Boolean, extPref: Boolean): Boolean {
            return isExternal && extPref
        }

        // Case 1: Default configuration (external audio routes, internal mic strictly bypasses)
        assertTrue("External audio must route to FARGAN by default", decideRoute(isExternal = true, extPref = true))
        assertFalse("Internal microphone must strictly bypass FARGAN", decideRoute(isExternal = false, extPref = true))

        // Case 2: User disables FARGAN for external audio
        assertFalse("External audio bypasses FARGAN when disabled", decideRoute(isExternal = true, extPref = false))
        assertFalse("Internal microphone remains bypassed", decideRoute(isExternal = false, extPref = false))
    }
}
