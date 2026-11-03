package com.ping.verbead.watch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceDetectorTest {

    private val rate = 16_000
    private fun chunk(ms: Int, amp: Float) = FloatArray(rate * ms / 1000) { if (it % 2 == 0) amp else -amp }
    private fun detector(
        trailing: Float = 1.0f,
        initialTimeout: Float = 2.0f,
        min: Float = 0.4f,
    ) = SilenceDetector(rate, speechThreshold = 0.018f, silenceThreshold = 0.012f,
        trailingSilenceSeconds = trailing, initialTimeoutSeconds = initialTimeout, minSeconds = min)

    @Test
    fun pcmToFloats_decodesSignedLittleEndian() {
        val bytes = byteArrayOf(
            0x00, 0x00,          // 0
            0xFF.toByte(), 0x7F, // 32767
            0x00, 0x80.toByte(), // -32768
            0x01, 0x00,          // 1
            0x55,                // 落單位元組，應被丟棄
        )
        val f = PcmUtil.toFloats(bytes)
        assertEquals(4, f.size)
        assertArrayEquals(floatArrayOf(0f, 32767 / 32768f, -1f, 1 / 32768f), f, 1e-7f)
    }

    @Test
    fun rms_removesDcOffset() {
        assertEquals(0f, PcmUtil.rms(FloatArray(100) { 0.5f }), 1e-6f)
        assertEquals(0.5f, PcmUtil.rms(chunk(10, 0.5f)), 1e-4f)
        assertEquals(0f, PcmUtil.rms(FloatArray(0)), 0f)
    }

    @Test
    fun silenceOnly_timesOutWithNoSpeech() {
        val d = detector(initialTimeout = 2.0f)
        var event = SilenceDetector.Event.NONE
        var elapsed = 0
        while (event == SilenceDetector.Event.NONE && elapsed < 10_000) {
            event = d.feed(chunk(100, 0f)); elapsed += 100
        }
        assertEquals(SilenceDetector.Event.NO_SPEECH, event)
        assertEquals(2000, elapsed)
        assertFalse(d.spoken)
    }

    @Test
    fun speechThenSilence_autoStopsAfterTrailingSilence() {
        val d = detector(trailing = 1.0f)
        repeat(10) { assertEquals(SilenceDetector.Event.NONE, d.feed(chunk(100, 0.2f))) } // 1s 說話
        assertTrue(d.spoken)
        var silenceMs = 0
        var event = SilenceDetector.Event.NONE
        while (event == SilenceDetector.Event.NONE && silenceMs < 5000) {
            event = d.feed(chunk(100, 0f)); silenceMs += 100
        }
        assertEquals(SilenceDetector.Event.AUTO_STOP, event)
        assertEquals(1000, silenceMs)
    }

    @Test
    fun briefPauseInsideSpeech_doesNotStop() {
        val d = detector(trailing = 1.0f)
        repeat(5) { d.feed(chunk(100, 0.2f)) }
        repeat(8) { assertEquals(SilenceDetector.Event.NONE, d.feed(chunk(100, 0f))) } // 0.8s 停頓 < 1s
        assertEquals(SilenceDetector.Event.NONE, d.feed(chunk(100, 0.2f)))             // 繼續說話重置計時
        repeat(9) { assertEquals(SilenceDetector.Event.NONE, d.feed(chunk(100, 0f))) }
    }

    @Test
    fun veryShortClick_isNotTreatedAsSpeech() {
        val d = detector(initialTimeout = 1.0f)
        d.feed(chunk(30, 0.5f)) // < 100ms 的爆音
        assertFalse(d.spoken)
    }
}
