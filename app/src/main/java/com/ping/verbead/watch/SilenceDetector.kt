package com.ping.verbead.watch

import kotlin.math.pow

/**
 * 以 RMS 做的兩階段語音端點偵測，語意與 AudioRecorder.recordStreaming 相同：
 *  1. 等待開口：連續達到說話門檻累積 [minSpeechSeconds] 才算開口；
 *     超過 [initialTimeoutSeconds] 一直沒開口 → [Event.NO_SPEECH]。
 *  2. 已開口：低於靜音門檻的靜音累積達 [trailingSilenceSeconds]，
 *     且總長度已達 [minSeconds] → [Event.AUTO_STOP]。
 *
 * 手錶麥克風經 Speex 壓縮後音量遠低於手機麥克風，固定的絕對門檻會把「小聲說話」誤判成靜音而
 * 在講到一半時停止。因此 [speechThreshold] / [silenceThreshold] 只當作上限，實際門檻依
 * 目前的雜訊底與說話音量自動下修（只會讓停止更保守，不會比固定門檻更容易停）。
 *
 * 以「樣本數」計時，因此不受網路/藍牙傳輸抖動影響。
 */
class SilenceDetector(
    private val sampleRate: Int,
    private val speechThreshold: Float,
    private val silenceThreshold: Float,
    trailingSilenceSeconds: Float,
    initialTimeoutSeconds: Float,
    minSeconds: Float,
    minSpeechSeconds: Float = 0.10f,
) {
    enum class Event { NONE, AUTO_STOP, NO_SPEECH }

    private val trailingFrames = (trailingSilenceSeconds * sampleRate).toLong()
    private val initialTimeoutFrames = (initialTimeoutSeconds * sampleRate).toLong()
    private val minFrames = (minSeconds * sampleRate).toLong()
    private val minSpeechFrames = (minSpeechSeconds * sampleRate).toLong()

    private var totalFrames = 0L
    private var hasSpoken = false
    private var consecutiveSpeech = 0L
    private var initialSilence = 0L
    private var trailingSilence = 0L

    /** 開口前的雜訊底：往下立即跟隨，往上緩慢爬升；尚未收到音訊時為 -1。 */
    private var noiseFloor = -1f
    /** 開口後的說話音量：峰值跟隨，隨時間緩慢衰減。 */
    private var speechLevel = 0f

    val spoken: Boolean get() = hasSpoken

    /** 目前實際使用的門檻，供除錯記錄。 */
    val effectiveSpeechThreshold: Float get() = speechGate()
    val effectiveSilenceThreshold: Float get() = silenceGate()
    val currentNoiseFloor: Float get() = noiseFloor.coerceAtLeast(0f)
    val currentSpeechLevel: Float get() = speechLevel

    private fun speechGate(): Float =
        (noiseFloor.coerceAtLeast(0f) * SPEECH_OVER_NOISE)
            .coerceAtLeast(MIN_SPEECH_RMS)
            .coerceAtMost(speechThreshold)

    private fun silenceGate(): Float =
        maxOf(noiseFloor.coerceAtLeast(0f) * SILENCE_OVER_NOISE, speechLevel * SILENCE_OVER_SPEECH)
            .coerceAtLeast(MIN_SILENCE_RMS)
            .coerceAtMost(silenceThreshold)

    fun feed(samples: FloatArray): Event {
        val n = samples.size.toLong()
        if (n == 0L) return Event.NONE
        val rms = PcmUtil.rms(samples)
        totalFrames += n

        if (!hasSpoken) {
            if (rms >= speechGate()) {
                consecutiveSpeech += n
                if (consecutiveSpeech >= minSpeechFrames) {
                    hasSpoken = true
                    trailingSilence = 0
                    speechLevel = rms
                }
            } else {
                noiseFloor = when {
                    noiseFloor < 0f || rms < noiseFloor -> rms
                    else -> noiseFloor + (rms - noiseFloor) * NOISE_RISE
                }
                consecutiveSpeech = maxOf(0L, consecutiveSpeech - n / 2)
                initialSilence += n
                if (initialSilence >= initialTimeoutFrames) return Event.NO_SPEECH
            }
            return Event.NONE
        }

        speechLevel = if (rms > speechLevel) rms
        else speechLevel * SPEECH_DECAY_PER_SECOND.pow(n.toFloat() / sampleRate)

        if (rms < silenceGate()) trailingSilence += n else trailingSilence = 0
        return if (totalFrames >= minFrames && trailingSilence >= trailingFrames) Event.AUTO_STOP else Event.NONE
    }

    private companion object {
        /** 說話門檻下限，避免把底噪當成說話。 */
        const val MIN_SPEECH_RMS = 0.006f
        /** 靜音門檻下限。 */
        const val MIN_SILENCE_RMS = 0.003f
        const val SPEECH_OVER_NOISE = 3f
        const val SILENCE_OVER_NOISE = 2f
        /** 低於說話音量的這個比例才算靜音；小聲說話（約為大聲時的 1/10~1/5）不會被誤判。 */
        const val SILENCE_OVER_SPEECH = 0.05f
        const val NOISE_RISE = 0.05f
        const val SPEECH_DECAY_PER_SECOND = 0.9f
    }
}
