package com.ping.verbead.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 輕量級 PCM 音訊播放器，支援 16kHz 與 24kHz Mono 16-bit / Float PCM 即時回放。
 * 用於 LFM3 語音合成波形、LiquidCodec 輸出以及對比音訊播放。
 */
class PcmPlayer {

    companion object {
        private const val TAG = "PcmPlayer"
    }

    private var audioTrack: AudioTrack? = null
    @Volatile private var isPlayingFlag = false

    fun isPlaying(): Boolean = isPlayingFlag

    /**
     * 播放 32-bit Float PCM 音訊 (正規化 [-1.0f, 1.0f])。
     */
    suspend fun play(
        samples: FloatArray,
        sampleRate: Int = 24_000,
        onCompletion: (() -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        stop()
        if (samples.isEmpty()) {
            withContext(Dispatchers.Main) { onCompletion?.invoke() }
            return@withContext
        }

        try {
            val minBufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT
            )
            val bufSize = maxOf(minBufSize, samples.size * 4)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack = track
            isPlayingFlag = true

            track.play()
            val written = track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            Log.d(TAG, "Written $written float samples to AudioTrack ($sampleRate Hz)")

            // 等待播放完畢
            val durationMs = (samples.size.toDouble() / sampleRate * 1000).toLong()
            kotlinx.coroutines.delay(durationMs + 100)

            track.stop()
            track.release()
            audioTrack = null
            isPlayingFlag = false

            withContext(Dispatchers.Main) {
                onCompletion?.invoke()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Float PCM playback error: ${e.message}", e)
            stop()
            withContext(Dispatchers.Main) {
                onCompletion?.invoke()
            }
        }
    }

    /**
     * 播放 16-bit Short PCM 音訊。
     */
    suspend fun play(
        samples: ShortArray,
        sampleRate: Int = 16_000,
        onCompletion: (() -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        stop()
        if (samples.isEmpty()) {
            withContext(Dispatchers.Main) { onCompletion?.invoke() }
            return@withContext
        }

        try {
            val minBufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBufSize, samples.size * 2)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack = track
            isPlayingFlag = true

            track.play()
            val written = track.write(samples, 0, samples.size)
            Log.d(TAG, "Written $written short samples to AudioTrack ($sampleRate Hz)")

            val durationMs = (samples.size.toDouble() / sampleRate * 1000).toLong()
            kotlinx.coroutines.delay(durationMs + 100)

            track.stop()
            track.release()
            audioTrack = null
            isPlayingFlag = false

            withContext(Dispatchers.Main) {
                onCompletion?.invoke()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Short PCM playback error: ${e.message}", e)
            stop()
            withContext(Dispatchers.Main) {
                onCompletion?.invoke()
            }
        }
    }

    @Synchronized
    fun stop() {
        isPlayingFlag = false
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Throwable) {
            Log.w(TAG, "Error stopping AudioTrack: ${e.message}")
        } finally {
            audioTrack = null
        }
    }
}
