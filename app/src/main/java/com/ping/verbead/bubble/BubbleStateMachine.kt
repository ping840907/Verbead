package com.ping.verbead.bubble

/**
 * States of the floating input bubble.
 */
enum class BubbleState {
    IDLE,
    LOADING,
    RECORDING,
    TRANSCRIBING,
    PASTED,
    SCANNING
}

/**
 * Pure Kotlin state machine for managing floating bubble lifecycle transitions.
 */
class BubbleStateMachine(initialState: BubbleState = BubbleState.IDLE) {

    var state: BubbleState = initialState
        private set

    val isBusy: Boolean
        get() = state in listOf(
            BubbleState.LOADING,
            BubbleState.RECORDING,
            BubbleState.TRANSCRIBING,
            BubbleState.SCANNING
        )

    val canStartRecording: Boolean
        get() = state == BubbleState.IDLE || state == BubbleState.PASTED

    val canCancelToIdle: Boolean
        get() = state != BubbleState.IDLE

    fun canTransitionTo(target: BubbleState): Boolean {
        if (state == target) return true
        return when (state) {
            BubbleState.IDLE -> true
            BubbleState.LOADING -> target in listOf(BubbleState.RECORDING, BubbleState.IDLE, BubbleState.SCANNING)
            BubbleState.RECORDING -> target in listOf(BubbleState.TRANSCRIBING, BubbleState.IDLE, BubbleState.PASTED)
            BubbleState.TRANSCRIBING -> target in listOf(BubbleState.PASTED, BubbleState.IDLE)
            BubbleState.PASTED -> true
            BubbleState.SCANNING -> target in listOf(BubbleState.IDLE, BubbleState.PASTED, BubbleState.RECORDING)
        }
    }

    fun transitionTo(target: BubbleState): Boolean {
        if (!canTransitionTo(target)) return false
        state = target
        return true
    }

    fun reset() {
        state = BubbleState.IDLE
    }
}
