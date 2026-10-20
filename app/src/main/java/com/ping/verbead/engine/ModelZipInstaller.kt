package com.ping.verbead.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
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

    private const val TAG = "Verbead_Zip"
    const val DEFAULT_ZIP_FILENAME = "verbead_models.zip"

    data class InstallResult(
        val isSuccess: Boolean,
        val xAsrReady: Boolean,
        val qwen3Ready: Boolean,
        val ocrReady: Boolean,
        val slmReady: Boolean = false,
        val restoredModels: List<String> = emptyList(),
        val restoredModelShortNames: List<String> = emptyList(),
        val restoredSlmModels: List<String> = emptyList(),
        val fileCount: Int,
        val totalBytes: Long,
        val message: String
    )

    data class ExtractionReport(
        val fileCount: Int,
        val totalBytes: Long,
        val extractedEntries: List<String>
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
            val exists = try { c.exists() } catch (e: Exception) { Log.w(TAG, "exists check failed for ${c.absolutePath}", e); false }
            val canRead = try { c.canRead() } catch (e: Exception) { Log.w(TAG, "canRead check failed for ${c.absolutePath}", e); false }
            Log.d(TAG, "Candidate: ${c.absolutePath}, exists=$exists, canRead=$canRead")
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
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query size for uri $uri", e)
        }

        val inputStream = contentResolver.openInputStream(uri)
            ?: return@withContext InstallResult(
                isSuccess = false,
                xAsrReady = false,
                qwen3Ready = false,
                ocrReady = false,
                slmReady = false,
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
                slmReady = false,
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
        if (entryName.isEmpty() || entryName.endsWith("/")) {
            return entryName
        }

        val fileName = entryName.substringAfterLast('/')
        // Smartly map known Qwen3.5 GGUF models (or root .gguf files) into slm/ directory
        val matchedSlm = com.ping.verbead.slm.SlmModelConfig.AVAILABLE_MODELS.firstOrNull {
            it.fileName.equals(fileName, ignoreCase = true)
        }
        if (matchedSlm != null) {
            return "${com.ping.verbead.slm.SlmModelConfig.SLM_DIR}/${matchedSlm.fileName}"
        }
        if (fileName.endsWith(".gguf", ignoreCase = true) && !entryName.contains('/')) {
            return "${com.ping.verbead.slm.SlmModelConfig.SLM_DIR}/$fileName"
        }

        // Smartly map flat PP-OCRv6 files into ocr/ directory
        if (!entryName.contains('/') && fileName.startsWith("pp_ocrv6_", ignoreCase = true)) {
            return "ocr/$fileName"
        }

        // Smartly map upstream sherpa-onnx-qwen3-asr folder prefix into qwen3_asr/
        if (entryName.startsWith("sherpa-onnx-qwen3-asr") && entryName.contains('/')) {
            val rest = entryName.substringAfter('/')
            if (rest.isNotEmpty()) {
                return "qwen3_asr/$rest"
            }
        }

        return entryName
    }

    /**
     * Analyzes the normalized paths extracted from the ZIP package alongside the target directory
     * to determine which specific models were contained in and restored by the imported package.
     */
    fun analyzeRestoredPackage(
        extractedEntries: Collection<String>,
        targetDir: File
    ): RestoredPackageAnalysis {
        val hasQwen3Entry = extractedEntries.any { it.startsWith("qwen3_asr/") }
        val hasXAsrEntry = extractedEntries.any { it.startsWith("x_asr/") }
        val hasOcrSmallEntry = extractedEntries.any { it.startsWith("ocr/pp_ocrv6_small_") }
        val hasOcrTinyEntry = extractedEntries.any { it.startsWith("ocr/pp_ocrv6_tiny_") }

        val qwen3Dir = File(targetDir, "qwen3_asr")
        val qwen3Restored = hasQwen3Entry &&
            File(qwen3Dir, "conv_frontend.onnx").exists() &&
            File(qwen3Dir, "encoder.int8.onnx").exists() &&
            File(qwen3Dir, "decoder.int8.onnx").exists() &&
            File(qwen3Dir, "vocab.json").exists()

        val xAsrDir = File(targetDir, "x_asr")
        val xAsrRestored = hasXAsrEntry &&
            File(xAsrDir, "encoder.int8.onnx").exists() &&
            File(xAsrDir, "decoder.onnx").exists() &&
            File(xAsrDir, "joiner.int8.onnx").exists() &&
            File(xAsrDir, "tokens.txt").exists()

        val ocrDir = File(targetDir, "ocr")
        val ocrSmallRestored = hasOcrSmallEntry &&
            File(ocrDir, "pp_ocrv6_small_det.onnx").exists() &&
            File(ocrDir, "pp_ocrv6_small_rec.onnx").exists() &&
            File(ocrDir, "pp_ocrv6_small_dict.txt").exists()

        val ocrTinyRestored = hasOcrTinyEntry &&
            File(ocrDir, "pp_ocrv6_tiny_det.onnx").exists() &&
            File(ocrDir, "pp_ocrv6_tiny_rec.onnx").exists() &&
            File(ocrDir, "pp_ocrv6_tiny_dict.txt").exists()

        val slmDir = File(targetDir, com.ping.verbead.slm.SlmModelConfig.SLM_DIR)
        val restoredSlmIds = mutableListOf<String>()
        for (meta in com.ping.verbead.slm.SlmModelConfig.AVAILABLE_MODELS) {
            val expectedEntry = "${com.ping.verbead.slm.SlmModelConfig.SLM_DIR}/${meta.fileName}"
            val inZip = extractedEntries.any { it.equals(expectedEntry, ignoreCase = true) }
            val fileOnDisk = File(slmDir, meta.fileName)
            if (inZip && fileOnDisk.exists() && fileOnDisk.length() > 0L) {
                restoredSlmIds.add(meta.id)
            }
        }

        val detailedLabels = mutableListOf<String>()
        val shortLabels = mutableListOf<String>()

        if (qwen3Restored) {
            detailedLabels.add("Qwen3-ASR 高精度離線語音")
            shortLabels.add("Qwen3-ASR")
        }
        if (xAsrRestored) {
            detailedLabels.add("X-ASR 極速即時串流語音")
            shortLabels.add("X-ASR")
        }
        if (ocrSmallRestored) {
            detailedLabels.add("PP-OCRv6 (Small 高精版) 文字辨識")
            shortLabels.add("PP-OCRv6 Small")
        }
        if (ocrTinyRestored) {
            detailedLabels.add("PP-OCRv6 (Tiny 輕量版) 文字辨識")
            shortLabels.add("PP-OCRv6 Tiny")
        }
        for (slmId in restoredSlmIds) {
            val meta = com.ping.verbead.slm.SlmModelConfig.getModelMetadata(slmId)
            detailedLabels.add("${meta.displayName} (${meta.chipLabel}) 端側語言模型")
            shortLabels.add(meta.displayName)
        }

        return RestoredPackageAnalysis(
            qwen3Restored = qwen3Restored,
            xAsrRestored = xAsrRestored,
            ocrSmallRestored = ocrSmallRestored,
            ocrTinyRestored = ocrTinyRestored,
            restoredSlmIds = restoredSlmIds,
            detailedLabels = detailedLabels,
            shortLabels = shortLabels
        )
    }

    data class RestoredPackageAnalysis(
        val qwen3Restored: Boolean,
        val xAsrRestored: Boolean,
        val ocrSmallRestored: Boolean,
        val ocrTinyRestored: Boolean,
        val restoredSlmIds: List<String>,
        val detailedLabels: List<String>,
        val shortLabels: List<String>
    )

    private suspend fun installFromInputStream(
        context: Context,
        rawStream: InputStream,
        totalSize: Long,
        onProgress: suspend (currentFile: String, percent: Int) -> Unit
    ): InstallResult {
        val targetDir = ModelConfig.getPrimaryModelsDir(context)
        Log.d(TAG, "installFromInputStream: targetDir=$targetDir, totalSize=$totalSize")
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val report = try {
            extractZipToDirectoryWithReport(rawStream, totalSize, targetDir, onProgress)
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed: ${e.message}", e)
            return InstallResult(
                isSuccess = false,
                xAsrReady = false,
                qwen3Ready = false,
                ocrReady = false,
                slmReady = false,
                fileCount = 0,
                totalBytes = 0,
                message = "解壓縮失敗: ${e.message}"
            )
        }

        onProgress("復原完成", 100)

        val analysis = analyzeRestoredPackage(report.extractedEntries, targetDir)

        // Smartly activate newly restored models based on what was inside the imported package
        if (analysis.qwen3Restored) {
            ModelConfig.setSelectedEngine(context, ModelConfig.ENGINE_QWEN3)
        } else if (analysis.xAsrRestored && !ModelConfig.isQwen3Ready(context)) {
            ModelConfig.setSelectedEngine(context, ModelConfig.ENGINE_X_ASR)
        }

        if (analysis.ocrSmallRestored) {
            ModelConfig.setSelectedOcrModel(context, ModelConfig.ENGINE_PP_OCR_SMALL)
        } else if (analysis.ocrTinyRestored) {
            ModelConfig.setSelectedOcrModel(context, ModelConfig.ENGINE_PP_OCR_TINY)
        }

        if (analysis.restoredSlmIds.isNotEmpty()) {
            val currentSlm = com.ping.verbead.slm.SlmModelConfig.getSelectedModel(context)
            val chosenSlm = if (currentSlm in analysis.restoredSlmIds) {
                currentSlm
            } else {
                analysis.restoredSlmIds.first()
            }
            com.ping.verbead.slm.SlmModelConfig.setSelectedModel(context, chosenSlm)
        }

        // Overall readiness across disk
        val xAsrReady = ModelConfig.isXAsrReady(context)
        val qwen3Ready = ModelConfig.isQwen3Ready(context)
        val ocrSmallReady = ModelConfig.isOcrSmallReady(context)
        val ocrTinyReady = ModelConfig.isOcrTinyReady(context)
        val ocrReady = ocrSmallReady || ocrTinyReady
        val slmReady = com.ping.verbead.slm.SlmModelConfig.resolveAvailableModelId(context) != null

        val readyShortNames = if (analysis.shortLabels.isNotEmpty()) {
            analysis.shortLabels
        } else {
            buildList {
                if (qwen3Ready) add("Qwen3-ASR")
                if (xAsrReady) add("X-ASR")
                if (ocrReady) add("PP-OCRv6")
                com.ping.verbead.slm.SlmModelConfig.AVAILABLE_MODELS.forEach { meta ->
                    if (com.ping.verbead.slm.SlmModelConfig.isModelDownloaded(context, meta.id)) {
                        add(meta.displayName)
                    }
                }
            }
        }

        val readySummary = readyShortNames.joinToString("、").ifEmpty { "無完整模型組合" }

        return InstallResult(
            isSuccess = report.fileCount > 0,
            xAsrReady = xAsrReady,
            qwen3Ready = qwen3Ready,
            ocrReady = ocrReady,
            slmReady = slmReady,
            restoredModels = analysis.detailedLabels,
            restoredModelShortNames = readyShortNames,
            restoredSlmModels = analysis.restoredSlmIds,
            fileCount = report.fileCount,
            totalBytes = report.totalBytes,
            message = if (report.fileCount > 0) "模型包復原成功！還原模型：$readySummary" else "壓縮包中未包含有效模型檔案"
        )
    }

    internal suspend fun extractZipToDirectory(
        rawStream: InputStream,
        totalSize: Long,
        targetDir: File,
        onProgress: suspend (currentFile: String, percent: Int) -> Unit = { _, _ -> }
    ): Pair<Int, Long> {
        val report = extractZipToDirectoryWithReport(rawStream, totalSize, targetDir, onProgress)
        return Pair(report.fileCount, report.totalBytes)
    }

    internal suspend fun extractZipToDirectoryWithReport(
        rawStream: InputStream,
        totalSize: Long,
        targetDir: File,
        onProgress: suspend (currentFile: String, percent: Int) -> Unit = { _, _ -> }
    ): ExtractionReport {
        var extractedCount = 0
        var totalBytesExtracted = 0L
        val extractedEntries = mutableListOf<String>()

        val progressStream = ProgressInputStream(rawStream, totalSize) { _, _ -> }

        ZipInputStream(BufferedInputStream(progressStream, 64 * 1024)).use { zis ->
            val buffer = ByteArray(64 * 1024)
            var entry = zis.nextEntry

            while (entry != null) {
                val rawName = entry.name.replace('\\', '/').trim()
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
                    extractedEntries.add(entryName)

                    val updatedPct = if (totalSize > 0) {
                        ((progressStream.bytesRead * 100) / totalSize).toInt().coerceIn(0, 100)
                    } else 0
                    onProgress(currentFileName, updatedPct)
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return ExtractionReport(extractedCount, totalBytesExtracted, extractedEntries)
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
