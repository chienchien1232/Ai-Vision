package com.example.ai_vision

import com.example.ai_vision.ai.requireGlassesPcm
import com.example.ai_vision.ai.AiBackendHttpException
import com.example.ai_vision.ai.validateBackendUrl
import com.example.ai_vision.ai.requireBackendSession
import com.example.ai_vision.ai.backendText
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackendAiClientTest {
    @Test fun localBackendIsRestrictedToDebugAdbLoopback() {
        assertEquals("127.0.0.1", validateBackendUrl("http://127.0.0.1:8080", true).host)
        assertEquals("https", validateBackendUrl("https://example.invalid", false).protocol)
        for (url in listOf("http://127.0.0.1:8080", "http://192.168.1.2:8080", "http://localhost:8080",
            "http://127.0.0.1:8080.evil.invalid", "https://token@example.invalid", "https://example.invalid?q=token")) {
            assertThrows(IllegalArgumentException::class.java) { validateBackendUrl(url, false) }
        }
        for (url in listOf("http://192.168.1.2:8080", "http://localhost:8080", "http://127.0.0.1:8080/other")) {
            assertThrows(IllegalArgumentException::class.java) { validateBackendUrl(url, true) }
        }
    }
    @Test
    fun backendTimeoutErrorDoesNotExposeResponseBody() {
        val error = AiBackendHttpException(504, "AI_TIMEOUT")
        assertEquals("AI backend returned HTTP 504 (AI_TIMEOUT)", error.message)
    }

    @Test
    fun acceptsEvenLengthPcmWithinLimit() {
        val pcm = ByteArray(32000) { it.toByte() }
        assertArrayEquals(pcm, requireGlassesPcm(pcm))
    }

    @Test
    fun rejectsEmptyOddOrOversizePcm() {
        assertThrows(IllegalArgumentException::class.java) { requireGlassesPcm(ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { requireGlassesPcm(ByteArray(3)) }
        assertThrows(IllegalArgumentException::class.java) { requireGlassesPcm(ByteArray(16000 * 2 * 30 + 2)) }
    }

    @Test fun responseRequiresExactSessionAndActualText() {
        val valid = JSONObject().put("sessionId", "session-1").put("answer", "  Xin chào  ")
        requireBackendSession(valid, "session-1")
        assertEquals("Xin chào", backendText(valid, "answer"))
        assertThrows(IOException::class.java) { requireBackendSession(valid, "session-2") }
        assertThrows(IOException::class.java) { requireBackendSession(JSONObject().put("sessionId", 1), "1") }
        for (value in listOf<Any>(JSONObject.NULL, 5, JSONObject(), "   ")) {
            assertThrows(IOException::class.java) { backendText(JSONObject().put("answer", value), "answer") }
        }
        assertThrows(IOException::class.java) { backendText(JSONObject(), "transcript") }
    }
}
