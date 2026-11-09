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

class ModelConfigOcrTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun writeModel(dir: File, model: String, skip: String? = null) {
        for (name in ModelConfig.requiredOcrFiles(model)) {
            if (name != skip) File(dir, name).writeText("fake_$name")
        }
    }

    @Test
    fun testRequiredFilesCoverEveryDetectorSizeAndRecognizerWidth() {
        val small = ModelConfig.requiredOcrFiles(ModelConfig.OCR_MODEL_SMALL)
        assertTrue("pp_ocrv6_small_det_640x640.tflite" in small)
        assertTrue("pp_ocrv6_small_det_480x960.tflite" in small)
        assertTrue("pp_ocrv6_small_det_960x480.tflite" in small)
        assertTrue("pp_ocrv6_small_rec_160.tflite" in small)
        assertTrue("pp_ocrv6_small_rec_1280.tflite" in small)
        assertTrue("pp_ocrv6_small_dict.txt" in small)
        assertEquals(3 + 4 + 1, small.size)

        val tiny = ModelConfig.requiredOcrFiles(ModelConfig.OCR_MODEL_TINY)
        assertTrue("pp_ocrv6_tiny_rec_64.tflite" in tiny)
        assertEquals(3 + 16 + 1, tiny.size)
        assertTrue(tiny.none { it.contains("small") })
    }

    @Test
    fun testEveryRequiredFileHasADownloadChecksum() {
        val requiredByModel = listOf(ModelConfig.OCR_MODEL_SMALL, ModelConfig.OCR_MODEL_TINY).map { ModelConfig.requiredOcrFiles(it) } +
            listOf(ModelConfig.requiredOcrKoreanFiles())
        for (files in requiredByModel) {
            for (name in files) {
                val sum = OcrModelManifest.sha256[name]
                assertNotNull("no checksum for $name", sum)
                assertTrue("bad checksum for $name", sum!!.matches(Regex("^[0-9a-f]{64}$")))
            }
        }
        // and the manifest lists nothing else
        val all = requiredByModel.flatten().toSet()
        assertEquals(all, OcrModelManifest.sha256.keys)
    }

    @Test
    fun testDownloadTargetPointsAtTheReleaseAssets() {
        val target = ModelDownloadSpec.ocr(ModelConfig.OCR_MODEL_SMALL)
        assertEquals(ModelConfig.requiredOcrFiles(ModelConfig.OCR_MODEL_SMALL), target.files.map { it.relativePath })
        for (f in target.files) {
            assertEquals(OcrModelManifest.BASE_URL + f.relativePath, f.url)
            assertEquals(OcrModelManifest.sha256[f.relativePath], f.sha256)
        }
    }

    @Test
    fun testFindSpecificOcrPathsStrictSeparation() {
        val dir = tempFolder.newFolder("ocr_models")
        val dirs = listOf(dir)

        // Case 1: Only Small model files exist
        writeModel(dir, ModelConfig.OCR_MODEL_SMALL)

        val foundSmall = ModelConfig.findSpecificOcrPathsInDirs(dirs, ModelConfig.OCR_MODEL_SMALL)
        assertNotNull("Small model should be found", foundSmall)
        assertEquals(dir.absolutePath, foundSmall!!.dir)
        assertEquals(File(dir, "pp_ocrv6_small_dict.txt").absolutePath, foundSmall.dictPath)
        assertEquals(ModelConfig.OCR_MODEL_SMALL, foundSmall.model)

        // Tiny MUST NOT be found even though Small exists (strict separation, no cross-fallback)
        assertNull("Tiny must NOT be found when only Small is present",
            ModelConfig.findSpecificOcrPathsInDirs(dirs, ModelConfig.OCR_MODEL_TINY))

        // Case 2: Now add Tiny files
        writeModel(dir, ModelConfig.OCR_MODEL_TINY)
        val foundTiny = ModelConfig.findSpecificOcrPathsInDirs(dirs, ModelConfig.OCR_MODEL_TINY)
        assertNotNull("Tiny model should now be found", foundTiny)
        assertEquals(ModelConfig.OCR_MODEL_TINY, foundTiny!!.model)
    }

    @Test
    fun testIncompleteModelIsNotReady() {
        val dir = tempFolder.newFolder("ocr_incomplete")
        writeModel(dir, ModelConfig.OCR_MODEL_SMALL, skip = "pp_ocrv6_small_rec_640.tflite")
        assertNull("one missing recognizer width means the model is incomplete",
            ModelConfig.findSpecificOcrPathsInDirs(listOf(dir), ModelConfig.OCR_MODEL_SMALL))

        // an empty (failed) download of one file must not count either
        File(dir, "pp_ocrv6_small_rec_640.tflite").writeText("")
        assertNull(ModelConfig.findSpecificOcrPathsInDirs(listOf(dir), ModelConfig.OCR_MODEL_SMALL))

        File(dir, "pp_ocrv6_small_rec_640.tflite").writeText("ok")
        assertNotNull(ModelConfig.findSpecificOcrPathsInDirs(listOf(dir), ModelConfig.OCR_MODEL_SMALL))
    }

    @Test
    fun testDeleteOcrFilesFromDirMutualExclusion() {
        val dir = tempFolder.newFolder("ocr_replace_test")
        writeModel(dir, ModelConfig.OCR_MODEL_SMALL)
        writeModel(dir, ModelConfig.OCR_MODEL_TINY)
        val smallPart = File(dir, "pp_ocrv6_small_rec_320.tflite.part").apply { writeText("small_part") }
        val oldOnnx = File(dir, "pp_ocrv6_small_det.onnx").apply { writeText("old") }

        // Delete Small model files to replace with Tiny
        ModelConfig.deleteOcrFilesFromDir(dir, ModelConfig.OCR_MODEL_SMALL)

        for (name in ModelConfig.requiredOcrFiles(ModelConfig.OCR_MODEL_SMALL)) {
            assertFalse("$name should be deleted", File(dir, name).exists())
        }
        assertFalse("Small partial download should be deleted", smallPart.exists())
        assertFalse("Old ONNX file should be deleted", oldOnnx.exists())
        for (name in ModelConfig.requiredOcrFiles(ModelConfig.OCR_MODEL_TINY)) {
            assertTrue("$name must be preserved", File(dir, name).exists())
        }

        // Now delete Tiny model files to replace with Small
        ModelConfig.deleteOcrFilesFromDir(dir, ModelConfig.OCR_MODEL_TINY)
        for (name in ModelConfig.requiredOcrFiles(ModelConfig.OCR_MODEL_TINY)) {
            assertFalse("$name should be deleted", File(dir, name).exists())
        }
    }
}
