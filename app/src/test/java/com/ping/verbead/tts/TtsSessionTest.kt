package com.ping.verbead.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsSessionTest {

    /** Records what it was asked to speak; the test decides when an utterance finishes or fails. */
    private class FakeEngine : TtsEngine {
        val spoken = mutableListOf<Pair<Int, String>>()
        val lastId: Int get() = spoken.last().first
        override fun init(callback: TtsEngine.Callback, onReady: (Boolean) -> Unit) = onReady(true)
        override fun speak(utteranceId: Int, text: String, lang: SpeechLang, rate: Float) { spoken += utteranceId to text }
        override fun stop() = Unit
        override fun shutdown() = Unit
    }

    private class Recorder : TtsSession.Listener {
        val indexes = mutableListOf<Int>()
        override fun onStateChanged(state: TtsSession.State) = Unit
        override fun onSentenceChanged(index: Int) { indexes += index }
    }

    private fun sentences(vararg t: String) = t.mapIndexed { i, s -> Sentence(s, i * 10, i * 10 + s.length) }

    private fun newSession(): Triple<TtsSession, FakeEngine, Recorder> {
        val e = FakeEngine()
        val r = Recorder()
        return Triple(TtsSession(e, r), e, r)
    }

    private fun FakeEngine.said() = spoken.map { it.second }

    @Test
    fun readsEverySentenceInOrderThenFinishes() {
        val (s, e, r) = newSession()
        s.start(sentences("a", "b", "c"))
        s.onDone(e.lastId)
        s.onDone(e.lastId)
        s.onDone(e.lastId)
        assertEquals(listOf("a", "b", "c"), e.said())
        assertEquals(TtsSession.State.FINISHED, s.state)
        assertEquals(listOf(0, 1, 2), r.indexes)
    }

    @Test
    fun emptyListFinishesAtOnce() {
        val (s, e, _) = newSession()
        s.start(emptyList())
        assertEquals(TtsSession.State.FINISHED, s.state)
        assertEquals(0, e.spoken.size)
    }

    @Test
    fun staleCallbacksAreIgnoredAfterASkip() {
        val (s, e, _) = newSession()
        s.start(sentences("a", "b", "c"))
        val first = e.lastId
        s.next()
        s.onDone(first) // the cut-off "a" reports in late
        assertEquals(1, s.index)
        assertEquals(listOf("a", "b"), e.said())
    }

    @Test
    fun pauseStopsAndResumeRepeatsTheCurrentSentence() {
        val (s, e, _) = newSession()
        s.start(sentences("a", "b"))
        s.onDone(e.lastId) // now on "b"
        s.pause()
        assertEquals(TtsSession.State.PAUSED, s.state)
        s.onDone(e.lastId) // a late callback must not advance a paused session
        assertEquals(1, s.index)
        s.resume()
        assertEquals(TtsSession.State.SPEAKING, s.state)
        assertEquals(listOf("a", "b", "b"), e.said())
    }

    @Test
    fun previousAndNextClampAtTheEnds() {
        val (s, e, _) = newSession()
        s.start(sentences("a", "b"))
        s.previous()
        assertEquals(0, s.index)
        s.next()
        assertEquals(1, s.index)
        s.next() // past the last sentence ends the reading
        assertEquals(TtsSession.State.FINISHED, s.state)
        assertEquals(listOf("a", "a", "b"), e.said())
    }

    @Test
    fun skippingWhilePausedMovesWithoutSpeaking() {
        val (s, e, _) = newSession()
        s.start(sentences("a", "b", "c"))
        s.pause()
        s.next()
        assertEquals(1, s.index)
        assertEquals(1, e.spoken.size)
        s.resume()
        assertEquals("b", e.said().last())
    }

    @Test
    fun resumeAfterFinishingStartsOver() {
        val (s, e, _) = newSession()
        s.start(sentences("a"))
        s.onDone(e.lastId)
        assertEquals(TtsSession.State.FINISHED, s.state)
        s.resume()
        assertEquals(TtsSession.State.SPEAKING, s.state)
        assertEquals(listOf("a", "a"), e.said())
    }

    @Test
    fun oneFailedSentenceIsSkipped() {
        val (s, e, _) = newSession()
        s.start(sentences("a", "b"))
        s.onError(e.lastId)
        assertEquals(1, s.index)
        assertEquals(TtsSession.State.SPEAKING, s.state)
    }

    @Test
    fun threeFailuresInARowStopWithAnError() {
        val (s, e, _) = newSession()
        s.start(sentences("a", "b", "c", "d"))
        s.onError(e.lastId)
        s.onError(e.lastId)
        s.onError(e.lastId)
        assertEquals(TtsSession.State.ERROR, s.state)
    }

    @Test
    fun successResetsTheFailureCount() {
        val (s, e, _) = newSession()
        s.start(sentences("a", "b", "c", "d", "e"))
        s.onError(e.lastId)
        s.onError(e.lastId)
        s.onDone(e.lastId)
        s.onError(e.lastId)
        assertEquals(TtsSession.State.SPEAKING, s.state)
    }

    @Test
    fun rateIsClampedAndRestartsTheCurrentSentenceWhileSpeaking() {
        val (s, e, _) = newSession()
        s.start(sentences("a"))
        s.rate = 9f
        assertEquals(TtsSession.MAX_RATE, s.rate)
        assertEquals(2, e.spoken.size)
    }

    @Test
    fun stopSilencesEverythingAndResets() {
        val (s, e, _) = newSession()
        s.start(sentences("a", "b"))
        val id = e.lastId
        s.stop()
        s.onDone(id)
        assertEquals(TtsSession.State.IDLE, s.state)
        assertEquals(0, s.index)
        assertEquals(1, e.spoken.size)
    }

    // ── an engine that can queue sentences behind the current one ───────

    /** Records speak and queue calls; canQueue false makes it behave like an engine that cannot queue. */
    private class QueueingEngine(var canQueue: Boolean = true) : TtsEngine {
        val calls = mutableListOf<String>()
        var spokenId = -1
        val queuedIds = mutableListOf<Int>()
        override fun init(callback: TtsEngine.Callback, onReady: (Boolean) -> Unit) = onReady(true)
        override fun speak(utteranceId: Int, text: String, lang: SpeechLang, rate: Float) {
            calls += "speak:$text"; spokenId = utteranceId; queuedIds.clear()
        }
        override fun queue(utteranceId: Int, text: String, lang: SpeechLang, rate: Float): Boolean {
            if (!canQueue) return false
            calls += "queue:$text"; queuedIds += utteranceId
            return true
        }
        override fun prefetch(text: String, lang: SpeechLang, rate: Float) { calls += "prefetch:$text" }
        override fun stop() { calls += "stop"; queuedIds.clear() }
        override fun shutdown() = Unit
    }

    @Test
    fun anEngineThatQueuesIsHandedTheNextTwoSentencesAtOnce() {
        val e = QueueingEngine()
        val s = TtsSession(e, Recorder())
        s.start(sentences("a", "b", "c", "d"))
        assertEquals(listOf("stop", "speak:a", "queue:b", "queue:c"), e.calls)
    }

    @Test
    fun whenASentenceEndsTheQueuedOneIsAlreadyPlayingAndOnlyTheQueueIsTopped() {
        val e = QueueingEngine()
        val r = Recorder()
        val s = TtsSession(e, r)
        s.start(sentences("a", "b", "c", "d"))
        val queuedB = e.queuedIds[0]
        e.calls.clear()
        s.onDone(e.spokenId)           // a is over; b is already playing
        assertEquals(listOf("queue:d"), e.calls) // no speak: the engine goes on by itself
        assertEquals(1, s.index)
        assertEquals(listOf(0, 1), r.indexes)
        e.calls.clear()
        s.onDone(queuedB)              // b is over
        assertEquals(2, s.index)
        assertEquals(emptyList<String>(), e.calls) // d is already queued, nothing left to hand over
    }

    @Test
    fun theLastSentenceFinishesTheReading() {
        val e = QueueingEngine()
        val s = TtsSession(e, Recorder())
        s.start(sentences("a", "b"))
        val b = e.queuedIds[0]
        s.onDone(e.spokenId)
        s.onDone(b)
        assertEquals(TtsSession.State.FINISHED, s.state)
    }

    @Test
    fun skippingDropsWhatWasQueuedAndStartsAgain() {
        val e = QueueingEngine()
        val s = TtsSession(e, Recorder())
        s.start(sentences("a", "b", "c", "d"))
        e.calls.clear()
        s.next()
        assertEquals(listOf("speak:b", "queue:c", "queue:d"), e.calls)
        assertEquals(1, s.index)
    }

    @Test
    fun aLateDoneForTheSkippedSentenceIsIgnored() {
        val e = QueueingEngine()
        val s = TtsSession(e, Recorder())
        s.start(sentences("a", "b", "c"))
        val old = e.spokenId
        s.next()
        s.onDone(old)
        assertEquals(1, s.index)
    }

    @Test
    fun pausingClearsTheQueueAndResumingSpeaksTheCurrentSentenceAgain() {
        val e = QueueingEngine()
        val s = TtsSession(e, Recorder())
        s.start(sentences("a", "b", "c"))
        s.onDone(e.spokenId)           // now on b
        s.pause()
        e.calls.clear()
        s.resume()
        assertEquals(listOf("speak:b", "queue:c"), e.calls)
    }

    @Test
    fun anEngineThatCannotQueueGetsAHintAndTheNextSentenceIsSpokenOnDone() {
        val e = QueueingEngine(canQueue = false)
        val s = TtsSession(e, Recorder())
        s.start(sentences("a", "b", "c"))
        assertEquals(listOf("stop", "speak:a", "prefetch:b"), e.calls)
        e.calls.clear()
        s.onDone(e.spokenId)
        assertEquals(listOf("speak:b", "prefetch:c"), e.calls)
    }

    @Test
    fun aQueuedSentenceThatFailsIsDroppedAndTheOnesBehindItToo() {
        val e = QueueingEngine()
        val s = TtsSession(e, Recorder())
        s.start(sentences("a", "b", "c", "d"))
        val b = e.queuedIds[0]
        s.onError(b)                   // b failed while a is playing
        e.calls.clear()
        s.onDone(e.spokenId)           // a done: nothing held any more, so b is spoken again
        assertEquals(listOf("speak:b", "queue:c", "queue:d"), e.calls)
    }

    @Test
    fun aFailedCurrentSentenceMovesOnToTheQueuedOne() {
        val e = QueueingEngine()
        val s = TtsSession(e, Recorder())
        s.start(sentences("a", "b", "c"))
        e.calls.clear()
        s.onError(e.spokenId)          // a failed; the engine carries on with b by itself
        assertEquals(1, s.index)
        assertEquals(emptyList<String>(), e.calls) // c was already queued behind b
    }
}
