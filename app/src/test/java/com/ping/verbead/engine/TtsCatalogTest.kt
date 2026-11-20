package com.ping.verbead.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

class TtsCatalogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun bundledManifestLoadsEveryModel() {
        val models = TtsCatalog.models
        assertTrue(models.size >= 2)
        assertEquals(models.size, models.map { it.id }.toSet().size)
        assertEquals(models.size, models.map { it.dir }.toSet().size)
        for (m in models) {
            assertTrue("${m.id} needs a pinned checksum", !m.archiveSha256.isNullOrBlank())
            assertEquals(64, m.archiveSha256!!.length)
            assertTrue("${m.id} downloads over https", m.archiveUrl.startsWith("https://"))
            assertTrue(m.languages.isNotEmpty())
            assertTrue(m.requiredFiles.contains("model.onnx"))
        }
    }

    @Test
    fun theTwoBundledModelsHaveTheirMeasuredFigures() {
        val melo = TtsCatalog.byId("tts_melo_zh_en")!!
        assertEquals(TtsKind.VITS_MELO, melo.kind)
        assertEquals(1, melo.voices)
        assertEquals(44100, melo.sampleRate)
        val kokoro = TtsCatalog.byId("tts_kokoro_multi")!!
        assertEquals(TtsKind.KOKORO, kokoro.kind)
        assertEquals(103, kokoro.voices)
        assertTrue(kokoro.supportsLanguage("zh-TW"))
        assertFalse(kokoro.supportsLanguage("ja"))
    }

    @Test
    fun systemIsNotACatalogId() {
        assertNull(TtsCatalog.byId(TtsCatalog.SYSTEM))
        val result = TtsCatalog.parse(manifest(id = TtsCatalog.SYSTEM))
        assertTrue(result.models.isEmpty())
        assertEquals(1, result.problems.size)
    }

    @Test
    fun aBrokenEntryIsSkippedWithoutLosingTheOthers() {
        val text = """{"models":[${entry("good")},{"id":"bad","name":"x","kind":"nope"},${entry("good2")}]}"""
        val result = TtsCatalog.parse(text)
        assertEquals(listOf("good", "good2"), result.models.map { it.id })
        assertEquals(1, result.problems.size)
        assertTrue(result.problems[0].startsWith("bad"))
    }

    @Test
    fun unsafeFolderAndFilePathsAreRejected() {
        assertEquals(1, TtsCatalog.parse(manifest(dir = "..")).problems.size)
        assertEquals(1, TtsCatalog.parse(manifest(dir = "a/b")).problems.size)
        assertEquals(1, TtsCatalog.parse(manifest(required = """["../x"]""")).problems.size)
        assertEquals(1, TtsCatalog.parse(manifest(required = """["/etc/passwd"]""")).problems.size)
    }

    @Test
    fun garbageManifestGivesAProblemNotACrash() {
        val result = TtsCatalog.parse("not json")
        assertTrue(result.models.isEmpty())
        assertEquals(1, result.problems.size)
    }

    @Test
    fun installedMeansEveryRequiredFileIsPresentAndNotEmpty() {
        val model = TtsCatalog.parse(manifest(required = """["model.onnx","dict/"]""")).models.single()
        val dir = tmp.newFolder("m")
        assertFalse(model.isInstalled(dir))

        File(dir, "model.onnx").writeText("x")
        assertFalse("the dict folder is still missing", model.isInstalled(dir))

        File(dir, "dict").mkdirs()
        assertFalse("an empty folder does not count", model.isInstalled(dir))

        File(dir, "dict/a.txt").writeText("y")
        assertTrue(model.isInstalled(dir))

        File(dir, "model.onnx").writeText("") // a truncated file
        assertFalse(model.isInstalled(dir))
    }

    @Test
    fun defaultVoiceFollowsTheLanguage() {
        val kokoro = TtsCatalog.byId("tts_kokoro_multi")!!
        assertEquals(3, kokoro.defaultSidFor("zh"))
        assertEquals(0, kokoro.defaultSidFor("en"))
        assertEquals(0, kokoro.defaultSidFor("ja")) // no entry for it: English's
        val melo = TtsCatalog.byId("tts_melo_zh_en")!!
        assertEquals(0, melo.defaultSidFor("zh"))
    }

    @Test
    fun descriptionPicksTheBestLanguage() {
        val m = TtsCatalog.byId("tts_melo_zh_en")!!
        assertTrue(m.descriptionFor(Locale.forLanguageTag("zh-TW")).contains("女聲"))
        assertTrue(m.descriptionFor(Locale.forLanguageTag("zh-HK")).isNotBlank()) // same language, other region
        assertTrue(m.descriptionFor(Locale.forLanguageTag("fr")).startsWith("Light")) // English fallback
    }

    @Test
    fun downloadSpecComesFromTheCatalog() {
        val m = TtsCatalog.byId("tts_kokoro_multi")!!
        val spec = ModelDownloadSpec.forEngine(m.id)
        assertEquals(m.id, spec.engine)
        assertEquals(m.archiveUrl, spec.archiveUrl)
        assertEquals(m.archiveSha256, spec.archiveSha256)
        assertTrue(spec.files.isEmpty())
        assertNotNull(ModelDownloadSpec.forTts(m))
    }

    @Test
    fun speedStepsCycleAndUnknownStartsOver() {
        val steps = ModelConfig.TTS_RATE_STEPS
        var r = steps.first()
        repeat(steps.size) { r = ModelConfig.nextTtsRate(r) }
        assertEquals(steps.first(), r)
        assertEquals(1.25f, ModelConfig.nextTtsRate(1.0f))
        assertEquals(steps.first(), ModelConfig.nextTtsRate(3.3f))
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun entry(
        id: String,
        dir: String = id,
        required: String = """["model.onnx"]""",
    ) = """{"id":"$id","name":"N","kind":"kokoro","dir":"$dir","languages":["en"],"requiredFiles":$required,
        |"archive":{"url":"https://example.com/a.tar.bz2","sha256":"${"0".repeat(64)}"}}""".trimMargin()

    private fun manifest(id: String = "m", dir: String = id, required: String = """["model.onnx"]""") =
        """{"models":[${entry(id, dir, required)}]}"""
}
