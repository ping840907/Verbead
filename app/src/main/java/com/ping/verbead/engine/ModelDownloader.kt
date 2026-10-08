package com.ping.verbead.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Downloads and installs ASR model files over the network. Model files
 * themselves are only ever read on-device afterwards — recognition never
 * makes a network call.
 */
class ModelDownloader(private val context: Context? = null) {

    data class Progress(val label: String, val percent: Int) // percent < 0 = indeterminate

    /** Sum of Content-Length across all remote files, or -1 if unknown. */
    suspend fun estimateTotalBytes(target: ModelDownloadSpec.DownloadTarget): Long =
        withContext(Dispatchers.IO) {
            val urls = target.archiveUrl?.let { listOf(it) } ?: target.files.map { it.url }
            var total = 0L
            for (url in urls) {
                val len = runCatching { headContentLength(url) }.getOrDefault(-1L)
                if (len < 0) return@withContext -1L
                total += len
            }
            total
        }

    suspend fun download(
        target: ModelDownloadSpec.DownloadTarget,
        onProgress: (Progress) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val dir = engineDir(target.engine)
            dir.mkdirs()

            if (target.archiveUrl != null) {
                // Extract into a staging dir first and only swap it into place after it
                // verifies clean. If this download/extraction fails partway, whatever model
                // was already installed (if any) is left completely untouched.
                val stagingDir = File(dir.parentFile, dir.name + "_staging")
                stagingDir.deleteRecursively()
                stagingDir.mkdirs()

                val ctx = context ?: throw IllegalStateException("Context is required for archive download")
                val archiveFile = File(ctx.cacheDir, "model_download.tar.bz2")
                downloadFile(target.archiveUrl, archiveFile, target.archiveSha256) { done, total ->
                    val pct = if (total > 0) ((done * 90) / total).toInt() else -1
                    onProgress(Progress("下載中…", pct))
                }
                extractTarBz2(archiveFile, stagingDir) { fraction, currentFile ->
                    onProgress(Progress("解壓縮中：$currentFile", (fraction * 100).toInt().coerceIn(0, 100)))
                }
                archiveFile.delete()
                if (target.engine == ModelConfig.ENGINE_QWEN3) verifyQwen3Files(stagingDir)

                dir.deleteRecursively()
                if (!stagingDir.renameTo(dir)) throw IOException("Failed to install extracted model into $dir")
            } else {
                val n = target.files.size
                target.files.forEachIndexed { i, f ->
                    downloadFile(f.url, File(dir, f.relativePath), f.sha256) { done, total ->
                        val filePct = if (total > 0) (done * 100 / total).toInt() else 0
                        onProgress(Progress("下載中 (${i + 1}/$n)…", (i * 100 + filePct) / n))
                    }
                }
            }

            // PP-OCRv6 Tiny 與 Small 互斥：下載其一成功時，安全清理並替換另一種舊模型檔案
            if (target.engine == ModelConfig.OCR_MODEL_TINY) {
                context?.let { ModelConfig.deleteOcrModel(it, ModelConfig.OCR_MODEL_SMALL) }
            } else if (target.engine == ModelConfig.OCR_MODEL_SMALL) {
                context?.let { ModelConfig.deleteOcrModel(it, ModelConfig.OCR_MODEL_TINY) }
            }
            Result.success(Unit)
        } catch (ex: Exception) {
            Log.e(TAG, "Download failed: ${ex.message}", ex)
            Result.failure(ex)
        }
    }

    /**
     * Sanity-check that extraction actually produced usable model files. A truncated or
     * corrupt archive (e.g. from the multi-stream bzip2 issue above) can otherwise leave
     * empty/partial files in place that only fail much later — as a native abort when
     * onnxruntime tries to parse them during model load.
     */
    private fun verifyQwen3Files(dir: File) {
        val required = listOf(
            File(dir, ModelConfig.QWEN3_ASR_CONV_FRONTEND),
            File(dir, ModelConfig.QWEN3_ASR_ENCODER),
            File(dir, ModelConfig.QWEN3_ASR_DECODER),
        )
        val broken = required.filter { !it.exists() || it.length() == 0L }
        val tokenizerDir = File(dir, ModelConfig.QWEN3_ASR_TOKENIZER_DIR)
        if (broken.isNotEmpty() || !tokenizerDir.isDirectory || tokenizerDir.listFiles().isNullOrEmpty()) {
            throw IOException(
                "模型檔案不完整或已損毀（${broken.joinToString { it.name }.ifEmpty { "tokenizer/" }}），請重新下載"
            )
        }
    }

    private fun engineDir(engine: String): File {
        val ctx = context ?: throw IllegalStateException("Context is required for model installation")
        return when (engine) {
            ModelConfig.ENGINE_X_ASR -> File(ModelConfig.xAsrDir(ctx))
            ModelConfig.OCR_MODEL_TINY, ModelConfig.OCR_MODEL_SMALL -> File(ModelConfig.ocrDir(ctx))
            else -> File(ModelConfig.qwen3AsrDir(ctx))
        }
    }

    private suspend fun headContentLength(url: String): Long = withContext(Dispatchers.IO) {
        val conn = openConnection(url, "HEAD")
        try {
            if (conn.responseCode !in 200..299) return@withContext -1L
            conn.contentLengthLong
        } finally {
            conn.disconnect()
        }
    }

    internal suspend fun downloadFile(
        url: String,
        dest: File,
        expectedSha256: String? = null,
        onProgress: (done: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (dest.exists() && !expectedSha256.isNullOrBlank()) {
            if (computeSha256(dest).equals(expectedSha256, ignoreCase = true)) {
                val len = dest.length()
                onProgress(len, len)
                return@withContext
            }
        }

        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        val existingBytes = if (tmp.exists()) tmp.length() else 0L

        var conn = openConnection(url, "GET", if (existingBytes > 0) existingBytes else null)
        try {
            var responseCode = conn.responseCode
            if (existingBytes > 0 && responseCode == 416) {
                conn.disconnect()
                if (!expectedSha256.isNullOrBlank() && computeSha256(tmp).equals(expectedSha256, ignoreCase = true)) {
                    if (dest.exists()) dest.delete()
                    if (!tmp.renameTo(dest)) throw IOException("Failed to move downloaded file into place: $dest")
                    onProgress(existingBytes, existingBytes)
                    return@withContext
                }
                tmp.delete()
                conn = openConnection(url, "GET", null)
                responseCode = conn.responseCode
            }

            if (responseCode !in 200..299) {
                throw IOException("HTTP $responseCode for $url")
            }

            val isPartial = (responseCode == HttpURLConnection.HTTP_PARTIAL)
            val appendMode = isPartial && existingBytes > 0

            val totalBytes = if (isPartial) {
                val cr = conn.getHeaderField("Content-Range")
                parseContentRangeTotal(cr).takeIf { it > 0 }
                    ?: (if (conn.contentLengthLong >= 0) existingBytes + conn.contentLengthLong else -1L)
            } else {
                conn.contentLengthLong
            }

            var done = if (appendMode) existingBytes else 0L

            conn.inputStream.use { input ->
                FileOutputStream(tmp, appendMode).use { output ->
                    val buf = ByteArray(COPY_BUFFER_SIZE)
                    while (coroutineContext.isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress(done, totalBytes)
                    }
                }
            }

            if (!coroutineContext.isActive) {
                throw IOException("Download cancelled")
            }

            if (!expectedSha256.isNullOrBlank()) {
                val actualSha256 = computeSha256(tmp)
                if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    tmp.delete()
                    Log.e(TAG, "SHA-256 mismatch for ${dest.name}: expected $expectedSha256, got $actualSha256")
                    throw IOException("檔案校驗失敗（${dest.name} SHA-256 不符），已自動刪除損毀檔案，請重新下載")
                }
            }

            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("Failed to move downloaded file into place: $dest")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Extracts entries into [targetDir], stripping each entry's first path component.
     * [onProgress] receives the fraction (0f..1f) of the *compressed* archive consumed so far,
     * plus the name of the file currently being written. Reported per chunk (not per whole
     * file) so a single very large file (e.g. the encoder) doesn't leave the UI looking frozen
     * for however long that one file takes to decompress — decompression is CPU-bound and can
     * take a long time on-device.
     */
    private fun extractTarBz2(archiveFile: File, targetDir: File, onProgress: (Float, String) -> Unit) {
        val archiveSize = archiveFile.length().coerceAtLeast(1L)
        val counting = CountingInputStream(BufferedInputStream(archiveFile.inputStream(), COPY_BUFFER_SIZE))
        var lastReportedBytes = 0L
        val reportEveryBytes = 128 * 1024L // throttle UI updates to ~every 128KB consumed
        counting.use { fis ->
            // decompressConcatenated=true: some tools (e.g. pbzip2) emit multi-stream bzip2
            // archives. Without this flag, BZip2CompressorInputStream silently stops after the
            // first stream, truncating the tar — files further into the archive (like the
            // decoder) end up corrupt/incomplete even though the download itself succeeded.
            BZip2CompressorInputStream(fis, true).use { bzIn ->
                TarArchiveInputStream(bzIn).use { tarIn ->
                    var entry = tarIn.nextTarEntry
                    while (entry != null) {
                        // Strip the archive's top-level wrapper folder if present;
                        // fall back to the raw name for flat (non-wrapped) archives.
                        val slash = entry.name.indexOf('/')
                        val relPath = if (slash >= 0) entry.name.substring(slash + 1) else entry.name
                        if (relPath.isNotEmpty()) {
                            val outFile = File(targetDir, relPath)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                var copied = 0L
                                FileOutputStream(outFile).use { out ->
                                    val buf = ByteArray(COPY_BUFFER_SIZE)
                                    while (true) {
                                        val n = tarIn.read(buf)
                                        if (n < 0) break
                                        out.write(buf, 0, n)
                                        copied += n
                                        if (counting.bytesRead - lastReportedBytes >= reportEveryBytes) {
                                            lastReportedBytes = counting.bytesRead
                                            onProgress(counting.bytesRead.toFloat() / archiveSize, relPath)
                                        }
                                    }
                                }
                                if (copied != entry.size) {
                                    throw IOException(
                                        "解壓縮失敗：${relPath} 大小不符（預期 ${entry.size}，實際 $copied），封存檔可能已損毀"
                                    )
                                }
                            }
                        }
                        lastReportedBytes = counting.bytesRead
                        onProgress(counting.bytesRead.toFloat() / archiveSize, relPath)
                        entry = tarIn.nextTarEntry
                    }
                }
            }
        }
    }

    /** Wraps a stream and tracks total bytes read through it. */
    private class CountingInputStream(private val delegate: java.io.InputStream) : java.io.InputStream() {
        var bytesRead = 0L
            private set

        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) bytesRead++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(b, off, len)
            if (n > 0) bytesRead += n
            return n
        }

        override fun close() = delegate.close()
    }

    private fun parseContentRangeTotal(contentRange: String?): Long {
        if (contentRange == null) return -1L
        val slashIdx = contentRange.lastIndexOf('/')
        if (slashIdx != -1 && slashIdx + 1 < contentRange.length) {
            return contentRange.substring(slashIdx + 1).trim().toLongOrNull() ?: -1L
        }
        return -1L
    }

    private fun openConnection(url: String, method: String, rangeBytes: Long? = null): HttpURLConnection {
        var currentUrl = url
        var redirects = 0
        while (redirects < 6) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (rangeBytes != null && rangeBytes > 0) {
                conn.setRequestProperty("Range", "bytes=$rangeBytes-")
            }
            conn.connect()
            val code = conn.responseCode
            if (code in listOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, HttpURLConnection.HTTP_SEE_OTHER, 307, 308)) {
                val newUrl = conn.getHeaderField("Location")
                conn.disconnect()
                if (newUrl.isNullOrBlank()) {
                    throw IOException("Redirect without Location header")
                }
                currentUrl = if (newUrl.startsWith("http://") || newUrl.startsWith("https://")) {
                    newUrl
                } else {
                    URL(URL(currentUrl), newUrl).toString()
                }
                redirects++
            } else {
                return conn
            }
        }
        throw IOException("Too many redirects: $url")
    }

    companion object {
        private const val TAG = "ModelDownloader"

        // Larger than the JDK's 8KB default to cut down on read()/write() syscall counts for
        // large model files; still small enough to not matter for memory.
        private const val COPY_BUFFER_SIZE = 256 * 1024

        fun computeSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(COPY_BUFFER_SIZE)
                var n: Int
                while (input.read(buf).also { n = it } > 0) {
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun formatBytes(bytes: Long): String = when {
            bytes < 0             -> "大小未知"
            bytes < 1024 * 1024   -> "%.0f KB".format(bytes / 1024.0)
            else                  -> "%.0f MB".format(bytes / (1024.0 * 1024.0))
        }
    }
}
