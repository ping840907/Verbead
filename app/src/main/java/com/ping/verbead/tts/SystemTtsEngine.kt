package com.ping.verbead.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/** The device's own text-to-speech engine: no download, always available as a fallback. */
class SystemTtsEngine(context: Context) : TtsEngine {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var callback: TtsEngine.Callback? = null
    private var ready = false
    private var lastLang: SpeechLang? = null

    override fun init(callback: TtsEngine.Callback, onReady: (Boolean) -> Unit) {
        this.callback = callback
        tts = TextToSpeech(appContext) { status ->
            // the init callback already runs on the main thread, but post anyway so it never re-enters the caller
            main.post {
                val engine = tts
                if (status != TextToSpeech.SUCCESS || engine == null) {
                    onReady(false)
                    return@post
                }
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                engine.setOnUtteranceProgressListener(progressListener)
                ready = true
                onReady(true)
            }
        }
    }

    override fun speak(utteranceId: Int, text: String, lang: SpeechLang, rate: Float) {
        val engine = tts ?: return
        if (!ready) return
        if (lang != lastLang) {
            val locale = when (lang) {
                SpeechLang.ZH -> Locale.TRADITIONAL_CHINESE
                SpeechLang.JA -> Locale.JAPANESE
                SpeechLang.KO -> Locale.KOREAN
                SpeechLang.OTHER -> Locale.getDefault().takeIf { it.language != "zh" && it.language != "ja" && it.language != "ko" } ?: Locale.US
            }
            val result = engine.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "no system voice for $locale (result=$result), keeping the engine default")
            }
            lastLang = lang
        }
        engine.setSpeechRate(rate)
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId.toString())
        if (result != TextToSpeech.SUCCESS) main.post { callback?.onError(utteranceId) }
    }

    override fun stop() {
        tts?.stop()
    }

    override fun shutdown() {
        ready = false
        tts?.setOnUtteranceProgressListener(null)
        tts?.stop()
        tts?.shutdown()
        tts = null
        callback = null
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = post(utteranceId) { onStart(it) }
        override fun onDone(utteranceId: String?) = post(utteranceId) { onDone(it) }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = post(utteranceId) { onError(it) }
        override fun onError(utteranceId: String?, errorCode: Int) = post(utteranceId) { onError(it) }
        override fun onStop(utteranceId: String?, interrupted: Boolean) = Unit // a skip or stop is not a result
    }

    /** The engine reports on a binder thread; the session wants the main thread. */
    private inline fun post(utteranceId: String?, crossinline block: TtsEngine.Callback.(Int) -> Unit) {
        val id = utteranceId?.toIntOrNull() ?: return
        main.post { callback?.block(id) }
    }

    private companion object { const val TAG = "SystemTtsEngine" }
}
