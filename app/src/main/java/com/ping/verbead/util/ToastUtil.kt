package com.ping.verbead.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.StringRes

/**
 * The one way the app shows a short notice that is not a bubble state.
 *
 * - The card next to the bubble only shows what the bubble is doing (listening, loading, live text). Everything else
 *   ("copied", "nothing detected", errors ...) goes through here, or is not shown at all when the visible state already
 *   says what happened.
 * - Duration follows the length of the text: a long message (an error with details) gets the long toast so it can be read.
 * - The same text is not shown twice in a short time (a repeated tap or a retry loop would otherwise stack identical toasts).
 */
object ToastUtil {
    /** Text weight above which the long toast is used (CJK and other wide characters count double). */
    const val LONG_WEIGHT = 24

    /** The same text is suppressed for this long after it was shown. */
    const val DEBOUNCE_MS = 2000L

    private val main = Handler(Looper.getMainLooper())
    private val debouncer = Debouncer(DEBOUNCE_MS)

    /** Reading length of [text]: ASCII counts 1, everything else 2. */
    fun weight(text: CharSequence): Int {
        var w = 0
        for (c in text) w += if (c.code < 0x80) 1 else 2
        return w
    }

    /** [Toast.LENGTH_LONG] for long text, [Toast.LENGTH_SHORT] otherwise. */
    fun durationFor(text: CharSequence): Int =
        if (weight(text) > LONG_WEIGHT) Toast.LENGTH_LONG else Toast.LENGTH_SHORT

    /** Remembers the last text and when it was shown. */
    class Debouncer(private val windowMs: Long) {
        private var lastText: String? = null
        private var lastAtMs = Long.MIN_VALUE / 2

        /** true if [text] may be shown at [nowMs]; records it when it is. */
        @Synchronized
        fun allow(text: String, nowMs: Long): Boolean {
            if (text == lastText && nowMs - lastAtMs < windowMs) return false
            lastText = text
            lastAtMs = nowMs
            return true
        }
    }

    fun show(context: Context, text: String) {
        if (text.isBlank()) return
        if (!debouncer.allow(text, android.os.SystemClock.elapsedRealtime())) return
        val app = context.applicationContext
        val duration = durationFor(text)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(app, text, duration).show()
        } else {
            main.post { Toast.makeText(app, text, duration).show() }
        }
    }

    fun show(context: Context, @StringRes id: Int) = show(context, context.getString(id))
}
