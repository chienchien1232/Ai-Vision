package com.example.ai_vision

import com.example.ai_vision.device.VoicePhase
import com.example.ai_vision.device.VoiceSessionState
import org.junit.Assert.*
import org.junit.Test

class VoiceSessionStateTest {
    @Test fun onlyOneManualWakeCanOwnTheMicrophone() {
        val state = VoiceSessionState()
        assertFalse(state.accepts("unsolicited"))
        assertTrue(state.start())
        assertFalse(state.start())
        assertFalse(state.accepts("early"))
        state.bind("current")
        assertTrue(state.accepts("current"))
        assertFalse(state.accepts("old"))
        state.move(VoicePhase.WAITING_AI)
        assertFalse(state.start())
        assertFalse(state.accepts("current"))
        state.clear()
        assertFalse(state.accepts("current"))
        assertTrue(state.start())
    }
    @Test fun cancellationCannotAdoptAResponseFromThePreviousSession() {
        val state = VoiceSessionState()
        state.start(); state.bind("previous"); state.move(VoicePhase.CANCELLING)
        assertFalse(state.accepts("previous"))
        state.clear(); state.start(); state.bind("next")
        assertFalse(state.accepts("previous"))
        assertTrue(state.accepts("next"))
    }
}
