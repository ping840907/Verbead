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

    // Qwen3-ASR 0.6B int8 tar.bz2 released on 2026-03-25 by k2-fsa/sherpa-onnx
    fun qwen3(): DownloadTarget = DownloadTarget(
        engine = ModelConfig.ENGINE_QWEN3,
        archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2",
        archiveSha256 = "393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96",
    )

    // X-ASR: locked to commit 90fe6b8a0f278886cbeabd2d41cc5402f7f90a2b (2026-10-07)
    fun xAsr(): DownloadTarget = DownloadTarget(
        engine = ModelConfig.ENGINE_X_ASR,
        files = listOf(
            RemoteFile(
                "https://huggingface.co/Luigi/x-asr-zh-tw-en-streaming-ft75m/resolve/90fe6b8a0f278886cbeabd2d41cc5402f7f90a2b/${ModelConfig.X_ASR_ENCODER}",
                ModelConfig.X_ASR_ENCODER,
                "9a92f64387787ff47ace05edaedc1e6774b231e790e283330018b14f4a1b3e07",
            ),
            RemoteFile(
                "https://huggingface.co/Luigi/x-asr-zh-tw-en-streaming-ft75m/resolve/90fe6b8a0f278886cbeabd2d41cc5402f7f90a2b/${ModelConfig.X_ASR_DECODER}",
                ModelConfig.X_ASR_DECODER,
                "0d6d200e0a3c6e70ab6bf063b174b532e638d458e74c5a406c83c1eaec993b05",
            ),
            RemoteFile(
                "https://huggingface.co/Luigi/x-asr-zh-tw-en-streaming-ft75m/resolve/90fe6b8a0f278886cbeabd2d41cc5402f7f90a2b/${ModelConfig.X_ASR_JOINER}",
                ModelConfig.X_ASR_JOINER,
                "f8c88ae06fa13ea83335660e7235e19cb4f57aad0a82882a35126202b96c2a7d",
            ),
            RemoteFile(
                "https://huggingface.co/Luigi/x-asr-zh-tw-en-streaming-ft75m/resolve/90fe6b8a0f278886cbeabd2d41cc5402f7f90a2b/${ModelConfig.X_ASR_TOKENS}",
                ModelConfig.X_ASR_TOKENS,
                "3a286a8dd2015c08d27314b1641f9be86aa3274c49fb452fbb4170bee6c22737",
            ),
        ),
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

    fun forEngine(engine: String): DownloadTarget = when {
        engine == ModelConfig.OCR_MODEL_KOREAN -> ocrKorean()
        engine == ModelConfig.ENGINE_X_ASR -> xAsr()
        engine == ModelConfig.OCR_MODEL_TINY || engine == ModelConfig.OCR_MODEL_SMALL -> ocr(engine)
        else -> qwen3()
    }
}
