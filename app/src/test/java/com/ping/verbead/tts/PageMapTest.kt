package com.ping.verbead.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageMapTest {

    @Test
    fun aNewReadingNumbersFromTheStart() {
        val m = PageMap()
        assertEquals(5, m.local(5))
        assertEquals(0, m.firstIndex)
        assertFalse(m.hasPending)
    }

    @Test
    fun theQueuedPageTakesOverOnlyWhenItsFirstSentenceIsReached() {
        val m = PageMap()
        // page 1 has 10 sentences; page 2 was appended from its sentence 3 on
        m.queueNext(sessionSize = 10, from = 3)
        assertTrue(m.hasPending)
        for (i in 0..9) {
            assertFalse("still page 1 at $i", m.reached(i))
            assertEquals(i, m.local(i))
        }
        assertTrue(m.reached(10))
        assertEquals(3, m.local(10))   // the first appended sentence is sentence 3 of page 2
        assertEquals(5, m.local(12))
        assertEquals(10, m.firstIndex)
        assertFalse(m.hasPending)
    }

    @Test
    fun aSecondPageCanFollowTheFirst() {
        val m = PageMap()
        m.queueNext(10, 3)
        m.reached(10)
        // page 2 had 8 sentences, 3..7 appended: the session now holds 15
        m.queueNext(15, 2)
        assertFalse(m.reached(14))
        assertTrue(m.reached(15))
        assertEquals(2, m.local(15))
        assertEquals(15, m.firstIndex)
    }

    @Test
    fun aQueuedPageCanBeForgotten() {
        val m = PageMap()
        m.queueNext(10, 0)
        m.clearPending()
        assertFalse(m.reached(11))
        assertEquals(11, m.local(11))
    }

    @Test
    fun resetForgetsEverything() {
        val m = PageMap()
        m.queueNext(10, 3)
        m.reached(10)
        m.reset()
        assertEquals(4, m.local(4))
        assertEquals(0, m.firstIndex)
    }

    @Test
    fun skippingPastTheJoinStillSwitchesPage() {
        // a tap on "next" at the end can jump over the exact first sentence
        val m = PageMap()
        m.queueNext(10, 3)
        assertTrue(m.reached(11))
        assertEquals(4, m.local(11))
    }
}
