package com.ping.verbead.watch

import kotlin.math.sqrt

/** PCM 相關的純運算，無 Android 相依。 */
object PcmUtil {

    /** 16-bit little-endian 單聲道 → [-1, 1) 浮點。尾端落單的位元組會被丟棄。 */
    fun toFloats(pcm16le: ByteArray): FloatArray {
        val n = pcm16le.size / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val lo = pcm16le[2 * i].toInt() and 0xFF
            val hi = pcm16le[2 * i + 1].toInt() // 帶號
            out[i] = ((hi shl 8) or lo).toShort() / 32768.0f
        }
        return out
    }

    /** 去除直流偏移後的 RMS，與 AudioRecorder 的算法一致。 */
    fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var mean = 0.0
        for (s in samples) mean += s
        mean /= samples.size
        var sumSq = 0.0
        for (s in samples) {
            val d = s - mean
            sumSq += d * d
        }
        return sqrt(sumSq / samples.size).toFloat()
    }
}
