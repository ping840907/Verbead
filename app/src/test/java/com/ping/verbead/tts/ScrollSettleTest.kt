package com.ping.verbead.tts

import com.ping.verbead.tts.ScrollSettle.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollSettleTest {

    @Test
    fun settlesWhenTheScreenChangedAndThenStoodStill() {
        val s = ScrollSettle("old")
        assertEquals(Verdict.WAIT, s.onSample("old", 250))      // the scroll has not shown yet
        assertEquals(Verdict.WAIT, s.onSample("moving", 500))   // changed, but it may still be moving
        assertEquals(Verdict.WAIT, s.onSample("moving 2", 750))
        assertEquals(Verdict.SETTLED, s.onSample("moving 2", 1000))
    }

    @Test
    fun aScrollThatNeverShowsIsTheEnd() {
        val s = ScrollSettle("old", timeoutMs = 1000)
        assertEquals(Verdict.WAIT, s.onSample("old", 250))
        assertEquals(Verdict.WAIT, s.onSample("old", 750))
        assertEquals(Verdict.UNCHANGED, s.onSample("old", 1000))
    }

    @Test
    fun aScreenStillMovingAtTheTimeoutIsTakenAsItIs() {
        val s = ScrollSettle("old", timeoutMs = 1000)
        s.onSample("a", 250)
        s.onSample("b", 500)
        assertEquals(Verdict.SETTLED, s.onSample("c", 1000))
    }

    @Test
    fun theScreenGoingBackToTheOldTextIsNotSettled() {
        val s = ScrollSettle("old", timeoutMs = 5000)
        s.onSample("x", 250)
        assertEquals(Verdict.WAIT, s.onSample("old", 500))
        assertEquals(Verdict.WAIT, s.onSample("old", 750)) // steady, but the same as before the scroll
    }
}
