package com.ping.verbead.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 輕量化 PCM 音訊播放器，專為 16kHz Mono 16-bit 語音音訊回放設計。
 * 支援播放進度回呼、即時停止與安全釋放。
 */
class PcmPlayer {

    companion object {
        private const val TAG = "PcmPlayer"
    }

    private var audioTrack: AudioTrack? = null
    private val isPlayingState = AtomicBoolean(false)

    fun isPlaying(): Boolean = isPlayingState.get()

    /**
     * 停止當前播放並安全釋放硬體 AudioTrack 資源。
     */
    @Synchronized
    fun stop() {
        isPlayingState.set(false)
        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack stop error: ${e.message}")
        } finally {
            audioTrack = null
        }
    }

    /**
     * 播放正規化至 [-1.0f, 1.0f] 之 32-bit Float 音訊。
     */
    suspend fun playFloats(
        floats: FloatArray,
        sampleRate: Int = 16_000,
        onProgress: ((Float) -> Unit)? = null,
        onCompletion: (() -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        if (floats.isEmpty()) {
            withContext(Dispatchers.Main) { onCompletion?.invoke() }
            return@withContext
        }
        val shorts = ShortArray(floats.size) { i ->
            (floats[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
        }
        playShorts(shorts, sampleRate, onProgress, onCompletion)
    }

    /**
     * 播放 16-bit PCM ShortArray 音訊。
     */
    suspend fun playShorts(
        pcmShorts: ShortArray,
        sampleRate: Int = 16_000,
        onProgress: ((Float) -> Unit)? = null,
        onCompletion: (() -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        stop()
        if (pcmShorts.isEmpty()) {
            withContext(Dispatchers.Main) { onCompletion?.invoke() }
            return@withContext
        }

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, 2048)

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack init failed: ${e.message}", e)
            withContext(Dispatchers.Main) { onCompletion?.invoke() }
            return@withContext
        }

        synchronized(this@PcmPlayer) {
            audioTrack = track
            isPlayingState.set(true)
        }

        try {
            track.play()
            val chunkSize = 1024
            var written = 0
            val total = pcmShorts.size

            while (written < total && isPlayingState.get()) {
                val toWrite = minOf(chunkSize, total - written)
                val ret = track.write(pcmShorts, written, toWrite)
                if (ret < 0) {
                    Log.w(TAG, "AudioTrack.write error code: $ret")
                    break
                }
                written += ret
                val progress = written.toFloat() / total
                withContext(Dispatchers.Main) {
                    onProgress?.invoke(progress)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio playback error: ${e.message}", e)
        } finally {
            synchronized(this@PcmPlayer) {
                isPlayingState.set(false)
                try {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.stop()
                    }
                    track.release()
                } catch (_: Exception) {}
                if (audioTrack === track) {
                    audioTrack = null
                }
            }
            withContext(Dispatchers.Main) {
                onProgress?.invoke(0.0f)
                onCompletion?.invoke()
            }
        }
    }
}
