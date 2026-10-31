package com.ping.verbead.engine

import android.content.Context
import android.util.Log
import com.github.houbb.opencc4j.util.ZhConverterUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Alibaba Qwen3-TTS (12Hz / 24kHz) GGUF 端側神經語音合成執行器。
 *
 * 核心特性：
 * 1. 支援 Alibaba Qwen3-TTS-12Hz-1.7B-Base 官方 GGUF 量化模型與 mmproj 聲學投影頭
 * 2. 透過 llama.cpp (b12020+ libmtmd qwen3tts_gen & qwen3tts_spkenc) 原生加速
 * 3. 支援 Qualcomm Hexagon NPU (`HTP0`)、Adreno 840 GPU (`GPUOpenCL`) 與 ARMv9 CPU 全層卸載
 * 4. 內建聲音克隆 (Zero-Shot Voice Cloning) 聲學參考音訊機制
 */
object Qwen3TtsRunner {

    private const val TAG = "Qwen3TtsRunner"

    private val runMutex = Mutex()

    @Volatile
    var activeBackendDevice: String? = null
        private set

    private val candidateBackends = listOf("HTP0", "GPUOpenCL", "CPU")

    /**
     * 檢查 Qwen3-TTS 執行檔與模型是否就緒。
     */
    fun isAvailable(context: Context): Boolean {
        if (!ModelConfig.isQwen3TtsReady(context)) return false
        val ttsBin = resolveExecutable(context)
        return ttsBin != null
    }

    private fun resolveExecutable(context: Context): File? {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val fromJni = File(nativeDir, "liblfm3_tts_exec.so")
        if (fromJni.exists() && fromJni.canExecute()) {
            return fromJni
        }
        val fromTmp = File("/data/local/tmp/llama/bin/llama-tts")
        if (fromTmp.exists() && fromTmp.canExecute()) {
            return fromTmp
        }
        return null
    }

    private fun resolveLibDir(context: Context): String {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val hasGgml = File(nativeDir, "libggml.so").exists()
        return if (hasGgml) {
            nativeDir.absolutePath
        } else {
            "/data/local/tmp/llama/lib:${nativeDir.absolutePath}"
        }
    }

