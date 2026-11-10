package com.ping.verbead.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

class AsrCatalogTest {

    @Test fun idsAndFoldersAreUnique() {
        val models = AsrCatalog.models
        assertEquals(models.size, models.map { it.id }.toSet().size)
        assertEquals(models.size, models.map { it.dir }.toSet().size)
    }

    @Test fun everyModelHasLanguagesAndADownloadSource() {
        for (m in AsrCatalog.models) {
            assertTrue("${m.id} languages", m.languages.isNotEmpty())
            // a model comes as one archive or as a list of files, and each has a checksum
            if (m.archiveUrl != null) {
                assertTrue("${m.id} sha", m.archiveSha256?.length == 64)
            } else {
                assertTrue("${m.id} files", m.files.isNotEmpty() && m.files.all { it.sha256?.length == 64 })
            }
        }
    }

    @Test fun everyDownloadableModelHasATarget() {
        for (m in AsrCatalog.models) {
            val t = ModelDownloadSpec.forEngine(m.id)
            assertEquals(m.id, t.engine)
            assertTrue(m.id, t.archiveUrl != null || t.files.isNotEmpty())
        }
    }

    @Test fun bothKindsExist() {
        assertTrue(AsrCatalog.ofMode(AsrMode.LIVE).size >= 2)
        assertTrue(AsrCatalog.ofMode(AsrMode.FINAL).size >= 2)
    }

    @Test fun languageMatchingUsesTheBaseLanguage() {
        assertTrue(AsrModel.languageMatches("zh", "zh-TW"))
        assertTrue(AsrModel.languageMatches("zh-TW", "zh-TW"))
        assertFalse(AsrModel.languageMatches("zh-TW", "zh-CN"))
        assertFalse(AsrModel.languageMatches("zh-TW", "zh"))
        assertFalse(AsrModel.languageMatches("en", "es"))
    }

    @Test fun filterByKindAndLanguage() {
        val none = { _: AsrModel -> false }
        val liveJa = AsrCatalog.filter(AsrMode.LIVE, "ja", none)
        assertEquals(setOf("nemotron35", "nemotron35_160", "nemotron35_1120", "zipformer_multi8"), liveJa.map { it.id }.toSet())
        val finalVi = AsrCatalog.filter(AsrMode.FINAL, "vi", none).map { it.id }
        assertTrue("moonshine_base_vi" in finalVi)
        assertTrue(AsrCatalog.QWEN3 in finalVi)
        assertFalse("parakeet_v3" in finalVi)
        assertEquals(AsrCatalog.models.size, AsrCatalog.filter(null, null, none).size)
    }

    @Test fun traditionalChineseFindsXAsrQwenAndMoonshine() {
        val ids = AsrCatalog.filter(null, "zh-TW", { false }).map { it.id }
        assertTrue(AsrCatalog.X_ASR in ids)
        assertTrue(AsrCatalog.QWEN3 in ids)
        assertTrue("moonshine_base_zh" in ids)
    }

    @Test fun downloadedOnlyFilter() {
        val ids = AsrCatalog.filter(null, null, { it.id == AsrCatalog.QWEN3 }, downloadedOnly = true).map { it.id }
        assertEquals(listOf(AsrCatalog.QWEN3), ids)
    }

    @Test fun languageListIsSortedAndComplete() {
        val all = AsrCatalog.allLanguages(Locale.ENGLISH)
        assertEquals(all.size, all.toSet().size)
        assertTrue("en" in all && "ja" in all && "vi" in all && "th" in all)
        val names = all.map { AsrCatalog.languageName(it, Locale.ENGLISH).lowercase() }
        assertEquals(names.sorted(), names)
    }

    @Test fun languageNamesComeFromThePlatform() {
        assertEquals("Japanese", AsrCatalog.languageName("ja", Locale.ENGLISH))
        assertEquals("Vietnamese", AsrCatalog.languageName("vi", Locale.ENGLISH))
    }

    @Test fun unknownModelId() {
        assertNull(AsrCatalog.byId("nope"))
        assertNull(AsrCatalog.byId(null))
    }

