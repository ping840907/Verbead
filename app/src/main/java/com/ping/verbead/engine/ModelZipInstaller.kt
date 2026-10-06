package com.ping.verbead.engine

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Utility for restoring offline AI models (ASR & OCR) from a single ZIP package.
 * Supports loading from SAF content Uri or direct local file, with progress tracking,
 * Zip Slip security protection, and automatic model readiness activation.
 */
object ModelZipInstaller {

    const val DEFAULT_ZIP_FILENAME = "verbead_models.zip"

    data class InstallResult(
        val isSuccess: Boolean,
        val xAsrReady: Boolean,
        val qwen3Ready: Boolean,
        val ocrReady: Boolean,
        val fileCount: Int,
        val totalBytes: Long,
        val message: String
    )

    /**
     * Checks if default preloaded package exists in App private directories.
     */
    fun findDefaultZipPackage(context: Context? = null): File? {
        if (context == null) return null
        val candidates = mutableListOf<File>()
        context.getExternalFilesDir(null)?.let {
            candidates.add(File(it, DEFAULT_ZIP_FILENAME))
        }
        candidates.add(File(context.filesDir, DEFAULT_ZIP_FILENAME))
        candidates.add(File(context.cacheDir, DEFAULT_ZIP_FILENAME))
        context.getExternalFilesDir("models")?.parentFile?.let {
            candidates.add(File(it, DEFAULT_ZIP_FILENAME))
        }

        for (c in candidates) {
            val exists = try { c.exists() } catch (_: Exception) { false }
            val canRead = try { c.canRead() } catch (_: Exception) { false }
            android.util.Log.d("Verbead_Zip", "Candidate: ${c.absolutePath}, exists=$exists, canRead=$canRead")
            if (exists && canRead) return c
        }
        return null
    }

