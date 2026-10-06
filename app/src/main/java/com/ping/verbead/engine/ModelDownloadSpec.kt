package com.ping.verbead.engine

/** Where to fetch each engine's model files from, and how to lay them out on disk. */
object ModelDownloadSpec {

    data class RemoteFile(val url: String, val relativePath: String)

    /**
     * [archiveUrl] set → single tar.bz2 downloaded and extracted (first path
     * component stripped) into the engine's model directory.
     * [files] set → each entry downloaded individually into its relativePath.
     * Exactly one of the two is populated.
     */
    data class DownloadTarget(
        val engine: String,
        val archiveUrl: String? = null,
        val files: List<RemoteFile> = emptyList(),
    )

    fun qwen3(): DownloadTarget = DownloadTarget(
        engine = ModelConfig.ENGINE_QWEN3,
        archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2",
    )

    fun xAsr(): DownloadTarget = DownloadTarget(
        engine = ModelConfig.ENGINE_X_ASR,
        files = listOf(
            RemoteFile(
                "https://huggingface.co/Luigi/x-asr-zh-tw-en-streaming-ft75m/resolve/main/${ModelConfig.X_ASR_ENCODER}",
                ModelConfig.X_ASR_ENCODER,
            ),
            RemoteFile(
                "https://huggingface.co/Luigi/x-asr-zh-tw-en-streaming-ft75m/resolve/main/${ModelConfig.X_ASR_DECODER}",
                ModelConfig.X_ASR_DECODER,
            ),
            RemoteFile(
                "https://huggingface.co/Luigi/x-asr-zh-tw-en-streaming-ft75m/resolve/main/${ModelConfig.X_ASR_JOINER}",
                ModelConfig.X_ASR_JOINER,
            ),
            RemoteFile(
                "https://huggingface.co/Luigi/x-asr-zh-tw-en-streaming-ft75m/resolve/main/${ModelConfig.X_ASR_TOKENS}",
                ModelConfig.X_ASR_TOKENS,
            ),
        ),
    )

    fun ocr(model: String = ModelConfig.OCR_MODEL_TINY): DownloadTarget {
        val detModel = if (model == ModelConfig.OCR_MODEL_SMALL) "PP-OCRv6_small_det_onnx" else "PP-OCRv6_tiny_det_onnx"
        val recModel = if (model == ModelConfig.OCR_MODEL_SMALL) "PP-OCRv6_small_rec_onnx" else "PP-OCRv6_tiny_rec_onnx"
        return DownloadTarget(
            engine = model,
            files = listOf(
                RemoteFile(
                    "https://huggingface.co/PaddlePaddle/$detModel/resolve/main/inference.onnx",
                    "${model}_det.onnx"
                ),
                RemoteFile(
                    "https://huggingface.co/PaddlePaddle/$recModel/resolve/main/inference.onnx",
                    "${model}_rec.onnx"
                ),
                RemoteFile(
                    "https://huggingface.co/PaddlePaddle/$recModel/resolve/main/inference.yml",
                    "${model}_dict.txt"
                )
            )
        )
    }

    fun forEngine(engine: String): DownloadTarget = when (engine) {
        ModelConfig.ENGINE_X_ASR -> xAsr()
        ModelConfig.OCR_MODEL_TINY, ModelConfig.OCR_MODEL_SMALL -> ocr(engine)
        else -> qwen3()
    }
}
