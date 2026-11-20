package com.ping.verbead.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpeechAudioTest {

    private val sr = 10_000 // 10 frames per millisecond keeps the arithmetic easy

    /** [leadMs] of silence, [speechMs] of a loud tone, [trailMs] of silence. */
    private fun clip(leadMs: Int, speechMs: Int, trailMs: Int, level: Float = 0.5f): FloatArray {
        val lead = leadMs * 10
        val speech = speechMs * 10
        val trail = trailMs * 10
        return FloatArray(lead + speech + trail) { i -> if (i in lead until lead + speech) level else 0f }
    }

    @Test
    fun silenceAtBothEndsIsCutButAKeepMarginStays() {
        val out = SpeechAudio.shape(clip(100, 300, 150), sr, pauseMs = 0)
        val expected = (SpeechAudio.KEEP_BEFORE_MS + 300 + SpeechAudio.KEEP_AFTER_MS) * 10
        assertEquals(expected, out.size)
    }

    @Test
    fun theWantedPauseIsAddedAsSilence() {
        val bare = SpeechAudio.shape(clip(100, 300, 150), sr, pauseMs = 0)
        val out = SpeechAudio.shape(clip(100, 300, 150), sr, pauseMs = 200)
        assertEquals(bare.size + 2000, out.size)
        assertTrue(out.takeLast(2000).all { it == 0f })
    }

    @Test
    fun theEndsAreFadedSoTheCutDoesNotClick() {
        val out = SpeechAudio.shape(clip(0, 300, 0), sr, pauseMs = 0)
        assertEquals(0f, out.first(), 0f)
        assertEquals(0f, out.last(), 0f)
        assertEquals(0.5f, out[out.size / 2], 0f) // the middle is untouched
    }

    @Test
    fun aQuietClipIsJudgedAgainstItsOwnPeak() {
        val out = SpeechAudio.shape(clip(100, 300, 150, level = 0.02f), sr, pauseMs = 0)
        assertEquals((SpeechAudio.KEEP_BEFORE_MS + 300 + SpeechAudio.KEEP_AFTER_MS) * 10, out.size)
    }

    @Test
    fun softNoiseBelowTheThresholdCountsAsSilence() {
        val noisy = clip(100, 300, 150)
        for (i in 0 until 1000) noisy[i] = 0.004f // a faint hiss before the speech
        val out = SpeechAudio.shape(noisy, sr, pauseMs = 0)
        assertEquals((SpeechAudio.KEEP_BEFORE_MS + 300 + SpeechAudio.KEEP_AFTER_MS) * 10, out.size)
    }

    @Test
    fun audioThatIsSilentThroughoutBecomesJustThePause() {
        val out = SpeechAudio.shape(FloatArray(5000), sr, pauseMs = 100)
        assertEquals(1000, out.size)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun emptyAudioIsJustThePause() {
        assertEquals(500, SpeechAudio.shape(FloatArray(0), sr, pauseMs = 50).size)
    }

    @Test
    fun theInputIsNotChanged() {
        val input = clip(100, 300, 150)
        val copy = input.copyOf()
        SpeechAudio.shape(input, sr, pauseMs = 100)
        assertTrue(input.contentEquals(copy))
    }

    @Test
    fun aClipWithoutSilenceKeepsAllItsSpeech() {
        val out = SpeechAudio.shape(clip(0, 400, 0), sr, pauseMs = 0)
        assertEquals(4000, out.size)
        assertTrue(abs(out[out.size / 2] - 0.5f) < 1e-6f)
    }

    @Test
    fun pauseDependsOnHowTheSentenceEnds() {
        assertEquals(SpeechAudio.PAUSE_SENTENCE_MS, SpeechAudio.pauseMs("今天天氣很好。"))
        assertEquals(SpeechAudio.PAUSE_SENTENCE_MS, SpeechAudio.pauseMs("Is it done?"))
        assertEquals(SpeechAudio.PAUSE_SENTENCE_MS, SpeechAudio.pauseMs("他說：「好。」")) // a closing quote does not hide the stop
        assertEquals(SpeechAudio.PAUSE_CLAUSE_MS, SpeechAudio.pauseMs("先這樣；"))
        assertEquals(SpeechAudio.PAUSE_OTHER_MS, SpeechAudio.pauseMs("麥克風錄音權限 已就緒"))
        assertEquals(SpeechAudio.PAUSE_OTHER_MS, SpeechAudio.pauseMs(""))
        assertTrue(SpeechAudio.PAUSE_SENTENCE_MS > SpeechAudio.PAUSE_CLAUSE_MS && SpeechAudio.PAUSE_CLAUSE_MS > SpeechAudio.PAUSE_OTHER_MS)
    }

    @Test
    fun theUsersPauseChoiceScalesThePause() {
        assertEquals(90, SpeechAudio.pauseMs("好。", 0.5f))
        assertEquals(180, SpeechAudio.pauseMs("好。", 1f))
        assertEquals(306, SpeechAudio.pauseMs("好。", 1.7f))
        assertEquals(45, SpeechAudio.pauseMs("好", 0.5f))
        assertEquals(0, SpeechAudio.pauseMs("好。", 0f))
    }
}
