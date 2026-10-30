package com.ping.verbead.engine

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRoutingManagerTest {

    @Test
    fun testWidebandSpeechSampleRateConstraint() {
        assertEquals("ASR sample rate must be 16000Hz for mSBC compatibility", 16_000, ModelConfig.ASR_SAMPLE_RATE)
    }

    @Test
    fun testRoutingSessionProperties() {
        val session = AudioRoutingManager.RoutingSession(
            isExternal = true,
            isBluetooth = true,
            communicationDevice = null,
            inputDevice = null,
            audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            isWidebandSpeechSupported = true
        )
        assertTrue(session.isExternal)
        assertTrue(session.isBluetooth)
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, session.audioSource)
        assertTrue(session.isWidebandSpeechSupported)
    }

    @Test
    fun testRoutingSessionInternalMic() {
        val session = AudioRoutingManager.RoutingSession(
            isExternal = false,
            isBluetooth = false,
            communicationDevice = null,
            inputDevice = null,
            audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
            isWidebandSpeechSupported = true
        )
        assertFalse(session.isExternal)
        assertFalse(session.isBluetooth)
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, session.audioSource)
        assertTrue(session.isWidebandSpeechSupported)
    }

    @Test
    fun testWidebandSpeechSampleRateLogic() {
        // When rates array is empty, device has no restrictions -> supports 16kHz
        val emptyRates = intArrayOf()
        val emptySupported = emptyRates.isEmpty() || emptyRates.contains(ModelConfig.ASR_SAMPLE_RATE)
        assertTrue(emptySupported)

        // When rates array contains 16000 -> mSBC / WBS supported
        val msbcRates = intArrayOf(8000, 16000)
        val msbcSupported = msbcRates.isEmpty() || msbcRates.contains(ModelConfig.ASR_SAMPLE_RATE)
        assertTrue(msbcSupported)

        // When rates array only has 8000 -> legacy narrowband CVSD only
        val cvsdRates = intArrayOf(8000)
        val cvsdSupported = cvsdRates.isEmpty() || cvsdRates.contains(ModelConfig.ASR_SAMPLE_RATE)
        assertFalse(cvsdSupported)
    }
}
