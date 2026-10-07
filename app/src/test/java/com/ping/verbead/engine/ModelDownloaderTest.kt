package com.ping.verbead.engine

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

class ModelDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var serverSocket: ServerSocket? = null
    private var serverPort: Int = 0

    @Before
    fun setUp() {
        serverSocket = ServerSocket(0)
        serverPort = serverSocket!!.localPort
    }

    @After
    fun tearDown() {
        serverSocket?.close()
    }

    @Test
    fun testComputeSha256EmptyFile() {
        val emptyFile = tempFolder.newFile("empty.txt")
        val sha256 = ModelDownloader.computeSha256(emptyFile)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256)
    }

    @Test
    fun testComputeSha256KnownString() {
        val file = tempFolder.newFile("hello.txt")
        file.writeBytes("hello world".toByteArray(StandardCharsets.UTF_8))
        val sha256 = ModelDownloader.computeSha256(file)
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", sha256)
    }

    @Test
    fun testModelDownloadSpecHasValidSha256() {
        val xAsrTarget = ModelDownloadSpec.xAsr()
        assertTrue(xAsrTarget.files.isNotEmpty())
        for (f in xAsrTarget.files) {
            assertTrue("File ${f.relativePath} must have sha256", !f.sha256.isNullOrBlank())
            assertEquals(64, f.sha256!!.length)
        }

        val ocrSmall = ModelDownloadSpec.ocr(ModelConfig.OCR_MODEL_SMALL)
        for (f in ocrSmall.files) {
            assertTrue("OCR small ${f.relativePath} must have sha256", !f.sha256.isNullOrBlank())
            assertEquals(64, f.sha256!!.length)
        }

        val ocrTiny = ModelDownloadSpec.ocr(ModelConfig.OCR_MODEL_TINY)
        for (f in ocrTiny.files) {
            assertTrue("OCR tiny ${f.relativePath} must have sha256", !f.sha256.isNullOrBlank())
            assertEquals(64, f.sha256!!.length)
        }

        val qwen3Target = ModelDownloadSpec.qwen3()
        assertTrue("Qwen3 archiveSha256 must not be null or blank", !qwen3Target.archiveSha256.isNullOrBlank())
        assertEquals(64, qwen3Target.archiveSha256!!.length)
        assertTrue("Qwen3 archiveSha256 must be valid hex", qwen3Target.archiveSha256!!.matches(Regex("^[0-9a-fA-F]{64}$")))
        assertEquals("393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96", qwen3Target.archiveSha256)
    }

    @Test
    fun testDownloadWithValidSha256Succeeds() = runBlocking {
        val payload = "0123456789abcdefghijklmnopqrstuvwxyz".repeat(100).toByteArray()
        val expectedSha = ModelDownloader.computeSha256(tempFolder.newFile("sample.bin").apply { writeBytes(payload) })

        thread {
            try {
                val client = serverSocket?.accept() ?: return@thread
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                while (!reader.readLine().isNullOrEmpty()) {}
                val out = client.getOutputStream()
                val header = "HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
                out.write(payload)
                out.flush()
                client.close()
            } catch (_: Exception) {}
        }

        val tempDir = tempFolder.newFolder("out")
        val dest = File(tempDir, "model.bin")
        val downloader = ModelDownloader()

        downloader.downloadFile("http://127.0.0.1:$serverPort/model", dest, expectedSha) { _, _ -> }

        assertTrue("Destination file must exist", dest.exists())
        assertEquals(payload.size.toLong(), dest.length())
        assertFalse(".part file must not exist after success", File(tempDir, "model.bin.part").exists())
    }

    @Test
    fun testDownloadWithMismatchedSha256DeletesPartAndThrows() = runBlocking {
        val payload = "corrupted data".toByteArray()
        val wrongSha = "0000000000000000000000000000000000000000000000000000000000000000"

        thread {
            try {
                val client = serverSocket?.accept() ?: return@thread
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                while (!reader.readLine().isNullOrEmpty()) {}
                val out = client.getOutputStream()
                val header = "HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
                out.write(payload)
                out.flush()
                client.close()
            } catch (_: Exception) {}
        }

        val tempDir = tempFolder.newFolder("out_corrupt")
        val dest = File(tempDir, "corrupt.bin")
        val part = File(tempDir, "corrupt.bin.part")
        val downloader = ModelDownloader()

        try {
            downloader.downloadFile("http://127.0.0.1:$serverPort/corrupt", dest, wrongSha) { _, _ -> }
            fail("Should have thrown IOException on SHA-256 mismatch")
        } catch (e: IOException) {
            assertTrue("Exception message should indicate verification failure", e.message?.contains("檔案校驗失敗") == true)
        }

        assertFalse("Destination file must not exist on failure", dest.exists())
        assertFalse(".part file must be automatically deleted on failure", part.exists())
    }

    @Test
    fun testHttpRangeResumeSupport() = runBlocking {
        val fullData = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toByteArray()
        val expectedSha = ModelDownloader.computeSha256(tempFolder.newFile("full.txt").apply { writeBytes(fullData) })

        thread {
            try {
                val client = serverSocket?.accept() ?: return@thread
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                var line = reader.readLine()
                var rangeHeader: String? = null
                while (!line.isNullOrEmpty()) {
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = line.substring(6).trim()
                    }
                    line = reader.readLine()
                }
                val out = client.getOutputStream()
                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    val start = rangeHeader.removePrefix("bytes=").removeSuffix("-").toInt()
                    val chunk = fullData.copyOfRange(start, fullData.size)
                    val header = "HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Range: bytes $start-${fullData.size - 1}/${fullData.size}\r\n" +
                        "Content-Length: ${chunk.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
                    out.write(chunk)
                } else {
                    val header = "HTTP/1.1 200 OK\r\nContent-Length: ${fullData.size}\r\nConnection: close\r\n\r\n"
                    out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
                    out.write(fullData)
                }
                out.flush()
                client.close()
            } catch (_: Exception) {}
        }

        val tempDir = tempFolder.newFolder("out_resume")
        val dest = File(tempDir, "resumed.bin")
        val part = File(tempDir, "resumed.bin.part")

        // Simulate half-downloaded file in .part
        part.writeBytes(fullData.copyOfRange(0, 20))
        assertEquals(20L, part.length())

        val downloader = ModelDownloader()
        downloader.downloadFile("http://127.0.0.1:$serverPort/range", dest, expectedSha) { _, _ -> }

        assertTrue(dest.exists())
        assertEquals(fullData.size.toLong(), dest.length())
        assertEquals(expectedSha, ModelDownloader.computeSha256(dest))
        assertFalse(part.exists())
    }
}
