package com.ping.verbead

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickPhrasesManagerTest {

    @Test
    fun testDefaultPhrases() {
        val defaults = QuickPhrasesManager.DEFAULT_PHRASE_RES
        assertTrue(defaults.isNotEmpty())
        assertEquals(6, defaults.size)
        assertEquals(defaults.size, defaults.toSet().size)
    }

    @Test
    fun testMaxHistorySize() {
        assertEquals(6, QuickPhrasesManager.MAX_HISTORY_SIZE)
    }
}
