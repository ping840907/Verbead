package com.ping.verbead.engine

/** Where to fetch each engine's model files from, and how to lay them out on disk. */
object ModelDownloadSpec {

    data class RemoteFile(
        val url: String,
        val relativePath: String,
        val sha256: String? = null,
    )

    /**
     * [archiveUrl] set → single tar.bz2 downloaded and extracted (first path
     * component stripped) into the engine's model directory.
     * [files] set → each entry downloaded individually into its relativePath.
     * Exactly one of the two is populated.
     */
    data class DownloadTarget(
        val engine: String,
        val archiveUrl: String? = null,
        val archiveSha256: String? = null,
        val files: List<RemoteFile> = emptyList(),
    )

    // PP-OCRv6 (LiteRT): every file is an asset of the repository release OcrModelManifest.RELEASE_TAG,
    // checked against the sums recorded in OcrModelManifest.
    fun ocr(model: String = ModelConfig.OCR_MODEL_TINY): DownloadTarget = DownloadTarget(
        engine = model,
        files = ModelConfig.requiredOcrFiles(model).map { name ->
            RemoteFile(
                OcrModelManifest.BASE_URL + name,
                name,
                requireNotNull(OcrModelManifest.sha256[name]) { "no checksum recorded for $name" }
            )
        }
    )

    /** The optional Korean recognizer (same release as the other OCR files). */
    fun ocrKorean(): DownloadTarget = DownloadTarget(
        engine = ModelConfig.OCR_MODEL_KOREAN,
        files = ModelConfig.requiredOcrKoreanFiles().map { name ->
            RemoteFile(
                OcrModelManifest.BASE_URL + name,
                name,
                requireNotNull(OcrModelManifest.sha256[name]) { "no checksum recorded for $name" }
            )
        }
    )

    /** Where a catalog model comes from: its archive, or its list of files. */
    fun forModel(model: AsrModel): DownloadTarget = DownloadTarget(
        engine = model.id,
        archiveUrl = model.archiveUrl,
        archiveSha256 = model.archiveSha256,
        files = if (model.archiveUrl == null) model.files else emptyList(),
    )

    fun forEngine(engine: String): DownloadTarget {
        val model = AsrCatalog.byId(engine)
        return when {
            engine == ModelConfig.OCR_MODEL_KOREAN -> ocrKorean()
            engine == ModelConfig.OCR_MODEL_TINY || engine == ModelConfig.OCR_MODEL_SMALL -> ocr(engine)
            model != null -> forModel(model)
            else -> throw IllegalArgumentException("unknown model: $engine")
        }
    }
}
