package com.ping.verbead.engine.lang

import com.ping.verbead.engine.ModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLanguagesTest {

    @Test
    fun xAsr_isTraditionalChinese_andQwen3_isMultilingual() {
        assertEquals(setOf(LanguageTag.ZH_TW), ModelLanguages.tagsFor(ModelConfig.ENGINE_X_ASR))
        assertEquals(setOf(LanguageTag.MULTILINGUAL), ModelLanguages.tagsFor(ModelConfig.ENGINE_QWEN3))
    }

    @Test
    fun supports_followsTagsAndMultilingual() {
        assertTrue(ModelLanguages.supports(ModelConfig.ENGINE_X_ASR, LanguageTag.ZH_TW))
        assertFalse(ModelLanguages.supports(ModelConfig.ENGINE_X_ASR, LanguageTag.JA))
        for (language in LanguageTag.values()) {
            assertTrue(language.id, ModelLanguages.supports(ModelConfig.ENGINE_QWEN3, language))
        }
    }

    @Test
    fun enginesFor_putsSpecificBeforeMultilingual() {
        assertEquals(
            listOf(ModelConfig.ENGINE_X_ASR, ModelConfig.ENGINE_QWEN3),
            ModelLanguages.enginesFor(LanguageTag.ZH_TW)
        )
        assertEquals(listOf(ModelConfig.ENGINE_QWEN3), ModelLanguages.enginesFor(LanguageTag.JA))
    }

    @Test
    fun unknownEngine_hasNoTags() {
        assertTrue(ModelLanguages.tagsFor("nope").isEmpty())
        assertFalse(ModelLanguages.supports("nope", LanguageTag.EN))
        assertTrue(ModelLanguages.enginesFor(LanguageTag.EN).none { it == "nope" })
    }

    @Test
    fun tagIds_areUnique() {
        val ids = LanguageTag.values().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
