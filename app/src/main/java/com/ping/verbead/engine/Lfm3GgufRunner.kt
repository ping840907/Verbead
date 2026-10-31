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
 * Liquid AI LFM3-Audio-2B GGUF 原生硬體加速推論執行器 (llama.cpp b12020+ mtmd & tts)。
 *
 * 支援自動偵測並掛載：
 * 1. Qualcomm Hexagon NPU (`HTP0` FastRPC 硬體張量處理器)
 * 2. Qualcomm Adreno OpenCL GPU (`GPUOpenCL`)
 * 3. ARMv9 NEON / DotProd 多執行緒 CPU
 */
object Lfm3GgufRunner {

    private const val TAG = "Lfm3GgufRunner"

    private val runMutex = Mutex()

    // 紀錄目前已驗證可用的硬體加速後端 ("HTP0", "GPUOpenCL", "CPU")
    @Volatile
    var activeBackendDevice: String? = null
        private set

    private val candidateBackends = listOf("HTP0", "GPUOpenCL", "CPU")

    fun isAvailable(context: Context): Boolean {
        if (!ModelConfig.isLfm3GgufReady(context)) return false
        val mtmdBin = resolveExecutable(context, "liblfm3_mtmd_exec.so", "llama-mtmd-cli")
        val ttsBin = resolveExecutable(context, "liblfm3_tts_exec.so", "llama-tts")
        return mtmdBin != null && ttsBin != null
    }

    private fun resolveExecutable(context: Context, jniLibName: String, tmpBinName: String): File? {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val fromJni = File(nativeDir, jniLibName)
        if (fromJni.exists() && fromJni.canExecute()) {
            return fromJni
        }
        val fromTmp = File("/data/local/tmp/llama/bin/$tmpBinName")
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
     * 執行 GGUF 原生語音辨識 (ASR)：將 16kHz PCM 轉換為 WAV 並由 `llama-mtmd-cli` 推論。
     */
    suspend fun transcribe(
        context: Context,
        samples16k: FloatArray
    ): String = withContext(Dispatchers.IO) {
        if (samples16k.isEmpty()) return@withContext ""
        runMutex.withLock {
            val mtmdBin = resolveExecutable(context, "liblfm3_mtmd_exec.so", "llama-mtmd-cli")
                ?: return@withLock ""
            val modelPath = ModelConfig.lfm3AudioGgufModelPath(context)
            val mmprojPath = ModelConfig.lfm3AudioGgufMmprojPath(context)

            val wavFile = File(context.cacheDir, "lfm3_asr_input.wav")
            writeWav16kMono(wavFile, samples16k, 16_000)

            val backendsToTry = activeBackendDevice?.let { listOf(it) + candidateBackends.filter { b -> b != it } }
                ?: candidateBackends

            for (backend in backendsToTry) {
                val t0 = System.currentTimeMillis()
                try {
                    val cmd = ArrayList<String>(24)
                    cmd.add(mtmdBin.absolutePath)
                    cmd.add("-m"); cmd.add(modelPath)
                    cmd.add("--mmproj"); cmd.add(mmprojPath)
                    if (backend == "CPU") {
                        cmd.add("-ngl"); cmd.add("0")
                    } else {
                        cmd.add("-dev"); cmd.add(backend)
                        cmd.add("-ngl"); cmd.add("99")
                    }
                    cmd.add("-fit"); cmd.add("off")
                    cmd.add("--no-mmproj-offload")
                    cmd.add("--no-warmup")
                    cmd.add("-t"); cmd.add("8")
                    cmd.add("--audio"); cmd.add(wavFile.absolutePath)
                    cmd.add("-sys"); cmd.add("Perform ASR.")
                    cmd.add("-p"); cmd.add("<__media__>")
                    cmd.add("--temp"); cmd.add("0")
                    cmd.add("-n"); cmd.add("96")

                    val output = runProcess(context, cmd)
                    if (output.exitCode == 0) {
                        val parsed = parseMtmdTranscript(output.stdout)
                        if (parsed.isNotBlank()) {
                            activeBackendDevice = backend
                            val elapsed = System.currentTimeMillis() - t0
                            val traditional = ModelConfig.toTaiwanTraditional(parsed)
                            Log.i(TAG, "LFM3 GGUF ASR [$backend] completed in ${elapsed}ms: \"$traditional\"")
                            return@withLock traditional
                        }
                    } else {
                        Log.w(TAG, "LFM3 GGUF ASR on backend=$backend exited with ${output.exitCode}: ${output.stderr.takeLast(300)}")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "LFM3 GGUF ASR failed on backend=$backend: ${e.message}")
                }
            }
            ""
        }
    }

    /**
     * 執行 GGUF 原生 24kHz 語音合成 (TTS)：由 `llama-tts` 搭配 Hexagon NPU / Adreno GPU 加速生成。
     */
    suspend fun synthesizeSpeech(
        context: Context,
        text: String
    ): FloatArray? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        runMutex.withLock {
            val ttsBin = resolveExecutable(context, "liblfm3_tts_exec.so", "llama-tts")
                ?: return@withLock null
            val modelPath = ModelConfig.lfm3AudioGgufModelPath(context)
            val mmprojPath = ModelConfig.lfm3AudioGgufMmprojPath(context)

            val hasChinese = text.any { it.code in 0x4E00..0x9FFF }
            val ttsLang = if (hasChinese) "Chinese" else "English"
            val normalizedPrompt = if (hasChinese) {
                TaiwanesePromptAdapter.buildQwen3Prompt(text)
            } else {
                text
            }

            val outWavFile = File(context.cacheDir, "lfm3_tts_output.wav")
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
                    cmd.add("--tts-voice"); cmd.add("female")
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
                                "LFM3 GGUF TTS [$backend] synthesized ${samples.size} samples (${"%.2f".format(samples.size / 24000f)}s) in ${elapsed}ms"
                            )
                            return@withLock samples
                        }
                    } else {
                        Log.w(TAG, "LFM3 GGUF TTS on backend=$backend exited with ${output.exitCode}: ${output.stderr.takeLast(300)}")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "LFM3 GGUF TTS failed on backend=$backend: ${e.message}")
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
        val errThread = thread(start = true, name = "lfm3-stderr-reader") {
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

    /**
     * `llama-mtmd-cli` 將所有日誌輸出至 `stderr`，因此 `stdout` 僅包含純粹的模型輸出轉寫文字。
     */
    internal fun parseMtmdTranscript(rawStdout: String): String {
        return rawStdout.lines()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                    !line.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+\\s+[IWE]\\s+.*")) &&
                    !line.startsWith("<|")
            }
            .joinToString(" ")
            .trim()
    }

    private fun writeWav16kMono(outFile: File, samples: FloatArray, sampleRate: Int) {
        val numSamples = samples.size
        val dataSize = numSamples * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)

        BufferedOutputStream(FileOutputStream(outFile), 65536).use { bos ->
            bos.write(header.array())
            val buf = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                if (buf.remaining() < 2) {
                    bos.write(buf.array(), 0, buf.position())
                    buf.clear()
                }
                val pcm16 = (s.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
                buf.putShort(pcm16)
            }
            if (buf.position() > 0) {
                bos.write(buf.array(), 0, buf.position())
            }
        }
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
