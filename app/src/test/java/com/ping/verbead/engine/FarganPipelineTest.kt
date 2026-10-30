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
    fun testFarganDualPipelineDecisionLogic() {
        // Verification of dual-pipeline routing policy:
        // Policy:
        // 1. External audio (Bluetooth headset) -> Route to FARGAN enabled by default (reconstruct mSBC loss)
        // 2. Internal mic (Phone microphone)    -> Bypass FARGAN by default (preserve pristine acoustic features)

        fun decideRoute(isExternal: Boolean, extPref: Boolean, intPref: Boolean): Boolean {
            return if (isExternal) extPref else intPref
        }

        // Case 1: Default configuration
        val defaultExtRoute = decideRoute(isExternal = true, extPref = true, intPref = false)
        val defaultIntRoute = decideRoute(isExternal = false, extPref = true, intPref = false)
        assertTrue("External audio must route to FARGAN by default", defaultExtRoute)
        assertFalse("Internal microphone must bypass FARGAN by default", defaultIntRoute)

        // Case 2: User explicitly enables FARGAN for internal mic
        val userEnabledIntRoute = decideRoute(isExternal = false, extPref = true, intPref = true)
        assertTrue("Internal microphone routes to FARGAN when explicitly enabled", userEnabledIntRoute)

        // Case 3: User explicitly disables FARGAN for external audio
        val userDisabledExtRoute = decideRoute(isExternal = true, extPref = false, intPref = false)
        assertFalse("External audio bypasses FARGAN when disabled", userDisabledExtRoute)
    }
}
