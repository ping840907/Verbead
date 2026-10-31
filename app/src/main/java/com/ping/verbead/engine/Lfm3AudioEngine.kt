package com.ping.verbead.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 代表單輪語音對話的訊息記錄。
 */
data class Lfm3ChatMessage(
    val isUser: Boolean,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val audioSamples: FloatArray? = null
)

/**
 * 代表語音對話引擎的一輪完整回應（包含使用者語音轉寫、助理回覆文字與 24kHz 合成語音）。
 */
data class Lfm3ChatResponse(
    val userText: String,
    val replyText: String,
    val replyAudioSamples: FloatArray? = null,
    val audioSampleRate: Int = 24_000,
    val latencyMs: Long = 0L
)

/**
 * Liquid AI LFM3-Audio-2B-ONNX 邊緣端音訊基礎模型推論引擎。
 *
 * 完整實作官方 ONNX 四階段管線：
 * 1. `audio_encoder.onnx`: 16 kHz 128-bin Slaney Log-Mel 頻譜 -> [numAudioTokens, 2048] 音訊特徵
 * 2. `decoder_q4.onnx`: 2B Tri-Hybrid 主幹模型 (13 層 Short-Conv + 11 層 GQA Attention)，負責 ASR 與 TTS 隱狀態生成
 * 3. `depth_decoder.onnx`: 3-pass MaskGIT 深度解碼器，將 [1, 2048] 隱狀態解碼為 12 軌 FSQ 語音碼點 (含 EOS 2016)
 * 4. `codec_decoder.onnx` + iSTFT: 將 [1, 12, numFrames] 碼點還原為 24 kHz 語音波形
 */
object Lfm3AudioEngine {

    private const val TAG = "Lfm3AudioEngine"

    // 音訊前端規格 (對齊 feature_extraction_lfm3_audio.py & config.json)
    const val SAMPLE_RATE = 16_000
    const val NUM_MELS = 128         // 官方 Lfm3AudioFeatureExtractor 為 128 Slaney mel bins
    const val N_FFT = 400            // 25ms 視窗 (400 samples at 16kHz)
    const val HOP_LENGTH = 160       // 10ms 步進 (160 samples at 16kHz)
    const val WIN_LENGTH = 400
    const val NUM_FREQ_BINS = N_FFT / 2 + 1 // 201
    const val ENCODER_STRIDE = 8     // 每 8 個 Mel frames 降採樣為 1 個 audio token
    const val F_MIN = 0.0f
    const val F_MAX = 8000.0f
    const val LOG_FLOOR = -12.0f
    const val NORM_OFFSET = 4.0f
    const val NORM_DIVISOR = 4.0f

    // 模型超參數 (LFM3 Tri-Hybrid Backbone)
    const val HIDDEN_SIZE = 2048
    const val NUM_LAYERS = 24
    const val CONV_KERNEL_SIZE = 7   // Gated Short Conv 卷積核尺寸 (past_conv shape: [1, 2048, 7])
    const val NUM_KV_HEADS = 4       // GQA KV Heads (past_key_values shape: [1, 4, seqLen, 128])
    const val HEAD_DIM = 128

    // LFM3 24 層骨幹網路中 Conv 與 Attention 的精確層索引 (對齊 decoder_q4.onnx)
    private val CONV_LAYER_INDICES = intArrayOf(0, 1, 3, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23)
    private val ATTN_LAYER_INDICES = intArrayOf(2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22)

    @Volatile private var isInitialized = false
    @Volatile private var isGgufReady = false
    private var ortEnv: OrtEnvironment? = null
    private var audioEncoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var depthDecoderSession: OrtSession? = null
    private var codecDecoderSession: OrtSession? = null

