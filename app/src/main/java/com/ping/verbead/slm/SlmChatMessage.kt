package com.ping.verbead.slm

import java.util.UUID

/**
 * Data model for messages in the floating SLM chat window.
 */
data class SlmChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    var content: String,
    var thinkingContent: String? = null,
    var isThinkingExpanded: Boolean = false,
    var isStreaming: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val modelTag: String? = null
) {
    enum class Role {
        USER,
        ASSISTANT,
        SYSTEM
    }

    /**
     * Extracts the clean answer content without any <think> tags or reasoning artifacts,
     * suitable for pasting or copying.
     */
    fun getCleanAnswer(): String {
        var clean = content
        if (clean.contains("<think>")) {
            clean = clean.replace(Regex("<think>[\\s\\S]*?(</think>|$)"), "").trim()
        }
        return clean.trim()
    }
}
