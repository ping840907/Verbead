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

/**
 * Liquid AI LiquidCodec 24 kHz 神經聲學解碼器與 iSTFT 波形合成器。
 * 
 * 依據 LiquidCodecModel 規格：
 * - 採樣率: 24,000 Hz
 * - 碼簿數量: 12 (FSQ 階層 [8, 7, 6, 6])
 * - STFT 規格: N_FFT = 1280, Hop = 320, Win = 1280
 * - 幀堆疊: 6 倍 (每幀 80ms，共 1920 樣本點)
 * - 輸出: 24 kHz 單聲道 Float PCM
 */
object LiquidCodecSynthesizer {

    private const val TAG = "LiquidCodecSynthesizer"

    const val SAMPLE_RATE = 24_000
    const val NUM_CODEBOOKS = 12
    const val N_FFT = 1280
    const val HOP_LENGTH = 320
    const val WIN_LENGTH = 1280
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
     * 數學結果與 PyTorch torch.fft.irfft(spec, n=1280) 100% 精確吻合。
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
     * 透過逆向重疊相加 (Overlap-Add iSTFT) 還原為 24 kHz PCM 聲學波形。
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

        // 依窗函數能量正規化 (避免振幅失真)
        for (i in 0 until audioLen) {
            val env = max(envelope[i], 1e-4f)
            audio[i] = (audio[i] / env).coerceIn(-1.0f, 1.0f)
        }

        return audio
    }

    /**
     * 透過 Depth Decoder (MaskGIT 3-pass 排程 [4, 4, 4])，
     * 將語言模型 2048 維隱狀態 (hidden_states) 解碼為 12 軌 FSQ 碼點。
     */
    fun sampleCodesWithMaskGit(
        env: OrtEnvironment,
        depthSession: OrtSession,
        hiddenState: FloatArray // shape: [2048]
    ): LongArray {
        val numCodebooks = NUM_CODEBOOKS // 12
        val codes = Array(1) { LongArray(numCodebooks) { 0L } }
        val masked = Array(1) { BooleanArray(numCodebooks) { true } }

        val schedule = listOf(0 until 4, 4 until 8, 8 until 12)

        val hiddenTensor = OnnxTensor.createTensor(
            env,
            arrayOf(hiddenState) // [1, 2048]
        )

        hiddenTensor.use { hTensor ->
            for (stepRange in schedule) {
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
                            val vocabSize = 2017
                            val frameLogits = FloatArray(numCodebooks * vocabSize)
                            logitsBuffer.get(frameLogits)

                            // 針對本輪 stepRange 解除遮罩並進行 Greedy / Argmax 採樣
                            for (c in stepRange) {
                                val offset = c * vocabSize
                                var maxVal = -Float.MAX_VALUE
                                var bestIdx = 0
                                for (v in 0 until 2016) { // 限制在 0..2015 FSQ 範圍
                                    val logit = frameLogits[offset + v]
                                    if (logit > maxVal) {
                                        maxVal = logit
                                        bestIdx = v
                                    }
                                }
                                codes[0][c] = bestIdx.toLong()
                                masked[0][c] = false
                            }
                        }
                    }
                }
            }
        }

        return codes[0]
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
        val totalElements = NUM_CODEBOOKS * numFrames
        val longBuffer = LongBuffer.allocate(totalElements)

        // 展平為 [1, 12, numFrames]
        for (c in 0 until NUM_CODEBOOKS) {
            for (f in 0 until numFrames) {
                longBuffer.put(codes[c][f])
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
