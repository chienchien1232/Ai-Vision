package com.rayban.ai.data.audio

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rayban.ai.data.vision.GeminiHttpClient
import com.rayban.ai.data.vision.HttpResponse
import com.rayban.ai.domain.audio.*
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GeminiAudioTranscriberTest {
    @Test fun `bilingual transcription uses exact WAV payload and no forced locale`() = runTest {
        val wav = ByteArray(48) { it.toByte() }
        for (words in listOf("Chụp ảnh", "Take a photo", "Chụp ảnh please", "")) {
            val adapter = GeminiAudioTranscriber("test", httpClient = GeminiHttpClient { model, _, body ->
                assertEquals("gemini-3.6-flash", model)
                val root = JsonParser.parseString(body).asJsonObject
                val parts = root.getAsJsonArray("contents")[0].asJsonObject.getAsJsonArray("parts")
                val audio = parts[1].asJsonObject.getAsJsonObject("inlineData")
                assertEquals("audio/wav", audio.get("mimeType").asString)
                assertArrayEquals(wav, Base64.getDecoder().decode(audio.get("data").asString))
                response(words)
            })
            assertEquals(words, adapter.transcribe(wav))
        }
    }

    @Test fun `invalid responses and network failures never return commands`() = runTest {
        for ((status, body, error) in listOf(
            Triple(429, "", SpeechError.RateLimited), Triple(403, "", SpeechError.AccessDenied),
            Triple(401, "", SpeechError.AccessDenied), Triple(400, "", SpeechError.InvalidRequest),
            Triple(404, "", SpeechError.ModelUnavailable),
            Triple(504, "", SpeechError.Timeout),
            Triple(200, "{}", SpeechError.Unknown), Triple(200, "not json", SpeechError.Unknown),
        )) {
            val adapter = GeminiAudioTranscriber("test", httpClient = GeminiHttpClient { _, _, _ -> HttpResponse(status, body) })
            try { adapter.transcribe(ByteArray(48)); fail("Expected error") }
            catch (e: TranscriptionException) { assertEquals(error, e.error) }
        }
        val adapter = GeminiAudioTranscriber("test", httpClient = GeminiHttpClient { _, _, _ -> throw IOException() })
        try { adapter.transcribe(ByteArray(48)); fail("Expected network error") }
        catch (e: TranscriptionException) { assertEquals(SpeechError.Network, e.error) }
    }

    @Test fun `cancellation is propagated`() = runTest {
        val adapter = GeminiAudioTranscriber("test", httpClient = GeminiHttpClient { _, _, _ -> throw CancellationException() })
        try { adapter.transcribe(ByteArray(48)); fail("Expected cancellation") }
        catch (_: CancellationException) { }
    }

    @Test fun `missing key never sends audio`() = runTest {
        val adapter = GeminiAudioTranscriber(" ", httpClient = GeminiHttpClient { _, _, _ ->
            fail("Missing key must not send a request")
            response("")
        })
        assertFalse(adapter.isAvailable)
        try { adapter.transcribe(ByteArray(48)); fail("Expected missing key") }
        catch (e: TranscriptionException) { assertEquals(SpeechError.MissingApiKey, e.error) }
    }

    private fun response(words: String): HttpResponse {
        val transcript = JsonObject().apply { addProperty("transcript", words) }.toString()
        val text = JsonObject().apply { addProperty("text", transcript) }
        return HttpResponse(200, """{"candidates":[{"finishReason":"STOP","content":{"parts":[$text]}}]}""")
    }
}
