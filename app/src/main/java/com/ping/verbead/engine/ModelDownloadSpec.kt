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

    // PP-OCRv6: locked to respective commit hashes on 2026-10-07
    fun ocr(model: String = ModelConfig.OCR_MODEL_TINY): DownloadTarget {
        val isSmall = (model == ModelConfig.OCR_MODEL_SMALL)
        val detModel = if (isSmall) "PP-OCRv6_small_det_onnx" else "PP-OCRv6_tiny_det_onnx"
        val recModel = if (isSmall) "PP-OCRv6_small_rec_onnx" else "PP-OCRv6_tiny_rec_onnx"
        val detCommit = if (isSmall) "28fe5895c24fd108c19eb3e8479f4ab385fbfc62" else "2ba1506c0380b8f0b03dd142459aac66d4421f6c"
        val recCommit = if (isSmall) "b8f84f0b80c529de40b4fbb3544b84fa7233a513" else "2612ab37152ae0a677521bae4e1e3d4fb4cf7c30"

        val detSha256 = if (isSmall) {
            "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e"
        } else {
            "193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8"
        }
        val recSha256 = if (isSmall) {
            "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634"
        } else {
            "9ef676d6ed3c88256a2d92c640c44f25b0c40947e111b14b8be8f594091563e6"
        }
        val dictSha256 = if (isSmall) {
            "ab078671bb49f06228eadccd34f1bb501e157f7a047095ffb943ba81512c77d1"
        } else {
            "66170210bad538e83fff3c4a3867e547d6bf20b50d64b20347c4b913f3034ea1"
        }

        return DownloadTarget(
            engine = model,
            files = listOf(
                RemoteFile(
                    "https://huggingface.co/PaddlePaddle/$detModel/resolve/$detCommit/inference.onnx",
                    "${model}_det.onnx",
                    detSha256
                ),
                RemoteFile(
                    "https://huggingface.co/PaddlePaddle/$recModel/resolve/$recCommit/inference.onnx",
                    "${model}_rec.onnx",
                    recSha256
                ),
                RemoteFile(
                    "https://huggingface.co/PaddlePaddle/$recModel/resolve/$recCommit/inference.yml",
                    "${model}_dict.txt",
                    dictSha256
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
