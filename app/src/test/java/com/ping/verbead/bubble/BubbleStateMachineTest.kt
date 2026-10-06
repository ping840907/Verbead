package com.ping.verbead.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleStateMachineTest {

    @Test
    fun testInitialStateIsIdle() {
        val sm = BubbleStateMachine()
        assertEquals(BubbleState.IDLE, sm.state)
        assertFalse(sm.isBusy)
        assertTrue(sm.canStartRecording)
    }

    @Test
    fun testValidTransitionsFromIdle() {
        val sm = BubbleStateMachine()
        assertTrue(sm.canTransitionTo(BubbleState.LOADING))
        assertTrue(sm.canTransitionTo(BubbleState.RECORDING))
        assertTrue(sm.canTransitionTo(BubbleState.SCANNING))

        assertTrue(sm.transitionTo(BubbleState.RECORDING))
        assertEquals(BubbleState.RECORDING, sm.state)
        assertTrue(sm.isBusy)
        assertFalse(sm.canStartRecording)
    }

    @Test
    fun testRecordingLifecycleTransitions() {
        val sm = BubbleStateMachine()
        assertTrue(sm.transitionTo(BubbleState.RECORDING))

        // Recording to Transcribing
        assertTrue(sm.canTransitionTo(BubbleState.TRANSCRIBING))
        assertTrue(sm.transitionTo(BubbleState.TRANSCRIBING))
        assertEquals(BubbleState.TRANSCRIBING, sm.state)

        // Transcribing to Pasted
        assertTrue(sm.canTransitionTo(BubbleState.PASTED))
        assertTrue(sm.transitionTo(BubbleState.PASTED))
        assertEquals(BubbleState.PASTED, sm.state)
        assertFalse(sm.isBusy)
        assertTrue(sm.canStartRecording)

        // Pasted back to Idle
        assertTrue(sm.transitionTo(BubbleState.IDLE))
        assertEquals(BubbleState.IDLE, sm.state)
    }

    @Test
    fun testReset() {
        val sm = BubbleStateMachine()
        sm.transitionTo(BubbleState.RECORDING)
        sm.reset()
        assertEquals(BubbleState.IDLE, sm.state)
    }

    @Test
    fun testScanningTransitions() {
        val sm = BubbleStateMachine()
        assertTrue(sm.transitionTo(BubbleState.SCANNING))
        assertTrue(sm.isBusy)

        assertTrue(sm.transitionTo(BubbleState.PASTED))
        assertEquals(BubbleState.PASTED, sm.state)
    }
}
