package com.ping.verbead.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelConfigOcrTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testFindSpecificOcrPathsStrictSeparation() {
        val dir = tempFolder.newFolder("ocr_models")
        val dirs = listOf(dir)

        // Case 1: Only Small model files exist
        val smallDet = File(dir, "pp_ocrv6_small_det.onnx").apply { writeText("fake_det") }
        val smallRec = File(dir, "pp_ocrv6_small_rec.onnx").apply { writeText("fake_rec") }
        val smallDict = File(dir, "pp_ocrv6_small_dict.txt").apply { writeText("fake_dict") }

        // Small should be found
        val foundSmall = ModelConfig.findSpecificOcrPathsInDirs(dirs, ModelConfig.OCR_MODEL_SMALL)
        assertNotNull("Small model should be found", foundSmall)
        assertEquals(smallDet.absolutePath, foundSmall!!.detPath)
        assertEquals(smallRec.absolutePath, foundSmall.recPath)
        assertEquals(ModelConfig.OCR_MODEL_SMALL, foundSmall.model)

        // Tiny MUST NOT be found even though Small exists (strict separation, no cross-fallback)
        val foundTiny = ModelConfig.findSpecificOcrPathsInDirs(dirs, ModelConfig.OCR_MODEL_TINY)
        assertNull("Tiny must NOT be found when only Small is present", foundTiny)

        // Case 2: Now add Tiny files
        val tinyDet = File(dir, "pp_ocrv6_tiny_det.onnx").apply { writeText("fake_tiny_det") }
        val tinyRec = File(dir, "pp_ocrv6_tiny_rec.onnx").apply { writeText("fake_tiny_rec") }
        val tinyDict = File(dir, "pp_ocrv6_tiny_dict.txt").apply { writeText("fake_tiny_dict") }

        val foundTiny2 = ModelConfig.findSpecificOcrPathsInDirs(dirs, ModelConfig.OCR_MODEL_TINY)
        assertNotNull("Tiny model should now be found", foundTiny2)
        assertEquals(tinyDet.absolutePath, foundTiny2!!.detPath)
        assertEquals(tinyRec.absolutePath, foundTiny2.recPath)
        assertEquals(ModelConfig.OCR_MODEL_TINY, foundTiny2.model)
    }

    @Test
    fun testDeleteOcrFilesFromDirMutualExclusion() {
        val dir = tempFolder.newFolder("ocr_replace_test")

        val smallDet = File(dir, "pp_ocrv6_small_det.onnx").apply { writeText("small_det") }
        val smallRec = File(dir, "pp_ocrv6_small_rec.onnx").apply { writeText("small_rec") }
        val smallDict = File(dir, "pp_ocrv6_small_dict.txt").apply { writeText("small_dict") }
        val smallPart = File(dir, "pp_ocrv6_small_det.onnx.part").apply { writeText("small_part") }

        val tinyDet = File(dir, "pp_ocrv6_tiny_det.onnx").apply { writeText("tiny_det") }
        val tinyRec = File(dir, "pp_ocrv6_tiny_rec.onnx").apply { writeText("tiny_rec") }
        val tinyDict = File(dir, "pp_ocrv6_tiny_dict.txt").apply { writeText("tiny_dict") }

        // Delete Small model files to replace with Tiny
        ModelConfig.deleteOcrFilesFromDir(dir, ModelConfig.OCR_MODEL_SMALL)

        assertFalse("Small det should be deleted", smallDet.exists())
        assertFalse("Small rec should be deleted", smallRec.exists())
        assertFalse("Small dict should be deleted", smallDict.exists())
        assertFalse("Small part should be deleted", smallPart.exists())

        assertTrue("Tiny det must be preserved", tinyDet.exists())
        assertTrue("Tiny rec must be preserved", tinyRec.exists())
        assertTrue("Tiny dict must be preserved", tinyDict.exists())

        // Now delete Tiny model files to replace with Small
        ModelConfig.deleteOcrFilesFromDir(dir, ModelConfig.OCR_MODEL_TINY)

        assertFalse("Tiny det should be deleted", tinyDet.exists())
        assertFalse("Tiny rec should be deleted", tinyRec.exists())
        assertFalse("Tiny dict should be deleted", tinyDict.exists())
    }
}
