package com.ping.verbead.tts

/**
 * Decides when the screen has come to rest after a scroll, from what the screen says, instead of a fixed wait.
 *
 * An app reports a scroll as done before its content has caught up (a browser's page text lags), so the screen is
 * sampled: once its text differs from what it was before the scroll and is the same in two samples in a row, it
 * has settled. If it never differs, the scroll did nothing (the end of the page). Pure: the caller does the
 * sampling and the waiting.
 */
class ScrollSettle(private val before: String, private val timeoutMs: Long = TIMEOUT_MS) {

    enum class Verdict { WAIT, SETTLED, UNCHANGED }

    private var last: String? = null

    /** [sample] is the screen's text now, [elapsedMs] the time since the scroll was asked. */
    fun onSample(sample: String, elapsedMs: Long): Verdict {
        val changed = sample != before
        val steady = sample == last
        last = sample
        return when {
            changed && steady -> Verdict.SETTLED
            elapsedMs >= timeoutMs -> if (changed) Verdict.SETTLED else Verdict.UNCHANGED
            else -> Verdict.WAIT
        }
    }

    companion object {
        const val POLL_MS = 250L
        const val TIMEOUT_MS = 3000L
    }
}
