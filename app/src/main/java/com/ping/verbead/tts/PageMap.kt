package com.ping.verbead.tts

/**
 * Keeps track of which page a sentence of the session belongs to when several pages are joined into one reading.
 *
 * The session counts sentences from the start of the reading; each page has its own plan with its own numbering.
 * A page that is queued behind the one being read does not take over until the voice reaches its first sentence.
 */
class PageMap {

    /** The session index that is sentence 0 of the page being shown: a sentence's place in its page is the index minus this. */
    var base: Int = 0
        private set

    /** The first session index of the page being shown; the reading does not step back before it. */
    var firstIndex: Int = 0
        private set

    private var pendingAt = NONE
    private var pendingBase = 0

    val hasPending: Boolean get() = pendingAt != NONE

    /** A new reading: the page's sentences are the session's, from the start. */
    fun reset() {
        base = 0
        firstIndex = 0
        pendingAt = NONE
    }

    /** The place in the page being shown of the session sentence [index]. */
    fun local(index: Int): Int = index - base

    /**
     * Another page is queued: its sentences from [from] on were appended to the session when it already held
     * [sessionSize] sentences, so its first one is the session sentence [sessionSize].
     */
    fun queueNext(sessionSize: Int, from: Int) {
        pendingAt = sessionSize
        pendingBase = sessionSize - from
    }

    /** Forgets a queued page that will not be used. */
    fun clearPending() {
        pendingAt = NONE
    }

    /**
     * Call when the voice reaches the session sentence [index]. Returns true when that is the first sentence of
     * the queued page, which then becomes the page being shown.
     */
    fun reached(index: Int): Boolean {
        if (pendingAt == NONE || index < pendingAt) return false
        base = pendingBase
        firstIndex = pendingAt
        pendingAt = NONE
        return true
    }

    private companion object { const val NONE = -1 }
}
