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

        val (extractedCount, totalBytes) = ModelZipInstaller.extractZipToDirectory(
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