    /**
     * Installs models from a content Uri (e.g., from SAF OpenDocument picker).
     */
    suspend fun installFromUri(
        context: Context,
        uri: Uri,
        onProgress: suspend (currentFile: String, percent: Int) -> Unit
    ): InstallResult = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver
        var totalSize: Long = -1L
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1) {
                        totalSize = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (_: Exception) {}

        val inputStream = contentResolver.openInputStream(uri)
            ?: return@withContext InstallResult(
                isSuccess = false,
                xAsrReady = false,
                qwen3Ready = false,
                ocrReady = false,
                fileCount = 0,
                totalBytes = 0,
                message = "無法讀取選取的檔案 (InputStream 為空)"
            )

        installFromInputStream(context, inputStream, totalSize, onProgress)
    }

    /**
     * Installs models from a local File.
     */
    suspend fun installFromFile(
        context: Context,
        file: File,
        onProgress: suspend (currentFile: String, percent: Int) -> Unit
    ): InstallResult = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.canRead()) {
            return@withContext InstallResult(
                isSuccess = false,
                xAsrReady = false,
                qwen3Ready = false,
                ocrReady = false,
                fileCount = 0,
                totalBytes = 0,
                message = "找不到模型壓縮包或無讀取權限: ${file.absolutePath}"
            )
        }
        val totalSize = file.length()
        val inputStream = FileInputStream(file)
        installFromInputStream(context, inputStream, totalSize, onProgress)
    }

    fun normalizeEntryName(raw: String): String {
        var entryName = raw.replace('\\', '/').trim()
        while (entryName.startsWith("/")) {
            entryName = entryName.substring(1)
        }
        if (entryName.startsWith("models/")) {
            entryName = entryName.removePrefix("models/")
        } else if (entryName.startsWith("verbead_models/")) {
            entryName = entryName.removePrefix("verbead_models/")
        }
        return entryName
    }

    private suspend fun installFromInputStream(
        context: Context,
        rawStream: InputStream,
        totalSize: Long,
        onProgress: suspend (currentFile: String, percent: Int) -> Unit
    ): InstallResult {
        val targetDir = ModelConfig.getPrimaryModelsDir(context)
        android.util.Log.d("Verbead_Zip", "installFromInputStream: targetDir=$targetDir, totalSize=$totalSize")
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        var extractedCount = 0
        var totalBytesExtracted = 0L

        try {
            val progressStream = ProgressInputStream(rawStream, totalSize) { _, _ -> }

            ZipInputStream(BufferedInputStream(progressStream, 64 * 1024)).use { zis ->
                val buffer = ByteArray(64 * 1024)
                var entry = zis.nextEntry

                while (entry != null) {
                    val rawName = entry.name.replace('\\', '/').trim()
                    // Skip macOS or metadata junk
                    if (rawName.startsWith("__MACOSX/") || rawName.endsWith(".DS_Store")) {
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }

                    val entryName = normalizeEntryName(rawName)
                    if (entryName.isEmpty()) {
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }

                    val outFile = File(targetDir, entryName)
                    // Security: Zip Slip check
                    val canonicalDest = outFile.canonicalPath
                    val canonicalTarget = targetDir.canonicalPath
                    if (!canonicalDest.startsWith(canonicalTarget + File.separator) && canonicalDest != canonicalTarget) {
                        throw SecurityException("偵測到不合法的壓縮檔案路徑 (Zip Slip): $entryName")
                    }

                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        val currentFileName = outFile.name
                        val currentPct = if (totalSize > 0) {
                            ((progressStream.bytesRead * 100) / totalSize).toInt().coerceIn(0, 100)
                        } else 0
                        onProgress(currentFileName, currentPct)

                        val tempFile = File(outFile.parentFile, "${outFile.name}.tmp")
                        FileOutputStream(tempFile).use { fos ->
                            BufferedOutputStream(fos, 64 * 1024).use { bos ->
                                var len: Int
                                while (zis.read(buffer).also { len = it } > 0) {
                                    bos.write(buffer, 0, len)
                                    totalBytesExtracted += len
                                }
                                bos.flush()
                            }
                        }

                        if (outFile.exists()) {
                            outFile.delete()
                        }
                        if (!tempFile.renameTo(outFile)) {
                            tempFile.copyTo(outFile, overwrite = true)
                            tempFile.delete()
                        }
                        extractedCount++

                        val updatedPct = if (totalSize > 0) {
                            ((progressStream.bytesRead * 100) / totalSize).toInt().coerceIn(0, 100)
                        } else 0
                        onProgress(currentFileName, updatedPct)
                    }

                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            onProgress("復原完成", 100)

            // Validate and activate newly restored models
            val xAsrReady = ModelConfig.isXAsrReady(context)
            val qwen3Ready = ModelConfig.isQwen3Ready(context)
            val ocrSmallReady = ModelConfig.isOcrReady(context, ModelConfig.ENGINE_PP_OCR_SMALL)
            val ocrTinyReady = ModelConfig.isOcrReady(context, ModelConfig.ENGINE_PP_OCR_TINY)
            val ocrReady = ocrSmallReady || ocrTinyReady

            if (ocrSmallReady) {
                ModelConfig.setSelectedOcrModel(context, ModelConfig.ENGINE_PP_OCR_SMALL)
            } else if (ocrTinyReady) {
                ModelConfig.setSelectedOcrModel(context, ModelConfig.ENGINE_PP_OCR_TINY)
            }

            val readySummary = buildList {
                if (qwen3Ready) add("Qwen3-ASR")
                if (xAsrReady) add("X-ASR")
                if (ocrReady) add("PP-OCRv6")
            }.joinToString(", ")

            return InstallResult(
                isSuccess = extractedCount > 0,
                xAsrReady = xAsrReady,
                qwen3Ready = qwen3Ready,
                ocrReady = ocrReady,
                fileCount = extractedCount,
                totalBytes = totalBytesExtracted,
                message = if (extractedCount > 0) "模型包復原成功！就緒模型：$readySummary" else "壓縮包中未包含有效模型檔案"
            )

        } catch (ex: Exception) {
            return InstallResult(
                isSuccess = false,
                xAsrReady = ModelConfig.isXAsrReady(context),
                qwen3Ready = ModelConfig.isQwen3Ready(context),
                ocrReady = ModelConfig.isOcrReady(context),
                fileCount = extractedCount,
                totalBytes = totalBytesExtracted,
                message = "解壓失敗: ${ex.message ?: ex.javaClass.simpleName}"
            )
        }
    }

    private class ProgressInputStream(
        stream: InputStream,
        private val totalBytes: Long,
        private val onBytesRead: (bytesRead: Long, percent: Int) -> Unit
    ) : FilterInputStream(stream) {
        var bytesRead: Long = 0L
            private set
        private var lastReportedPercent = -1

        override fun read(): Int {
            val b = super.read()
            if (b != -1) {
                bytesRead++
                checkReport()
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = super.read(b, off, len)
            if (count > 0) {
                bytesRead += count
                checkReport()
            }
            return count
        }

        private fun checkReport() {
            if (totalBytes > 0) {
                val pct = ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100)
                if (pct != lastReportedPercent) {
                    lastReportedPercent = pct
                    onBytesRead(bytesRead, pct)
                }
            }
        }
    }
}
