package com.ping.verbead.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.nio.LongBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Liquid AI LiquidCodec 24 kHz 神經聲學解碼器與 iSTFT 波形合成器。
 *
 * 依據 LiquidCodecModel 規格 (`modeling_liquid_codec.py` & `modeling_lfm3_audio.py`)：
 * - 採樣率: 24,000 Hz
 * - 碼簿數量: 12 (FSQ 階層 [8, 7, 6, 6] = 2016 聲學碼 + 2016 EOS 結束碼 = 2017)
 * - STFT 規格: N_FFT = 1280, Hop = 320, Win = 1280
 * - 幀堆疊: 6 倍 (每幀 80ms，共 1920 樣本點)
 * - 輸出: 24 kHz 單聲道 Float PCM
 */
object LiquidCodecSynthesizer {

    private const val TAG = "LiquidCodecSynthesizer"

    const val SAMPLE_RATE = 24_000
    const val NUM_CODEBOOKS = 12
    const val CODEBOOK_VOCAB_SIZE = 2017
    const val AUDIO_EOS_CODE = 2016L
    const val N_FFT = 1280
    const val HOP_LENGTH = 320
    const val WIN_LENGTH = 1280
    const val TRIM_SAMPLES = (WIN_LENGTH - HOP_LENGTH) / 2 // 480
    const val FRAME_STACK = 6
    const val NUM_BINS = 641 // N_FFT / 2 + 1
    const val FEATURE_DIM = 1282 // 2 * NUM_BINS (log_mag + angle)

    // 預先計算旋轉因子查詢表 (大幅加速 iRFFT 計算)
    private val cosTable = FloatArray(N_FFT) { i -> cos(2.0 * PI * i / N_FFT).toFloat() }
    private val sinTable = FloatArray(N_FFT) { i -> sin(2.0 * PI * i / N_FFT).toFloat() }

