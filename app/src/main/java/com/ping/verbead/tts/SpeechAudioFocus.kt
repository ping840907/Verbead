package com.ping.verbead.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Audio focus for reading aloud: other media is paused while the text is spoken, and the reading is
 * paused when a call or another player takes focus. [onLost] is called on the main thread.
 */
class SpeechAudioFocus(context: Context, private val onLost: () -> Unit) {

    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var request: AudioFocusRequest? = null

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onLost()
        // a ducking request is ignored: speech should stay at full volume, and music ducks itself
    }

    fun acquire(): Boolean {
        if (request != null) return true
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(listener)
            .build()
        val granted = audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) request = req
        return granted
    }

    fun release() {
        request?.let { audioManager.abandonAudioFocusRequest(it) }
        request = null
    }
}
