package com.ping.verbead.watch

/**
 * 以 RMS 做的兩階段語音端點偵測，語意與 AudioRecorder.recordStreaming 相同：
 *  1. 等待開口：連續達到 [speechThreshold] 累積 [minSpeechSeconds] 才算開口；
 *     超過 [initialTimeoutSeconds] 一直沒開口 → [Event.NO_SPEECH]。
 *  2. 已開口：低於 [silenceThreshold] 的靜音累積達 [trailingSilenceSeconds]，
 *     且總長度已達 [minSeconds] → [Event.AUTO_STOP]。
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

    val spoken: Boolean get() = hasSpoken

    fun feed(samples: FloatArray): Event {
        val n = samples.size.toLong()
        if (n == 0L) return Event.NONE
        val rms = PcmUtil.rms(samples)
        totalFrames += n

        if (!hasSpoken) {
            if (rms >= speechThreshold) {
                consecutiveSpeech += n
                if (consecutiveSpeech >= minSpeechFrames) {
                    hasSpoken = true
                    trailingSilence = 0
                }
            } else {
                consecutiveSpeech = maxOf(0L, consecutiveSpeech - n / 2)
                initialSilence += n
                if (initialSilence >= initialTimeoutFrames) return Event.NO_SPEECH
            }
            return Event.NONE
        }

        if (rms < silenceThreshold) trailingSilence += n else trailingSilence = 0
        return if (totalFrames >= minFrames && trailingSilence >= trailingFrames) Event.AUTO_STOP else Event.NONE
    }
}