    @Test fun findsTransducerFilesAndPrefersInt8() {
        val dir = createTempDir()
        try {
            for (n in listOf("encoder-epoch-99-avg-1.onnx", "encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx",
                "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt")) File(dir, n).writeText("x")
            val t = AsrModelFiles.transducer(dir)!!
            assertTrue(t.encoder.endsWith("encoder-epoch-99-avg-1.int8.onnx"))
            assertTrue(t.decoder.endsWith("decoder-epoch-99-avg-1.onnx"))
            File(dir, "tokens.txt").delete()
            assertNull(AsrModelFiles.transducer(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun findsMoonshineFiles() {
        val dir = createTempDir()
        try {
            for (n in listOf("encoder_model.ort", "decoder_model_merged.ort", "tokens.txt")) File(dir, n).writeText("x")
            val m = AsrModelFiles.moonshine(dir)!!
            assertTrue(m.encoder.endsWith("encoder_model.ort"))
            assertTrue(m.mergedDecoder.endsWith("decoder_model_merged.ort"))
            File(dir, "decoder_model_merged.ort").delete()
            assertNull(AsrModelFiles.moonshine(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun legacySettingsMigrateToTwoSlots() {
        assertEquals(ModelConfig.AsrSelection(null, AsrCatalog.QWEN3), ModelConfig.migrateLegacySelection(AsrCatalog.QWEN3, false))
        assertEquals(ModelConfig.AsrSelection(AsrCatalog.X_ASR, null), ModelConfig.migrateLegacySelection(AsrCatalog.X_ASR, false))
        assertEquals(
            ModelConfig.AsrSelection(AsrCatalog.X_ASR, AsrCatalog.QWEN3),
            ModelConfig.migrateLegacySelection(AsrCatalog.QWEN3, true)
        )
        assertEquals(ModelConfig.AsrSelection(null, AsrCatalog.QWEN3), ModelConfig.migrateLegacySelection(null, false))
    }
}

class AsrOutputNormalizationTest {
    private val zip = AsrCatalog.byId("zipformer_en_20m")!!

    @Test fun capitalsOnlyOutputBecomesSentenceCase() {
        assertEquals("As a direct consequence of the sin.", zip.normalizeText("AS A DIRECT CONSEQUENCE OF THE SIN."))
        assertEquals("I think I'm ready", zip.normalizeText("I THINK I'M READY"))
    }

    @Test fun xAsrMixedChineseAndEnglish() {
        val x = AsrCatalog.byId(AsrCatalog.X_ASR)!!
        assertEquals("我想用 hello world 開會", x.normalizeText("我想用 HELLO WORLD 開會"))
        assertEquals("Hello world。我也是 I think", x.normalizeText("HELLO WORLD。我也是 I THINK"))
        assertEquals("今天天氣很好", x.normalizeText("今天天氣很好"))
        assertEquals("我用 iPhone", x.normalizeText("我用 iPhone"))
    }

    @Test fun otherOutputIsLeftAlone() {
        assertEquals("Already fine.", zip.normalizeText("Already fine."))
        assertEquals("", zip.normalizeText(""))
        val parakeet = AsrCatalog.byId("parakeet_v3")!!
        assertEquals("ABC", parakeet.normalizeText("ABC"))
    }
}

class AsrManifestTest {
    @Test fun customEntryIsParsedWithDefaults() {
        val r = AsrCatalog.parse("""
            [{"id":"my_model","name":"My Model","mode":"final","loader":"offline_transducer","languages":["sv"],
              "archive":{"url":"https://example.com/m.tar.bz2"},"description":{"en":"Mine","zh-TW":"我的"}}]
        """)
        assertTrue(r.problems.toString(), r.problems.isEmpty())
        val m = r.models.single()
        assertEquals("my_model", m.dir)
        assertEquals(listOf("cpu"), m.providers)
        assertEquals(AsrMode.FINAL, m.mode)
        assertEquals(AsrLoader.OFFLINE_TRANSDUCER, m.loader)
        assertEquals("我的", m.descriptionFor(Locale.TRADITIONAL_CHINESE))
        assertEquals("Mine", m.descriptionFor(Locale.GERMAN))
    }

    @Test fun badEntriesAreSkippedWithAMessageAndTheRestSurvives() {
        val r = AsrCatalog.parse("""
            {"models":[
              {"id":"ok","name":"A","mode":"live","loader":"online_transducer","languages":["en"],"archive":{"url":"u"}},
              {"id":"bad_loader","name":"B","mode":"live","loader":"magic","languages":["en"],"archive":{"url":"u"}},
              {"id":"no_source","name":"C","mode":"live","loader":"moonshine","languages":["en"]},
              {"id":"../evil","name":"D","mode":"live","loader":"moonshine","languages":["en"],"archive":{"url":"u"}},
              {"id":"escape","dir":"..","name":"E","mode":"live","loader":"moonshine","languages":["en"],"archive":{"url":"u"}}
            ]}
        """)
        assertEquals(listOf("ok"), r.models.map { it.id })
        assertEquals(4, r.problems.size)
    }

    @Test fun brokenManifestIsReportedNotThrown() {
        val r = AsrCatalog.parse("not json")
        assertTrue(r.models.isEmpty())
        assertEquals(1, r.problems.size)
    }

    @Test fun customManifestCannotReplaceBuiltInModels() {
        val dir = createTempDir()
        try {
            File(dir, AsrCatalog.CUSTOM_MANIFEST).writeText("""[
              {"id":"qwen3","name":"Fake","mode":"final","loader":"moonshine","languages":["en"],"archive":{"url":"u"}},
              {"id":"extra","name":"Extra","mode":"final","loader":"moonshine","languages":["en"],"archive":{"url":"u"}}]""")
            AsrCatalog.reloadCustom(listOf(dir))
            assertEquals("Qwen3-ASR", AsrCatalog.byId("qwen3")!!.name)
            assertEquals("Extra", AsrCatalog.byId("extra")!!.name)
            assertEquals(1, AsrCatalog.customProblems.size)
        } finally {
            AsrCatalog.reloadCustom(emptyList())
            dir.deleteRecursively()
        }
        assertNull(AsrCatalog.byId("extra"))
    }

    @Test fun builtInManifestHasEveryLoaderAndBothModes() {
        assertTrue(AsrCatalog.models.map { it.loader }.toSet().containsAll(AsrLoader.entries))
        assertTrue(AsrCatalog.models.all { it.description.isNotEmpty() })
    }
}

class AsrGroupingTest {
    @Test fun moonshineVariantsShareOneGroup() {
        val groups = AsrCatalog.grouped(AsrCatalog.models)
        val moonshine = groups.single { it.first().group == "Moonshine v2" }
        assertEquals(10, moonshine.size)
        assertTrue(moonshine.all { it.variant != null })
        // every other model is a group of its own
        assertTrue(groups.filter { it.first().group == null }.all { it.size == 1 })
    }

    @Test fun languageFilterNarrowsTheGroup() {
        val shown = AsrCatalog.filter(AsrMode.FINAL, "ja", { false })
        val moonshineJa = AsrCatalog.grouped(shown).single { it.first().group == "Moonshine v2" }
        assertEquals(listOf("moonshine_tiny_ja", "moonshine_base_ja"), moonshineJa.map { it.id }.sorted().reversed().sortedBy { it.contains("base") })
    }
}

class AsrRecommenderTest {
    private fun pick(lang: String, mode: AsrMode, tier: AsrTier) =
        AsrRecommender.pick(AsrCatalog.models, lang, mode, tier)?.id

    @Test fun taiwaneseChinese() {
        assertEquals("qwen3", pick("zh-TW", AsrMode.FINAL, AsrTier.BEST))
        assertEquals("qwen3", pick("zh-TW", AsrMode.FINAL, AsrTier.BALANCED))
        assertEquals("moonshine_base_zh", pick("zh-TW", AsrMode.FINAL, AsrTier.LIGHT))
        assertEquals("x_asr", pick("zh-TW", AsrMode.LIVE, AsrTier.BALANCED))
    }

    @Test fun english() {
        assertEquals("moonshine_tiny_en", pick("en", AsrMode.FINAL, AsrTier.LIGHT))
        assertEquals("parakeet_110m_en", pick("en", AsrMode.FINAL, AsrTier.BALANCED))
        assertEquals("parakeet_v3", pick("en", AsrMode.FINAL, AsrTier.BEST))
        assertEquals("zipformer_en_20m", pick("en", AsrMode.LIVE, AsrTier.LIGHT))
    }

    @Test fun japaneseKoreanVietnameseThai() {
        assertEquals("moonshine_tiny_ja", pick("ja", AsrMode.FINAL, AsrTier.LIGHT))
        assertEquals("moonshine_base_ja", pick("ja", AsrMode.FINAL, AsrTier.BALANCED))
        assertEquals("qwen3", pick("ja", AsrMode.FINAL, AsrTier.BEST))
        assertEquals("moonshine_tiny_ko", pick("ko", AsrMode.FINAL, AsrTier.LIGHT))
        assertEquals("qwen3", pick("ko", AsrMode.FINAL, AsrTier.BALANCED))
        assertEquals("moonshine_base_vi", pick("vi", AsrMode.FINAL, AsrTier.BALANCED))
        assertEquals("qwen3", pick("th", AsrMode.FINAL, AsrTier.LIGHT))
    }

    @Test fun onlyTheMainNemotronIsRecommended() {
        assertEquals("nemotron35", pick("ja", AsrMode.LIVE, AsrTier.BEST))
    }

    @Test fun noModelForAnUnknownLanguage() {
        assertNull(pick("xx", AsrMode.FINAL, AsrTier.BALANCED))
    }

    @Test fun defaultLanguageFollowsThePhone() {
        assertEquals("zh-TW", AsrRecommender.defaultLanguage(Locale.TRADITIONAL_CHINESE))
        assertEquals("zh-CN", AsrRecommender.defaultLanguage(Locale.SIMPLIFIED_CHINESE))
        assertEquals("ja", AsrRecommender.defaultLanguage(Locale.JAPAN))
        assertEquals("en", AsrRecommender.defaultLanguage(Locale.US))
    }
}

class AsrDisplayOrderTest {
    @Test fun inUseFirstThenDownloadedThenTheRestKeepingCatalogOrder() {
        val items = listOf("a", "b", "c", "d", "e")
        val sorted = AsrCatalog.sortForDisplay(items, inUse = { it == "d" }, downloaded = { it == "c" || it == "e" || it == "d" })
        assertEquals(listOf("d", "c", "e", "a", "b"), sorted)
    }
}