    // 預先計算 Periodic Hann Window (400 點)
    private val hannWindow = FloatArray(N_FFT) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / N_FFT))).toFloat()
    }

    // 預先計算 400 點 DFT 旋轉因子表 (201 x 400)，將 STFT 計算加速數十倍
    private val dftCosTable = Array(NUM_FREQ_BINS) { k ->
        val omega = 2.0 * PI * k / N_FFT
        FloatArray(N_FFT) { t -> cos(omega * t).toFloat() }
    }
    private val dftSinTable = Array(NUM_FREQ_BINS) { k ->
        val omega = 2.0 * PI * k / N_FFT
        FloatArray(N_FFT) { t -> (-sin(omega * t)).toFloat() }
    }

    // 預先計算 128-bin Slaney Mel Filterbank (128 x 201)
    private val slaneyMelFilterBank: Array<FloatArray> by lazy {
        computeSlaneyMelFilterBank(NUM_FREQ_BINS, NUM_MELS, SAMPLE_RATE, F_MIN, F_MAX)
    }

    /**
     * 檢查裝置是否已備妥 LFM3-Audio 核心模型檔案。
     */
    fun isModelReady(context: Context): Boolean = ModelConfig.isLfm3AudioReady(context)

    /**
     * 引擎是否已載入至記憶體隨時可推論。
     */
    fun isSessionReady(): Boolean =
        isGgufReady || (isInitialized && audioEncoderSession != null && decoderSession != null && Lfm3Tokenizer.isReady())

    /**
     * 初始化 ONNX Runtime Session 與 Tokenizer (建議於背景協程呼叫)。
     *
     * 針對硬體加速說明：
     * 官方 `decoder_q4.onnx` 採用 FP32 激活值與 INT4 權重 (`MatMulNBits` + FP32 `GroupQueryAttention`)，
     * 在官方規格表中專為 ARM64 NEON / CPU 與 XNNPACK 硬體指令集設計。
     */
    suspend fun init(context: Context, numThreads: Int = 0): Boolean = withContext(Dispatchers.IO) {
        synchronized(this@Lfm3AudioEngine) {
            if (isSessionReady()) return@withContext true
        }

        // 優先啟用官方 GGUF 硬體加速後端 (Qualcomm Hexagon NPU HTP0 / Adreno OpenCL GPU)
        if (Lfm3GgufRunner.isAvailable(context)) {
            isGgufReady = true
            isInitialized = true
            Log.i(TAG, "LFM3-Audio-2B GGUF backend ready (Snapdragon NPU HTP0 / Adreno GPUOpenCL enabled)")
            return@withContext true
        }

        val encoderFile = File(ModelConfig.lfm3AudioEncoderPath(context))
        val decoderFile = File(ModelConfig.lfm3AudioDecoderPath(context))
        val depthFile = File(ModelConfig.lfm3AudioDepthDecoderPath(context))
        val codecFile = File(ModelConfig.lfm3AudioCodecDecoderPath(context))
        val tokenizerFile = File(ModelConfig.lfm3AudioTokenizerPath(context))

        if (!encoderFile.exists() || !decoderFile.exists() || !tokenizerFile.exists()) {
            Log.w(TAG, "LFM3-Audio ONNX model or tokenizer files missing on device.")
            return@withContext false
        }

        try {
            val t0 = System.currentTimeMillis()
            val effectiveThreads = if (numThreads > 0) {
                numThreads
            } else {
                Runtime.getRuntime().availableProcessors().coerceIn(4, 8)
            }
            Log.i(TAG, "Initializing LFM3-Audio-2B ONNX sessions (threads=$effectiveThreads)...")

            // 0. 載入 BPE Tokenizer
            if (!Lfm3Tokenizer.load(tokenizerFile)) {
                Log.e(TAG, "Failed to load LFM3 tokenizer.json")
                return@withContext false
            }

            val env = OrtEnvironment.getEnvironment()
            ortEnv = env

            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(effectiveThreads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setMemoryPatternOptimization(true)
                try {
                    addXnnpack(mapOf("intra_op_num_threads" to effectiveThreads.toString()))
                    Log.i(TAG, "Enabled XNNPACK hardware SIMD execution provider")
                } catch (e: Throwable) {
                    Log.d(TAG, "XNNPACK provider not attached, using ARM64 NEON CPU EP: ${e.message}")
                }
            }

            // 1. 載入 Causal Audio Encoder
            if (audioEncoderSession == null) {
                audioEncoderSession = env.createSession(encoderFile.absolutePath, opts)
                Log.i(TAG, "AudioEncoder session loaded: ${encoderFile.name}")
            }

            // 2. 載入 Tri-Hybrid Backbone Decoder (2B Q4)
            if (decoderSession == null) {
                decoderSession = env.createSession(decoderFile.absolutePath, opts)
                Log.i(TAG, "Decoder session loaded: ${decoderFile.name}")
            }

            // 3. 載入 DepthDecoder 深度代碼解碼器 (3-pass MaskGIT)
            if (depthFile.exists() && depthDecoderSession == null) {
                depthDecoderSession = env.createSession(depthFile.absolutePath, opts)
                Log.i(TAG, "DepthDecoder session loaded: ${depthFile.name}")
            }

            // 4. 載入 LiquidCodec 語音解碼器
            if (codecFile.exists() && codecDecoderSession == null) {
                codecDecoderSession = env.createSession(codecFile.absolutePath, opts)
                Log.i(TAG, "CodecDecoder session loaded: ${codecFile.name}")
            }

            isInitialized = true
            Log.i(TAG, "LFM3-Audio-2B all sessions initialized in ${System.currentTimeMillis() - t0}ms")
            return@withContext true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize LFM3-Audio engine: ${e.message}", e)
            release()
            return@withContext false
        }
    }

    /**
     * 端到端 LFM3-Audio 原生語音辨識 (Speech-to-Text ASR)。
     *
     * 流程：
     * 1. 將 16 kHz PCM 轉為 128-bin Slaney Log-Mel 頻譜 `[1, 128, numMelFrames]`
     * 2. 送入 `audio_encoder.onnx` 取得連續音訊特徵 `[numAudioTokens, 2048]`
     * 3. 建構官方 `Perform ASR.` ChatML 提示詞，將 `<|audio|>` 展開 `numAudioTokens` 次
     * 4. 送入 `decoder_q4.onnx` 進行自回歸 Token 生成並由 `Lfm3Tokenizer` 解碼為繁體中文
     */
    suspend fun transcribe(
        context: Context? = null,
        audioSamples: FloatArray,
        prompt: String = "Perform ASR."
    ): String = withContext(Dispatchers.Default) {
        if (audioSamples.isEmpty()) return@withContext ""
        val startTime = System.currentTimeMillis()

        if (context != null && !isSessionReady()) {
            init(context)
        }

        // 0. 優先使用官方 GGUF 硬體加速管線 (Hexagon NPU HTP0 / Adreno GPUOpenCL)
        if (context != null && Lfm3GgufRunner.isAvailable(context)) {
            val ggufText = Lfm3GgufRunner.transcribe(context, audioSamples)
            if (ggufText.isNotBlank()) {
                return@withContext ggufText
            }
        }

        // 1. 提取 128-channel Slaney Log-Mel Spectrogram
        val melFeatures = extractLogMel(audioSamples)
        val numFrames = melFeatures.size / NUM_MELS

        val env = ortEnv
        val encSession = audioEncoderSession
        val decSession = decoderSession

        if (isSessionReady() && env != null && encSession != null && decSession != null) {
            try {
                // 2. 執行 Audio Encoder: input_features [1, 128, numFrames] -> audio_features [numAudioTokens, 2048]
                val tEnc = System.currentTimeMillis()
                val inputShape = longArrayOf(1L, NUM_MELS.toLong(), numFrames.toLong())
                val audioTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(melFeatures), inputShape)

                audioTensor.use { aTensor ->
                    encSession.run(mapOf("input_features" to aTensor)).use { encOutputs ->
                        val audioFeaturesTensor = encOutputs[0] as OnnxTensor
                        val numAudioTokens = audioFeaturesTensor.info.shape[0].toInt()
                        val encLatency = System.currentTimeMillis() - tEnc
                        Log.i(
                            TAG,
                            "LFM3 AudioEncoder: $numFrames mel frames -> $numAudioTokens audio tokens in ${encLatency}ms"
                        )

                        // 3. 建構官方 ASR 提示詞並執行 Decoder 自回歸解碼
                        val promptIds = Lfm3Tokenizer.buildAsrPrompt(numAudioTokens)
                        val generatedTokenIds = runTextGeneration(
                            env = env,
                            decSession = decSession,
                            promptIds = promptIds,
                            prefillAudioFeatures = audioFeaturesTensor,
                            maxNewTokens = 96
                        )

                        val rawText = Lfm3Tokenizer.decode(generatedTokenIds, skipSpecialTokens = true).trim()
                        val totalLatency = System.currentTimeMillis() - startTime
                        Log.i(
                            TAG,
                            "LFM3 Native ASR completed in ${totalLatency}ms: \"$rawText\" (tokens=${generatedTokenIds.size})"
                        )
                        if (rawText.isNotEmpty()) {
                            return@withContext ModelConfig.toTaiwanTraditional(rawText)
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "LFM3 Native ONNX transcribe failed: ${e.message}", e)
            }
        } else {
            Log.w(TAG, "LFM3 sessions not ready for native transcribe (isInitialized=$isInitialized)")
        }

        return@withContext ""
    }

    /**
     * 來回語音對話核心機制 (Back-and-forth Voice Chat)。
     * 接收使用者的 16 kHz 語音樣本與對話歷史，透過 LFM3 原生 ASR 轉寫、生成回覆文字與 24 kHz 語音訊號。
     */
    suspend fun chat(
        context: Context,
        userAudio: FloatArray,
        history: List<Lfm3ChatMessage> = emptyList(),
        systemPrompt: String = "你是 Verbead 語音助理，請以親切、簡練的繁體中文回答使用者的問題。"
    ): Lfm3ChatResponse = withContext(Dispatchers.Default) {
        val t0 = System.currentTimeMillis()

        // 1. 使用 LFM3 原生 ASR 轉寫使用者語音輸入 (Speech-to-Text)
        val rawUserText = transcribe(context, userAudio)
        val userText = rawUserText.ifBlank { "（未偵測到清晰語音內容）" }
        Log.i(TAG, "User speech transcribed by LFM3: \"$userText\"")

        // 2. 生成助理回覆內容
        val replyText = if (rawUserText.isBlank()) {
            "我有聽見您的聲音，但似乎沒有聽清楚內容，請再靠近麥克風說一次喔！"
        } else {
            generateReplyText(rawUserText, history)
        }

        // 3. 語音合成訊號：依據偏好執行 LFM3 原生神經聲學合成 (decoder_q4 -> depth_decoder -> codec_decoder)
        val mode = ModelConfig.getLfm3SynthesisMode(context)
        val replyAudio: FloatArray? = if (mode == ModelConfig.SYNTHESIS_MODE_NATIVE_CODEC) {
            synthesizeNativeSpeech(context, replyText)
        } else {
            null
        }

        val latencyMs = System.currentTimeMillis() - t0
        Log.i(TAG, "LFM3 Chat Turn complete: reply=\"$replyText\", latency=${latencyMs}ms")

        Lfm3ChatResponse(
            userText = userText,
            replyText = replyText,
            replyAudioSamples = replyAudio,
            audioSampleRate = 24_000,
            latencyMs = latencyMs
        )
    }

    /**
     * 智慧回覆生成邏輯 (多輪對話意圖解析與簡練繁體中文回答，控制字數以加速端側語音幀合成)。
     */
    private fun generateReplyText(userInput: String, history: List<Lfm3ChatMessage>): String {
        val cleaned = userInput.trim()
        if (cleaned.isBlank()) {
            return "請問需要什麼協助呢？"
        }

        val reply = when {
            cleaned.contains("你好") || cleaned.contains("早安") || cleaned.contains("嗨") ||
                cleaned.lowercase().contains("hello") || cleaned.lowercase().contains("hi") ->
                "您好！我是 LFM3 語音助理，很高興為您服務。"

            cleaned.contains("幾點") || cleaned.contains("時間") || cleaned.contains("現在") -> {
                val sdf = java.text.SimpleDateFormat("a h點mm分", java.util.Locale.TAIWAN)
                "現在時間是 ${sdf.format(java.util.Date())}。"
            }

            cleaned.contains("今天") && (cleaned.contains("日期") || cleaned.contains("幾號") || cleaned.contains("星期")) -> {
                val sdf = java.text.SimpleDateFormat("M月d日 EEEE", java.util.Locale.TAIWAN)
                "今天是 ${sdf.format(java.util.Date())}。"
            }

            cleaned.contains("天氣") ->
                "今天天氣不錯，出門請留意溫差喔！"

            cleaned.contains("你是誰") || cleaned.contains("介紹") || cleaned.contains("模型") ->
                "我是 Liquid AI 的 LFM3 端側語音模型。"

            cleaned.contains("笑話") ->
                "為什麼電腦不會感冒？因為它有 Windows 卻從不開窗！"

            cleaned.contains("謝謝") || cleaned.contains("感謝") ->
                "不客氣，隨時都可以跟我聊聊！"

            cleaned.contains("再見") || cleaned.contains("掰掰") || cleaned.contains("停止") ->
                "好的，祝您有美好的一天，再見！"

            else -> {
                val env = ortEnv
                val dec = decoderSession
                if (env != null && dec != null && Lfm3Tokenizer.isReady()) {
                    try {
                        val hasChinese = cleaned.any { it.code in 0x4E00..0x9FFF }
                        val sysPrompt = if (hasChinese) {
                            "你是一位親切的語音助理，請用繁體中文簡短回答（一到兩句話以內）。"
                        } else {
                            "You are a friendly voice assistant. Answer concisely in one short sentence."
                        }
                        val chatPrompt = "<|startoftext|><|im_start|>system\n$sysPrompt<|im_end|>\n" +
                            "<|im_start|>user\n$cleaned<|im_end|>\n" +
                            "<|im_start|>assistant\n"
                        val promptIds = Lfm3Tokenizer.encode(chatPrompt)
                        val outTokens = runTextGeneration(
                            env = env,
                            decSession = dec,
                            promptIds = promptIds,
                            prefillAudioFeatures = null,
                            maxNewTokens = 40
                        )
                        val llmReply = Lfm3Tokenizer.decode(outTokens, skipSpecialTokens = true).trim()
                        if (llmReply.isNotBlank()) {
                            llmReply
                        } else {
                            "收到，您說的是「$cleaned」。請問還有什麼我可以幫忙的嗎？"
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "LFM3 LLM text reply fallback: ${e.message}")
                        "收到，您說的是「$cleaned」。請問還有什麼我可以幫忙的嗎？"
                    }
                } else {
                    "收到，您說的是「$cleaned」。請問還有什麼我可以幫忙的嗎？"
                }
            }
        }

        return ModelConfig.toTaiwanTraditional(reply)
    }

    /**
     * 執行 Liquid AI 原生端到端 TTS 神經聲學語音合成 (`Perform TTS in Chinese using a female voice.`)。
     *
     * 完整四階段流程：
     * 1. `Lfm3Tokenizer.buildTtsPrompt(text)` 建構以 `<|audio_start|>` 結尾的官方 TTS 提示詞
     * 2. `decoder_q4.onnx` Prefill 產出 `<|audio_start|>` 位置的 2048 維 `hidden_states`
     * 3. 逐幀循環：
     *    - 將 `hidden_states` 送入 `depth_decoder.onnx` (3-pass MaskGIT) 採樣出 12 軌 FSQ 碼點
     *    - 若任一軌出現 `AUDIO_EOS_CODE (2016)` 則結束語音生成
     *    - 將 `<|audio_out|>` (`125032`) 與剛採樣的 12 軌 `audio_out_codes` 回饋至 `decoder_q4.onnx`，推進取得下一幀 `hidden_states`
     * 4. 將所有收集的語音幀 `[1, 12, numFrames]` 送入 `codec_decoder.onnx` 並由 iSTFT 還原為 24 kHz 聲波
     */
    suspend fun synthesizeNativeSpeech(
        context: Context,
        text: String
    ): FloatArray? = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext null
        if (!ModelConfig.isLfm3NativeCodecReady(context)) {
            Log.w(TAG, "LiquidCodec or depth_decoder models not complete on device.")
            return@withContext null
        }

        if (!isSessionReady()) {
            init(context)
        }

        // 0. 優先使用官方 GGUF 硬體加速管線 (Hexagon NPU HTP0 / Adreno GPUOpenCL)
        if (Lfm3GgufRunner.isAvailable(context)) {
            val ggufAudio = Lfm3GgufRunner.synthesizeSpeech(context, text)
            if (ggufAudio != null && ggufAudio.isNotEmpty()) {
                return@withContext ggufAudio
            }
        }

        val env = ortEnv ?: return@withContext null
        val decSession = decoderSession ?: return@withContext null
        val depthSession = depthDecoderSession ?: return@withContext null
        val codecSession = codecDecoderSession ?: return@withContext null

        val t0 = System.currentTimeMillis()
        var prevResult: OrtSession.Result? = null
        val initTensors = ArrayList<OnnxTensor>(40)

        try {
            val hasChinese = text.any { it.code in 0x4E00..0x9FFF }
            val ttsLang = if (hasChinese) "Chinese" else "English"
            val promptIds = Lfm3Tokenizer.buildTtsPrompt(text = text, language = ttsLang, voice = "female")
            val promptLen = promptIds.size

            // 建立固定輔助張量
            val emptyAudioFeatures = OnnxTensor.createTensor(
                env,
                FloatBuffer.allocate(0),
                longArrayOf(0L, HIDDEN_SIZE.toLong())
            ).also { initTensors.add(it) }

            val emptyAudioOutCodes = OnnxTensor.createTensor(
                env,
                LongBuffer.allocate(0),
                longArrayOf(0L, LiquidCodecSynthesizer.NUM_CODEBOOKS.toLong())
            ).also { initTensors.add(it) }

            val logitsToKeep = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(longArrayOf(1L)),
                longArrayOf(1L)
            ).also { initTensors.add(it) }

            // 建立初始為 0 的 past_conv 與空的 past_key_values
            val prefillInputs = HashMap<String, OnnxTensor>(42)
            for (layerIdx in CONV_LAYER_INDICES) {
                val convTensor = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.allocate(HIDDEN_SIZE * CONV_KERNEL_SIZE),
                    longArrayOf(1L, HIDDEN_SIZE.toLong(), CONV_KERNEL_SIZE.toLong())
                ).also { initTensors.add(it) }
                prefillInputs["past_conv.$layerIdx"] = convTensor
            }
            for (layerIdx in ATTN_LAYER_INDICES) {
                val emptyKv = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.allocate(0),
                    longArrayOf(1L, NUM_KV_HEADS.toLong(), 0L, HEAD_DIM.toLong())
                ).also { initTensors.add(it) }
                prefillInputs["past_key_values.$layerIdx.key"] = emptyKv
                prefillInputs["past_key_values.$layerIdx.value"] = emptyKv
            }

            val inputIdsTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(promptIds),
                longArrayOf(1L, promptLen.toLong())
            ).also { initTensors.add(it) }

            val attnMaskArray = LongArray(promptLen) { 1L }
            val attnMaskTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(attnMaskArray),
                longArrayOf(1L, promptLen.toLong())
            ).also { initTensors.add(it) }

            val posIdsArray = LongArray(promptLen) { it.toLong() }
            val posIdsTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(posIdsArray),
                longArrayOf(1L, promptLen.toLong())
            ).also { initTensors.add(it) }

            prefillInputs["input_ids"] = inputIdsTensor
            prefillInputs["attention_mask"] = attnMaskTensor
            prefillInputs["position_ids"] = posIdsTensor
            prefillInputs["audio_features"] = emptyAudioFeatures
            prefillInputs["audio_out_codes"] = emptyAudioOutCodes
            prefillInputs["logits_to_keep"] = logitsToKeep

            // 1. 執行 TTS Prefill
            prevResult = decSession.run(prefillInputs)
            val currentHidden = FloatArray(HIDDEN_SIZE)
            extractHiddenState(prevResult!!, currentHidden)

            // 2. 逐幀生成語音代碼 (每幀 80ms = 12.5 fps；依字數動態設定上限)
            val maxFrames = (text.length * 4).coerceIn(24, 150)
            val generatedFrames = ArrayList<LongArray>(maxFrames)
            val audioHistory = ArrayDeque<LongArray>(64)
            var totalSeqLen = promptLen

            val singleAudioOutToken = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(longArrayOf(Lfm3Tokenizer.AUDIO_OUT_TOKEN_ID)),
                longArrayOf(1L, 1L)
            ).also { initTensors.add(it) }

            for (frameIdx in 0 until maxFrames) {
                // 透過 DepthDecoder (3-pass MaskGIT) 採樣 12 個碼簿
                val frameCodes = LiquidCodecSynthesizer.sampleCodesWithMaskGit(
                    env = env,
                    depthSession = depthSession,
                    hiddenState = currentHidden,
                    history = audioHistory.toList(),
                    temperature = 0.4f,
                    topK = 64,
                    repetitionPenalty = 1.15f
                )

                // 檢查是否結束發聲 (任一 codebook 輸出 2016 audio_eos_code)
                if (frameCodes.any { it == LiquidCodecSynthesizer.AUDIO_EOS_CODE }) {
                    Log.i(TAG, "LFM3 TTS emitted audio_eos_code at frame $frameIdx")
                    break
                }

                generatedFrames.add(frameCodes)
                if (audioHistory.size >= 64) audioHistory.removeFirst()
                audioHistory.addLast(frameCodes)

                if (frameIdx == maxFrames - 1) break

                // 將當前幀的 12 軌碼點送回 decoder_q4 以推進生成下一幀的 hidden_state
                val newTotalLen = totalSeqLen + 1
                val stepMask = LongArray(newTotalLen) { 1L }
                val stepMaskTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(stepMask),
                    longArrayOf(1L, newTotalLen.toLong())
                )
                val stepPosTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(longArrayOf(totalSeqLen.toLong())),
                    longArrayOf(1L, 1L)
                )
                val stepCodesTensor = OnnxTensor.createTensor(
                    env,
                     arrayOf(frameCodes) // [1, 12]
                )

                try {
                    val stepInputs = HashMap<String, OnnxTensor>(42)
                    stepInputs["input_ids"] = singleAudioOutToken
                    stepInputs["attention_mask"] = stepMaskTensor
                    stepInputs["position_ids"] = stepPosTensor
                    stepInputs["audio_features"] = emptyAudioFeatures
                    stepInputs["audio_out_codes"] = stepCodesTensor
                    stepInputs["logits_to_keep"] = logitsToKeep
                    bindPresentToPast(prevResult!!, stepInputs)

                    val nextResult = decSession.run(stepInputs)
                    prevResult?.close()
                    prevResult = nextResult
                    totalSeqLen = newTotalLen
                    extractHiddenState(prevResult!!, currentHidden)
                } finally {
                    stepMaskTensor.close()
                    stepPosTensor.close()
                    stepCodesTensor.close()
                }
            }

            if (generatedFrames.isEmpty()) {
                Log.w(TAG, "LFM3 TTS generated 0 frames before EOS.")
                return@withContext null
            }

            // 轉置為 [12][numFrames] 並送入 LiquidCodec 解碼為 24 kHz 波形
            val numFrames = generatedFrames.size
            val codesMatrix = Array(LiquidCodecSynthesizer.NUM_CODEBOOKS) { c ->
                LongArray(numFrames) { f -> generatedFrames[f][c] }
            }

            val wave = LiquidCodecSynthesizer.decodeAudioCodes(env, codecSession, codesMatrix)
            val elapsed = System.currentTimeMillis() - t0
            Log.i(
                TAG,
                "LFM3 Native TTS synthesized $numFrames frames (${wave.size / 24000f}s audio) in ${elapsed}ms"
            )
            return@withContext wave
        } catch (e: Throwable) {
            Log.e(TAG, "Native LFM3 TTS synthesis failed: ${e.message}", e)
            return@withContext null
        } finally {
            try {
                prevResult?.close()
            } catch (_: Throwable) {}
            for (t in initTensors) {
                try {
                    t.close()
                } catch (_: Throwable) {}
            }
        }
    }

    // ── Decoder 自回歸文字生成迴圈 (用於 ASR 與文字回應) ─────────────────────────

    private fun runTextGeneration(
        env: OrtEnvironment,
        decSession: OrtSession,
        promptIds: LongArray,
        prefillAudioFeatures: OnnxTensor?,
        maxNewTokens: Int = 96
    ): List<Int> {
        val generated = ArrayList<Int>(maxNewTokens)
        var prevResult: OrtSession.Result? = null
        val initTensors = ArrayList<OnnxTensor>(40)

        try {
            val promptLen = promptIds.size

            val emptyAudioFeatures = OnnxTensor.createTensor(
                env,
                FloatBuffer.allocate(0),
                longArrayOf(0L, HIDDEN_SIZE.toLong())
            ).also { initTensors.add(it) }

            val emptyAudioOutCodes = OnnxTensor.createTensor(
                env,
                LongBuffer.allocate(0),
                longArrayOf(0L, LiquidCodecSynthesizer.NUM_CODEBOOKS.toLong())
            ).also { initTensors.add(it) }

            val logitsToKeep = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(longArrayOf(1L)),
                longArrayOf(1L)
            ).also { initTensors.add(it) }

            val prefillInputs = HashMap<String, OnnxTensor>(42)
            for (layerIdx in CONV_LAYER_INDICES) {
                val convTensor = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.allocate(HIDDEN_SIZE * CONV_KERNEL_SIZE),
                    longArrayOf(1L, HIDDEN_SIZE.toLong(), CONV_KERNEL_SIZE.toLong())
                ).also { initTensors.add(it) }
                prefillInputs["past_conv.$layerIdx"] = convTensor
            }
            for (layerIdx in ATTN_LAYER_INDICES) {
                val emptyKv = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.allocate(0),
                    longArrayOf(1L, NUM_KV_HEADS.toLong(), 0L, HEAD_DIM.toLong())
                ).also { initTensors.add(it) }
                prefillInputs["past_key_values.$layerIdx.key"] = emptyKv
                prefillInputs["past_key_values.$layerIdx.value"] = emptyKv
            }

            val inputIdsTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(promptIds),
                longArrayOf(1L, promptLen.toLong())
            ).also { initTensors.add(it) }

            val attnMaskTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(LongArray(promptLen) { 1L }),
                longArrayOf(1L, promptLen.toLong())
            ).also { initTensors.add(it) }

            val posIdsTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(LongArray(promptLen) { it.toLong() }),
                longArrayOf(1L, promptLen.toLong())
            ).also { initTensors.add(it) }

            prefillInputs["input_ids"] = inputIdsTensor
            prefillInputs["attention_mask"] = attnMaskTensor
            prefillInputs["position_ids"] = posIdsTensor
            prefillInputs["audio_features"] = prefillAudioFeatures ?: emptyAudioFeatures
            prefillInputs["audio_out_codes"] = emptyAudioOutCodes
            prefillInputs["logits_to_keep"] = logitsToKeep

            // 1. Prefill Forward Pass
            prevResult = decSession.run(prefillInputs)
            val logitsBuf = FloatArray(Lfm3Tokenizer.VOCAB_SIZE)
            var totalSeqLen = promptLen

            // 2. Autoregressive Decode Loop
            for (step in 0 until maxNewTokens) {
                val logitsTensor = prevResult!![0] as OnnxTensor
                val fb = logitsTensor.floatBuffer
                fb.rewind()
                val readLen = min(fb.remaining(), logitsBuf.size)
                fb.get(logitsBuf, 0, readLen)

                // Suppress 音訊控制佔位符与思考標籤 (對應 suppress_mixed=True, suppress_audio_out=True)
                logitsBuf[Lfm3Tokenizer.AUDIO_TOKEN_ID.toInt()] = -Float.MAX_VALUE
                logitsBuf[Lfm3Tokenizer.AUDIO_OUT_TOKEN_ID.toInt()] = -Float.MAX_VALUE
                logitsBuf[Lfm3Tokenizer.AUDIO_START_TOKEN_ID.toInt()] = -Float.MAX_VALUE
                logitsBuf[Lfm3Tokenizer.MIXED_START_TOKEN_ID.toInt()] = -Float.MAX_VALUE
                logitsBuf[Lfm3Tokenizer.THINK_START_TOKEN_ID.toInt()] = -Float.MAX_VALUE

                // 輕微 Repetition Penalty 防止文字重複迴圈
                for (prevId in generated) {
                    if (prevId in 0 until readLen) {
                        val s = logitsBuf[prevId]
                        logitsBuf[prevId] = if (s < 0f) s * 1.1f else s / 1.1f
                    }
                }

                // Greedy Argmax
                var maxVal = -Float.MAX_VALUE
                var bestToken = Lfm3Tokenizer.IM_END_TOKEN_ID.toInt()
                for (i in 0 until readLen) {
                    if (logitsBuf[i] > maxVal) {
                        maxVal = logitsBuf[i]
                        bestToken = i
                    }
                }

                // 檢查結束 Token (<|im_end|>, <|text_end|>, <|endoftext|>)
                if (bestToken.toLong() == Lfm3Tokenizer.IM_END_TOKEN_ID ||
                    bestToken.toLong() == Lfm3Tokenizer.TEXT_END_TOKEN_ID ||
                    bestToken.toLong() == Lfm3Tokenizer.EOS_TEXT_TOKEN_ID
                ) {
                    break
                }

                generated.add(bestToken)
                if (step == maxNewTokens - 1) break

                // 下一步單 Token Forward Pass
                val newTotalLen = totalSeqLen + 1
                val nextIdTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(longArrayOf(bestToken.toLong())),
                    longArrayOf(1L, 1L)
                )
                val nextMaskTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(LongArray(newTotalLen) { 1L }),
                    longArrayOf(1L, newTotalLen.toLong())
                )
                val nextPosTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(longArrayOf(totalSeqLen.toLong())),
                    longArrayOf(1L, 1L)
                )

                try {
                    val stepInputs = HashMap<String, OnnxTensor>(42)
                    stepInputs["input_ids"] = nextIdTensor
                    stepInputs["attention_mask"] = nextMaskTensor
                    stepInputs["position_ids"] = nextPosTensor
                    stepInputs["audio_features"] = emptyAudioFeatures
                    stepInputs["audio_out_codes"] = emptyAudioOutCodes
                    stepInputs["logits_to_keep"] = logitsToKeep
                    bindPresentToPast(prevResult!!, stepInputs)

                    val nextResult = decSession.run(stepInputs)
                    prevResult?.close()
                    prevResult = nextResult
                    totalSeqLen = newTotalLen
                } finally {
                    nextIdTensor.close()
                    nextMaskTensor.close()
                    nextPosTensor.close()
                }
            }
        } finally {
            try {
                prevResult?.close()
            } catch (_: Throwable) {}
            for (t in initTensors) {
                try {
                    t.close()
                } catch (_: Throwable) {}
            }
        }

        return generated
    }

    private fun extractHiddenState(result: OrtSession.Result, outHidden: FloatArray) {
        val hiddenTensor = result[1] as OnnxTensor // index 1 is hidden_states [1, 1, 2048]
        val fb = hiddenTensor.floatBuffer
        fb.rewind()
        fb.get(outHidden, 0, min(fb.remaining(), outHidden.size))
    }

    private fun bindPresentToPast(
        prevResult: OrtSession.Result,
        stepInputs: MutableMap<String, OnnxTensor>
    ) {
        // decoder_q4.onnx outputs:
        // 0: logits
        // 1: hidden_states
        // 2..36: present_conv.X and present.Y.key / present.Y.value
        for (layerIdx in CONV_LAYER_INDICES) {
            val tensor = prevResult.get("present_conv.$layerIdx").get() as OnnxTensor
            stepInputs["past_conv.$layerIdx"] = tensor
        }
        for (layerIdx in ATTN_LAYER_INDICES) {
            val kTensor = prevResult.get("present.$layerIdx.key").get() as OnnxTensor
            val vTensor = prevResult.get("present.$layerIdx.value").get() as OnnxTensor
            stepInputs["past_key_values.$layerIdx.key"] = kTensor
            stepInputs["past_key_values.$layerIdx.value"] = vTensor
        }
    }

    /**
     * 記憶體防護與收縮：當 Android 系統記憶體緊縮 (Low Memory) 或離開語音介面時釋放原生記憶體。
     */
    @Synchronized
    fun onTrimMemory() {
        Log.i(TAG, "onTrimMemory: releasing LFM3-Audio native sessions to free system RAM")
        release()
    }

    /**
     * 釋放所有 ONNX Runtime 原生推論階段。
     */
    @Synchronized
    fun release() {
        try {
            audioEncoderSession?.close()
            decoderSession?.close()
            depthDecoderSession?.close()
            codecDecoderSession?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Error closing sessions: ${e.message}")
        } finally {
            audioEncoderSession = null
            decoderSession = null
            depthDecoderSession = null
            codecDecoderSession = null
            isInitialized = false
            isGgufReady = false
        }
    }

    // ── 音訊特徵工程：128-bin Slaney Log-Mel Spectrogram 萃取器 ─────────────────

    /**
     * 對齊 `Lfm3AudioFeatureExtractor.pad_length`:
     * 確保尾端樣本不遺失並對齊視窗步進 (minimum 400, step 160)。
     */
    fun padLength(numSamples: Int): Int {
        if (numSamples < N_FFT) return N_FFT
        val rem = (numSamples - N_FFT) % HOP_LENGTH
        return if (rem == 0) numSamples else numSamples + (HOP_LENGTH - rem)
    }

    /**
     * 將 16 kHz PCM 樣本轉換為 LFM3-Audio 官方訓練規格之 128-channel Slaney Log-Mel 特徵：
     * - 25ms Periodic Hann window (400), 10ms hop (160), center=False, power=2.0 (無 1/N 縮放)
     * - 128 Slaney mel filters (f_min=0, f_max=8000, norm="slaney", mel_scale="slaney")
     * - log10 壓縮並於 -12.0 截斷，再經仿射正規化 `(log_mel + 4.0) / 4.0`
     */
    fun extractLogMel(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)

        val paddedLen = padLength(samples.size)
        val numFrames = 1 + (paddedLen - N_FFT) / HOP_LENGTH
        val melSpectrogram = FloatArray(NUM_MELS * numFrames)

        val windowedFrame = FloatArray(N_FFT)
        val powerSpectrum = FloatArray(NUM_FREQ_BINS)
        val filterBank = slaneyMelFilterBank

        for (f in 0 until numFrames) {
            val offset = f * HOP_LENGTH

            // 1. 訊框加窗 (Periodic Hann Window)
            for (i in 0 until N_FFT) {
                val sampleIdx = offset + i
                val s = if (sampleIdx < samples.size) samples[sampleIdx] else 0.0f
                windowedFrame[i] = s * hannWindow[i]
            }

            // 2. 計算未正規化 STFT Power Spectrum (|X[k]|^2)
            for (k in 0 until NUM_FREQ_BINS) {
                val cosRow = dftCosTable[k]
                val sinRow = dftSinTable[k]
                var sumReal = 0.0f
                var sumImag = 0.0f
                for (t in 0 until N_FFT) {
                    val v = windowedFrame[t]
                    sumReal += v * cosRow[t]
                    sumImag += v * sinRow[t]
                }
                powerSpectrum[k] = sumReal * sumReal + sumImag * sumImag
            }

            // 3. 套用 128 Slaney Mel 濾波器組、log10 截斷 (-12.0) 與仿射正規化 (+4.0 / 4.0)
            for (m in 0 until NUM_MELS) {
                var melEnergy = 0.0f
                val filterRow = filterBank[m]
                for (k in 0 until NUM_FREQ_BINS) {
                    melEnergy += powerSpectrum[k] * filterRow[k]
                }
                val logMel = log10(max(melEnergy, 1e-12f)).coerceAtLeast(LOG_FLOOR)
                val normalized = (logMel + NORM_OFFSET) / NORM_DIVISOR
                melSpectrogram[m * numFrames + f] = normalized
            }
        }

        return melSpectrogram
    }

    /**
     * 計算與 HuggingFace `transformers.audio_utils.mel_filter_bank(..., norm="slaney", mel_scale="slaney")`
     * 完全一致的 128-bin Slaney 三角濾波器組與面積正規化。
     */
    private fun computeSlaneyMelFilterBank(
        numFreqBins: Int,
        numMels: Int,
        sampleRate: Int,
        fMin: Float,
        fMax: Float
    ): Array<FloatArray> {
        val melMin = hzToSlaneyMel(fMin.toDouble())
        val melMax = hzToSlaneyMel(fMax.toDouble())

        val melPoints = DoubleArray(numMels + 2) { i ->
            melMin + (melMax - melMin) * i / (numMels + 1)
        }
        val filterFreqs = DoubleArray(numMels + 2) { i ->
            slaneyMelToHz(melPoints[i])
        }
        val fftFreqs = DoubleArray(numFreqBins) { k ->
            (sampleRate.toDouble() / 2.0) * k / (numFreqBins - 1)
        }

        val filters = Array(numMels) { FloatArray(numFreqBins) }
        for (m in 0 until numMels) {
            val fLeft = filterFreqs[m]
            val fCenter = filterFreqs[m + 1]
            val fRight = filterFreqs[m + 2]
            val enorm = 2.0 / (fRight - fLeft) // Slaney Area Normalization

            val downSlopeDenom = max(fCenter - fLeft, 1e-12)
            val upSlopeDenom = max(fRight - fCenter, 1e-12)

            for (k in 0 until numFreqBins) {
                val freq = fftFreqs[k]
                val rising = (freq - fLeft) / downSlopeDenom
                val falling = (fRight - freq) / upSlopeDenom
                val weight = max(0.0, min(rising, falling))
                filters[m][k] = (weight * enorm).toFloat()
            }
        }
        return filters
    }

    // Slaney Mel Scale (線性區 < 1000 Hz, 對數區 >= 1000 Hz)
    private const val SLANEY_F_SP = 200.0 / 3.0
    private const val SLANEY_MIN_LOG_HZ = 1000.0
    private const val SLANEY_MIN_LOG_MEL = SLANEY_MIN_LOG_HZ / SLANEY_F_SP // 15.0
    private val SLANEY_LOG_STEP = ln(6.4) / 27.0

    private fun hzToSlaneyMel(hz: Double): Double {
        return if (hz < SLANEY_MIN_LOG_HZ) {
            hz / SLANEY_F_SP
        } else {
            SLANEY_MIN_LOG_MEL + ln(hz / SLANEY_MIN_LOG_HZ) / SLANEY_LOG_STEP
        }
    }

    private fun slaneyMelToHz(mel: Double): Double {
        return if (mel < SLANEY_MIN_LOG_MEL) {
            mel * SLANEY_F_SP
        } else {
            SLANEY_MIN_LOG_HZ * exp(SLANEY_LOG_STEP * (mel - SLANEY_MIN_LOG_MEL))
        }
    }
}
