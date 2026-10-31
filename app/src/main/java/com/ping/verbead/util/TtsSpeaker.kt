package com.ping.verbead.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * 輕量級高品質繁體中文語音合成器 (Text-to-Speech)，
 * 用於 LFM3 語音助理多輪對話時的自然人聲朗讀與即時連續交互。
 */
class TtsSpeaker(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TtsSpeaker"
        private const val UTTERANCE_PREFIX = "lfm3_tts_"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    @Volatile private var isReady = false

    private var pendingText: String? = null
    private var pendingOnStart: (() -> Unit)? = null
    private var pendingOnDone: (() -> Unit)? = null

    private var currentOnStart: (() -> Unit)? = null
    private var currentOnDone: (() -> Unit)? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            // 優先設定台灣繁體中文 (zh_TW)
            var result = engine.setLanguage(Locale.TAIWAN)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                result = engine.setLanguage(Locale.TRADITIONAL_CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    result = engine.setLanguage(Locale.CHINESE)
                }
            }

            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )

            // 音調與語速微調 (稍微輕快自然符合對話助理風格)
            engine.setPitch(1.0f)
            engine.setSpeechRate(1.1f)

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    mainHandler.post {
                        currentOnStart?.invoke()
                    }
                }

                override fun onDone(utteranceId: String?) {
                    mainHandler.post {
                        val cb = currentOnDone
                        currentOnDone = null
                        cb?.invoke()
                    }
                }

                override fun onError(utteranceId: String?) {
                    Log.w(TAG, "TTS playback error for utterance: $utteranceId")
                    mainHandler.post {
                        val cb = currentOnDone
                        currentOnDone = null
                        cb?.invoke()
                    }
                }
            })

            isReady = true
            Log.i(TAG, "TextToSpeech initialized successfully with language: ${engine.voice?.locale ?: "default"}")

            // 執行待發音之請求
            pendingText?.let { text ->
                val onStart = pendingOnStart
                val onDone = pendingOnDone
                pendingText = null
                pendingOnStart = null
                pendingOnDone = null
                speak(text, onStart, onDone)
            }
        } else {
            Log.e(TAG, "Failed to initialize TextToSpeech engine, status: $status")
            isReady = false
        }
    }

    /**
     * 朗讀指定繁體中文文字。
     */
    fun speak(text: String, onStart: (() -> Unit)? = null, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) {
            mainHandler.post { onDone?.invoke() }
            return
        }

        if (!isReady) {
            Log.d(TAG, "TTS not ready yet, queuing text: \"$text\"")
            pendingText = text
            pendingOnStart = onStart
            pendingOnDone = onDone
            return
        }

        currentOnStart = onStart
        currentOnDone = onDone

        val utteranceId = "$UTTERANCE_PREFIX${System.currentTimeMillis()}"
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }

        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speak() failed with code $result, calling onDone fallback")
            currentOnStart = null
            val cb = currentOnDone
            currentOnDone = null
            mainHandler.post { cb?.invoke() }
        }
    }

    /**
     * 立即中斷播放 (插話打斷支援)。
     */
    fun stop() {
        try {
            pendingText = null
            pendingOnStart = null
            pendingOnDone = null
            currentOnStart = null
            val cb = currentOnDone
            currentOnDone = null
            tts?.stop()
        } catch (e: Throwable) {
            Log.w(TAG, "Error stopping TTS: ${e.message}")
        }
    }

    fun isSpeaking(): Boolean = isReady && tts?.isSpeaking == true

    /**
     * 釋放資源。
     */
    fun release() {
        stop()
        try {
            tts?.shutdown()
            tts = null
            isReady = false
        } catch (e: Throwable) {
            Log.w(TAG, "Error shutting down TTS: ${e.message}")
        }
    }
}
