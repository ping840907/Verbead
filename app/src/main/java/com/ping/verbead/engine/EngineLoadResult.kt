package com.ping.verbead.engine

/**
 * Common result structure for loading offline AI engines (ASR, OCR, etc.).
 */
data class EngineLoadResult(
    val success: Boolean,
    val error: String? = null,
    val provider: String = "unknown"
)
