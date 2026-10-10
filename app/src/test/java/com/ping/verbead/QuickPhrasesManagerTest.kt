package com.ping.verbead

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickPhrasesManagerTest {

    @Test
    fun testDefaultPhrases() {
        val defaults = QuickPhrasesManager.DEFAULT_PHRASES
        assertTrue(defaults.isNotEmpty())
        assertTrue(defaults.contains("好的，收到！"))
        for (phrase in defaults) {
            assertFalse(phrase.isBlank())
        }
    }
}