    // 預先計算 Hann 視窗與平方視窗
    private val hannWindow = FloatArray(WIN_LENGTH) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / WIN_LENGTH))).toFloat()
    }
    private val hannWindowSq = FloatArray(WIN_LENGTH) { i ->
        hannWindow[i] * hannWindow[i]
    }

    /**
     * 針對 1280 點 STFT 頻譜進行極速逆實數傅立葉變換 (iRFFT)，
     * 數學結果與 PyTorch torch.fft.irfft(spec, n=1280, norm="backward") 100% 精確吻合。
     */
    fun irfft1280(real: FloatArray, imag: FloatArray, out: FloatArray) {
        val invN = 1.0f / N_FFT
        for (n in 0 until N_FFT) {
            var sum = 0.0f
            for (k in 1 until NUM_BINS - 1) {
                val idx = (k * n) % N_FFT
                sum += real[k] * cosTable[idx] - imag[k] * sinTable[idx]
            }
            val sign = if (n % 2 == 1) -1.0f else 1.0f
            out[n] = (real[0] + sign * real[NUM_BINS - 1] + 2.0f * sum) * invN
        }
    }

    /**
     * 將 ONNX codec_decoder 輸出的 stft_features (shape: [1, seqLen, 1282])
     * 透過逆向重疊相加 (Overlap-Add iSTFT) 與首尾修剪 (trim = 480) 還原為 24 kHz PCM 聲學波形。
     */
    fun stftToWaveform(stftFeatures: FloatArray, seqLen: Int): FloatArray {
        if (seqLen <= 0) return FloatArray(0)
        val audioLen = (seqLen - 1) * HOP_LENGTH + WIN_LENGTH
        val audio = FloatArray(audioLen)
        val envelope = FloatArray(audioLen)

        val frameReal = FloatArray(NUM_BINS)
        val frameImag = FloatArray(NUM_BINS)
        val frameTime = FloatArray(WIN_LENGTH)

        for (t in 0 until seqLen) {
            val offset = t * FEATURE_DIM
            // 前 641 為 log_magnitude，後 641 為 angle
            for (k in 0 until NUM_BINS) {
                val logMag = stftFeatures[offset + k].coerceIn(-20.0f, 10.0f)
                val angle = stftFeatures[offset + NUM_BINS + k]
                val mag = exp(logMag)
                frameReal[k] = (mag * cos(angle)).toFloat()
                frameImag[k] = (mag * sin(angle)).toFloat()
            }

            // 執行 1280 點 iRFFT 並加窗
            irfft1280(frameReal, frameImag, frameTime)

            val startSample = t * HOP_LENGTH
            for (n in 0 until WIN_LENGTH) {
                val pos = startSample + n
                if (pos < audioLen) {
                    audio[pos] += frameTime[n] * hannWindow[n]
                    envelope[pos] += hannWindowSq[n]
                }
            }
        }

        // 對齊 modeling_liquid_codec.py: audio[:, trim:-trim] / envelope[trim:-trim]
        if (audioLen > 2 * TRIM_SAMPLES) {
            val trimmedLen = audioLen - 2 * TRIM_SAMPLES
            val trimmed = FloatArray(trimmedLen)
            for (i in 0 until trimmedLen) {
                val srcIdx = i + TRIM_SAMPLES
                val env = max(envelope[srcIdx], 1e-4f)
                trimmed[i] = (audio[srcIdx] / env).coerceIn(-1.0f, 1.0f)
            }
            return trimmed
        }

        for (i in 0 until audioLen) {
            val env = max(envelope[i], 1e-4f)
            audio[i] = (audio[i] / env).coerceIn(-1.0f, 1.0f)
        }
        return audio
    }

    /**
     * 透過 Depth Decoder (MaskGIT 3-pass 隨機順序排程 [4, 4, 4])，
     * 將語言模型 2048 維隱狀態 (hidden_states) 解碼為 12 軌 FSQ 碼點 (含 EOS 2016 判定)。
     */
    fun sampleCodesWithMaskGit(
        env: OrtEnvironment,
        depthSession: OrtSession,
        hiddenState: FloatArray, // shape: [2048]
        history: List<LongArray> = emptyList(),
        temperature: Float = 0.4f,
        topK: Int = 64,
        repetitionPenalty: Float = 1.15f
    ): LongArray {
        val numCodebooks = NUM_CODEBOOKS // 12
        val codes = Array(1) { LongArray(numCodebooks) { 0L } }
        val masked = Array(1) { BooleanArray(numCodebooks) { true } }

        // 對齊 modeling_lfm3_audio.py: 隨機排列 12 個 codebook 分 3 輪 [4, 4, 4] 揭露
        val order = (0 until numCodebooks).shuffled()
        val passes = listOf(
            order.subList(0, 4),
            order.subList(4, 8),
            order.subList(8, 12)
        )

        val hiddenTensor = OnnxTensor.createTensor(
            env,
            arrayOf(hiddenState) // [1, 2048]
        )

        val scores = FloatArray(CODEBOOK_VOCAB_SIZE)

        hiddenTensor.use { hTensor ->
            for (pick in passes) {
                val codesTensor = OnnxTensor.createTensor(env, codes)
                val maskedTensor = OnnxTensor.createTensor(env, masked)

                codesTensor.use { cTensor ->
                    maskedTensor.use { mTensor ->
                        val inputs = mapOf(
                            "hidden_states" to hTensor,
                            "codes" to cTensor,
                            "masked" to mTensor
                        )
                        depthSession.run(inputs).use { results ->
                            val logitsTensor = results[0] as OnnxTensor
                            // logits shape: [1, 12, 2017]
                            val logitsBuffer = logitsTensor.floatBuffer
                            val frameLogits = FloatArray(numCodebooks * CODEBOOK_VOCAB_SIZE)
                            logitsBuffer.get(frameLogits)

                            for (c in pick) {
                                val offset = c * CODEBOOK_VOCAB_SIZE
                                System.arraycopy(frameLogits, offset, scores, 0, CODEBOOK_VOCAB_SIZE)

                                // 1. Repetition Penalty (針對該 codebook 歷史已出現的碼點進行懲罰)
                                if (repetitionPenalty != 1.0f && history.isNotEmpty()) {
                                    for (pastFrame in history) {
                                        val pastCode = pastFrame[c].toInt()
                                        if (pastCode in 0 until CODEBOOK_VOCAB_SIZE) {
                                            val s = scores[pastCode]
                                            scores[pastCode] = if (s < 0f) {
                                                s * repetitionPenalty
                                            } else {
                                                s / repetitionPenalty
                                            }
                                        }
                                    }
                                }

                                // 2. Temperature & Top-K Sampling
                                val sampledCode = sampleFromLogits(scores, temperature, topK)
                                codes[0][c] = sampledCode.toLong()
                                masked[0][c] = false
                            }
                        }
                    }
                }
            }
        }

        return codes[0]
    }

    private fun sampleFromLogits(
        scores: FloatArray,
        temperature: Float,
        topK: Int
    ): Int {
        if (temperature <= 1e-4f || topK == 1) {
            var maxVal = -Float.MAX_VALUE
            var bestIdx = 0
            for (i in scores.indices) {
                if (scores[i] > maxVal) {
                    maxVal = scores[i]
                    bestIdx = i
                }
            }
            return bestIdx
        }

        val k = if (topK > 0) minOf(topK, scores.size) else scores.size
        // 找出前 k 大索引
        val topIndices = IntArray(k)
        val topValues = FloatArray(k) { -Float.MAX_VALUE }

        for (i in scores.indices) {
            val v = scores[i] / temperature
            if (v > topValues[k - 1]) {
                var pos = k - 1
                while (pos > 0 && v > topValues[pos - 1]) {
                    topValues[pos] = topValues[pos - 1]
                    topIndices[pos] = topIndices[pos - 1]
                    pos--
                }
                topValues[pos] = v
                topIndices[pos] = i
            }
        }

        val maxLogit = topValues[0]
        var sumExp = 0.0f
        val probs = FloatArray(k)
        for (i in 0 until k) {
            val p = exp((topValues[i] - maxLogit).toDouble()).toFloat()
            probs[i] = p
            sumExp += p
        }

        if (sumExp <= 0f) return topIndices[0]

        val r = Random.nextFloat() * sumExp
        var acc = 0.0f
        for (i in 0 until k) {
            acc += probs[i]
            if (r <= acc) return topIndices[i]
        }
        return topIndices[0]
    }

    /**
     * 執行完整端到端 LiquidCodec 原生語音解碼推論：
     * 將 [12, numFrames] 的音訊代碼送入 codec_decoder.onnx，還原為 24 kHz 原始音訊。
     */
    fun decodeAudioCodes(
        env: OrtEnvironment,
        codecSession: OrtSession,
        codes: Array<LongArray> // shape: [12][numFrames]
    ): FloatArray {
        val numFrames = codes[0].size
        if (numFrames <= 0) return FloatArray(0)
        val totalElements = NUM_CODEBOOKS * numFrames
        val longBuffer = LongBuffer.allocate(totalElements)

        // 展平為 [1, 12, numFrames] (確保每個碼點限制於合法範圍 0..2015)
        for (c in 0 until NUM_CODEBOOKS) {
            for (f in 0 until numFrames) {
                val safeCode = codes[c][f].coerceIn(0L, (AUDIO_EOS_CODE - 1))
                longBuffer.put(safeCode)
            }
        }
        longBuffer.flip()

        val tensor = OnnxTensor.createTensor(
            env,
            longBuffer,
            longArrayOf(1, NUM_CODEBOOKS.toLong(), numFrames.toLong())
        )

        tensor.use { onnxTensor ->
            val inputs = mapOf("audio_codes" to onnxTensor)
            codecSession.run(inputs).use { results ->
                val outputTensor = results[0] as OnnxTensor
                val shape = outputTensor.info.shape // [1, seqLen, 1282]
                val seqLen = shape[1].toInt()
                val floatBuffer = outputTensor.floatBuffer
                val features = FloatArray(floatBuffer.remaining())
                floatBuffer.get(features)

                Log.d(TAG, "LiquidCodec decoded stft_features: seqLen=$seqLen (${features.size} floats)")
                return stftToWaveform(features, seqLen)
            }
        }
    }
}
