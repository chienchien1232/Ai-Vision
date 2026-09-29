package com.example.ai_vision

import com.example.ai_vision.core.*
import org.junit.Assert.*
import org.junit.Test

class ReservedCommandTest {
    @Test fun futureChipCommandsNeverConsumeGeminiCalls() {
        listOf("stop", "repeat", "volume up", "volume down", "battery status", "tăng âm lượng", "pin còn bao nhiêu").forEach {
            assertNull(it, CloudPolicy.reason(IntentRouter.route(it)))
        }
        assertEquals(AppIntent.StopSpeaking, IntentRouter.route("stop"))
        assertEquals(AppIntent.Replay, IntentRouter.route("repeat"))
        assertTrue(IntentRouter.route("battery status") is AppIntent.UnsupportedLocal)
    }
}
