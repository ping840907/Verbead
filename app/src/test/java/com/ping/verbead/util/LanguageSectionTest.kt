package com.ping.verbead.util

import com.ping.verbead.engine.lang.LanguageProfiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageSectionTest {

    @Test
    fun chineseSection_isExpandedByDefaultOnlyForChineseInputLanguages() {
        val ids = LanguageProfiles.CHINESE_IDS
        assertTrue(LanguageSection.defaultExpanded(ids, "zh-TW"))
        assertTrue(LanguageSection.defaultExpanded(ids, "zh-CN"))
        assertFalse(LanguageSection.defaultExpanded(ids, "en"))
        assertFalse(LanguageSection.defaultExpanded(ids, "ja"))
        assertFalse(LanguageSection.defaultExpanded(ids, "ko"))
    }

    @Test
    fun chineseIds_areRegisteredProfiles() {
        val registered = LanguageProfiles.ALL.map { it.id }.toSet()
        assertTrue(registered.containsAll(LanguageProfiles.CHINESE_IDS))
    }
}