    /**
     * 確保預設參考聲音樣本就緒 (若本地尚無則從 assets 提取或寫入)。
     */
    fun ensureDefaultSpeaker(context: Context): File? {
        val target = File(ModelConfig.qwen3TtsDefaultSpeakerPath(context))
        if (target.exists() && target.length() > 1024L) {
            return target
        }
        try {
            target.parentFile?.mkdirs()
            context.assets.open("speaker_default.wav").use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            if (target.exists() && target.length() > 1024L) {
                return target
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Could not extract speaker_default.wav from assets: ${e.message}")
        }
        return if (target.exists()) target else null
    }

    /**
     * 執行 Qwen3-TTS 語音合成：輸出 24kHz 單聲道浮點數 PCM 聲波陣列。
     *
     * @param context Android 上下文
     * @param text 欲朗讀的文字 (支援繁簡中文與英文)
     * @param speakerAudioFile 自訂聲音克隆參考音訊 (若為 null 則採用系統預設聲音)
     */
    suspend fun synthesizeSpeech(
        context: Context,
        text: String,
        speakerAudioFile: File? = null
    ): FloatArray? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        if (!ModelConfig.isQwen3TtsReady(context)) {
            Log.w(TAG, "Qwen3-TTS GGUF model files not ready on device.")
            return@withContext null
        }

        runMutex.withLock {
            val ttsBin = resolveExecutable(context) ?: return@withLock null
            val modelPath = ModelConfig.qwen3TtsModelPath(context)
            val mmprojPath = ModelConfig.qwen3TtsMmprojPath(context)
            val speakerFile = speakerAudioFile ?: ensureDefaultSpeaker(context)

            val hasChinese = text.any { it.code in 0x4E00..0x9FFF }
            val ttsLang = if (hasChinese) "zh" else "en"
            val normalizedPrompt = if (hasChinese) {
                TaiwanesePromptAdapter.buildQwen3Prompt(text)
            } else {
                text
            }

            val outWavFile = File(context.cacheDir, "qwen3_tts_output.wav")
            if (outWavFile.exists()) outWavFile.delete()

            val backendsToTry = activeBackendDevice?.let { listOf(it) + candidateBackends.filter { b -> b != it } }
                ?: candidateBackends

            for (backend in backendsToTry) {
                val t0 = System.currentTimeMillis()
                try {
                    val cmd = ArrayList<String>(24)
                    cmd.add(ttsBin.absolutePath)
                    cmd.add("-m"); cmd.add(modelPath)
                    cmd.add("-mm"); cmd.add(mmprojPath)
                    if (backend == "CPU") {
                        cmd.add("-ngl"); cmd.add("0")
                    } else {
                        cmd.add("-dev"); cmd.add(backend)
                        cmd.add("-ngl"); cmd.add("99")
                    }
                    cmd.add("-fit"); cmd.add("off")
                    cmd.add("--no-mmproj-offload")
                    cmd.add("-t"); cmd.add("8")
                    cmd.add("-p"); cmd.add(normalizedPrompt)
                    cmd.add("--tts-lang"); cmd.add(ttsLang)
                    if (speakerFile != null && speakerFile.exists() && speakerFile.length() > 512L) {
                        cmd.add("--tts-speaker-file"); cmd.add(speakerFile.absolutePath)
                    }
                    cmd.add("--temp"); cmd.add("0.4")
                    cmd.add("--top-k"); cmd.add("64")
                    cmd.add("-o"); cmd.add(outWavFile.absolutePath)

                    val output = runProcess(context, cmd)
                    if (output.exitCode == 0 && outWavFile.exists() && outWavFile.length() > 100L) {
                        val samples = readWavPcm16(outWavFile)
                        if (samples != null && samples.isNotEmpty()) {
                            activeBackendDevice = backend
                            val elapsed = System.currentTimeMillis() - t0
                            Log.i(
                                TAG,
                                "Qwen3-TTS [$backend] synthesized ${samples.size} samples (${"%.2f".format(samples.size / 24000f)}s) in ${elapsed}ms"
                            )
                            return@withLock samples
                        }
                    } else {
                        Log.w(
                            TAG,
                            "Qwen3-TTS on backend=$backend exited with ${output.exitCode}: ${output.stderr.takeLast(300)}"
                        )
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Qwen3-TTS failed on backend=$backend: ${e.message}")
                }
            }
            null
        }
    }

    private data class ProcessResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    private fun runProcess(context: Context, cmd: List<String>): ProcessResult {
        val libDir = resolveLibDir(context)
        val pb = ProcessBuilder(cmd)
        val env = pb.environment()
        env["LD_LIBRARY_PATH"] = "$libDir:/vendor/lib64:/system/lib64"
        env["ADSP_LIBRARY_PATH"] = "$libDir;/vendor/dsp/cdsp;/vendor/lib64/rfsa/adsp;/system/lib64/rfsa/adsp;/dsp"
        env["GGML_OPENCL_PLATFORM"] = "QUALCOMM"

        val process = pb.start()
        try {
            process.outputStream.close()
        } catch (_: Throwable) {}

        var stderrStr = ""
        val errThread = thread(start = true, name = "qwen3tts-stderr-reader") {
            try {
                stderrStr = process.errorStream.bufferedReader(Charsets.UTF_8).readText()
            } catch (_: Throwable) {}
        }
        val stdoutStr = try {
            process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } catch (_: Throwable) {
            ""
        }
        errThread.join(5000)
        val exitCode = process.waitFor()
        return ProcessResult(
            exitCode = exitCode,
            stdout = stdoutStr,
            stderr = stderrStr
        )
    }

    private fun readWavPcm16(wavFile: File): FloatArray? {
        val bytes = FileInputStream(wavFile).use { it.readBytes() }
        if (bytes.size <= 44) return null
        var dataOffset = 44
        for (i in 12 until minOf(bytes.size - 8, 256)) {
            if (bytes[i] == 'd'.code.toByte() &&
                bytes[i + 1] == 'a'.code.toByte() &&
                bytes[i + 2] == 't'.code.toByte() &&
                bytes[i + 3] == 'a'.code.toByte()
            ) {
                dataOffset = i + 8
                break
            }
        }
        val pcmBytes = bytes.size - dataOffset
        if (pcmBytes <= 0) return null
        val numSamples = pcmBytes / 2
        val shortBuf = ByteBuffer.wrap(bytes, dataOffset, numSamples * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        val out = FloatArray(numSamples)
        for (i in 0 until numSamples) {
            out[i] = (shortBuf.get(i) / 32768.0f).coerceIn(-1.0f, 1.0f)
        }
        return out
    }
}
