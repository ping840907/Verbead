package com.ping.verbead.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelZipInstallerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testNormalizeEntryName() {
        assertEquals("ocr/pp_ocrv6_small_det.onnx", ModelZipInstaller.normalizeEntryName("models/ocr/pp_ocrv6_small_det.onnx"))
        assertEquals("x_asr/encoder.int8.onnx", ModelZipInstaller.normalizeEntryName("/models/x_asr/encoder.int8.onnx"))
        assertEquals("ocr/pp_ocrv6_small_det.onnx", ModelZipInstaller.normalizeEntryName("verbead_models/ocr/pp_ocrv6_small_det.onnx"))
        assertEquals("qwen3_asr/encoder.int8.onnx", ModelZipInstaller.normalizeEntryName("/verbead_models/qwen3_asr/encoder.int8.onnx"))
        assertEquals("", ModelZipInstaller.normalizeEntryName("models/"))
        assertEquals("", ModelZipInstaller.normalizeEntryName("verbead_models/"))
        assertEquals("custom_models/file.bin", ModelZipInstaller.normalizeEntryName("custom_models/file.bin"))

        // Qwen3.5 GGUF normalization (root, nested, case-insensitive)
        assertEquals("slm/Qwen3.5-0.8B-Q4_K_M.gguf", ModelZipInstaller.normalizeEntryName("Qwen3.5-0.8B-Q4_K_M.gguf"))
        assertEquals("slm/Qwen3.5-2B-Q4_K_M.gguf", ModelZipInstaller.normalizeEntryName("models/slm/Qwen3.5-2B-Q4_K_M.gguf"))
        assertEquals("slm/Qwen3.5-4B-Q4_K_M.gguf", ModelZipInstaller.normalizeEntryName("verbead_models/qwen3.5-4b-q4_k_m.gguf"))
        assertEquals("slm/Qwen3.5-9B-Q4_K_M.gguf", ModelZipInstaller.normalizeEntryName("llm/Qwen3.5-9B-Q4_K_M.gguf"))

        // Flat OCR & upstream Qwen3-ASR archive prefix normalization
        assertEquals("ocr/pp_ocrv6_tiny_det.onnx", ModelZipInstaller.normalizeEntryName("pp_ocrv6_tiny_det.onnx"))
        assertEquals("qwen3_asr/vocab.json", ModelZipInstaller.normalizeEntryName("sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/vocab.json"))
    }

    @Test
    fun testInstallValidZipPackage() = runBlocking {
        val outDir = tempFolder.newFolder("target_models")

        // Create in-memory zip
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("verbead_models/x_asr/tokens.txt"))
            zos.write("token1\ntoken2".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("models/ocr/dict.txt"))
            zos.write("char1\nchar2".toByteArray())
            zos.closeEntry()
        }

        val zipBytes = baos.toByteArray()
        val bais = ByteArrayInputStream(zipBytes)

        val (extractedCount, _) = ModelZipInstaller.extractZipToDirectory(
            rawStream = bais,
            totalSize = zipBytes.size.toLong(),
            targetDir = outDir,
            onProgress = { _, _ -> }
        )

        assertEquals(2, extractedCount)
        assertTrue(File(outDir, "x_asr/tokens.txt").exists())
        assertTrue(File(outDir, "ocr/dict.txt").exists())
    }

    @Test
    fun testSmartDetectRestoredModelsWithQwen35AndPartialPackage() = runBlocking {
        val outDir = tempFolder.newFolder("smart_models")

        // Simulate pre-existing Qwen3-ASR on disk that is NOT in the imported ZIP
        val preExistingQwen3 = File(outDir, "qwen3_asr").apply { mkdirs() }
        File(preExistingQwen3, "conv_frontend.onnx").writeBytes(byteArrayOf(1))
        File(preExistingQwen3, "encoder.int8.onnx").writeBytes(byteArrayOf(1))
        File(preExistingQwen3, "decoder.int8.onnx").writeBytes(byteArrayOf(1))
        File(preExistingQwen3, "vocab.json").writeBytes(byteArrayOf(1))

        // Create ZIP containing ONLY X-ASR, PP-OCRv6 Tiny, and Qwen3.5-0.8B + Qwen3.5-2B
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for (name in listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")) {
                zos.putNextEntry(ZipEntry("models/x_asr/$name"))
                zos.write("xasr_dummy".toByteArray())
                zos.closeEntry()
            }
            for (name in listOf("pp_ocrv6_tiny_det.onnx", "pp_ocrv6_tiny_rec.onnx", "pp_ocrv6_tiny_dict.txt")) {
                zos.putNextEntry(ZipEntry(name)) // root level to test smart normalization too
                zos.write("ocr_tiny_dummy".toByteArray())
                zos.closeEntry()
            }
            // Root-level and slm/-level Qwen3.5 GGUF models
            zos.putNextEntry(ZipEntry("Qwen3.5-0.8B-Q4_K_M.gguf"))
            zos.write("gguf_0_8b_payload".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("verbead_models/slm/Qwen3.5-2B-Q4_K_M.gguf"))
            zos.write("gguf_2b_payload".toByteArray())
            zos.closeEntry()
        }

        val zipBytes = baos.toByteArray()
        val report = ModelZipInstaller.extractZipToDirectoryWithReport(
            rawStream = ByteArrayInputStream(zipBytes),
            totalSize = zipBytes.size.toLong(),
            targetDir = outDir
        )

        assertEquals(9, report.fileCount)
        assertTrue(File(outDir, "slm/Qwen3.5-0.8B-Q4_K_M.gguf").exists())
        assertTrue(File(outDir, "slm/Qwen3.5-2B-Q4_K_M.gguf").exists())
        assertTrue(File(outDir, "ocr/pp_ocrv6_tiny_det.onnx").exists())

        val analysis = ModelZipInstaller.analyzeRestoredPackage(report.extractedEntries, outDir)
        // Qwen3-ASR was NOT in the ZIP, so qwen3Restored must be false even though it's on disk
        assertFalse(analysis.qwen3Restored)
        assertTrue(analysis.xAsrRestored)
        assertFalse(analysis.ocrSmallRestored)
        assertTrue(analysis.ocrTinyRestored)
        assertEquals(
            listOf(
                com.ping.verbead.slm.SlmModelConfig.MODEL_QWEN_0_8B,
                com.ping.verbead.slm.SlmModelConfig.MODEL_QWEN_2B
            ),
            analysis.restoredSlmIds
        )
        assertEquals(
            listOf("X-ASR", "PP-OCRv6 Tiny", "Qwen3.5-0.8B", "Qwen3.5-2B"),
            analysis.shortLabels
        )
    }

    @Test
    fun testZipSlipProtection() = runBlocking {
        val outDir = tempFolder.newFolder("safe_dir")
        val evilFile = File(tempFolder.root, "escaped.txt")

        // Create zip with malicious Zip Slip path
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("../../escaped.txt"))
            zos.write("malicious payload".toByteArray())
            zos.closeEntry()
        }

        val zipBytes = baos.toByteArray()
        val bais = ByteArrayInputStream(zipBytes)

        try {
            ModelZipInstaller.extractZipToDirectory(
                rawStream = bais,
                totalSize = zipBytes.size.toLong(),
                targetDir = outDir,
                onProgress = { _, _ -> }
            )
            org.junit.Assert.fail("Zip Slip should throw SecurityException")
        } catch (e: SecurityException) {
            assertTrue("Exception message should indicate Zip Slip", e.message?.contains("Zip Slip") == true)
        }

        assertFalse("Evil file outside destination directory must not be created", evilFile.exists())
    }
}
