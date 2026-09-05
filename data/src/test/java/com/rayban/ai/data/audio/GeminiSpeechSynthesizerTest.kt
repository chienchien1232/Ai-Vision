package com.rayban.ai.data.audio

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rayban.ai.data.vision.GeminiHttpClient
import com.rayban.ai.data.vision.HttpResponse
import com.rayban.ai.domain.audio.SpeechError
import com.rayban.ai.domain.audio.TranscriptionException
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class GeminiSpeechSynthesizerTest {

    @Test
    fun `request asks for audio-only verbatim speech in the original language`() = runTest {
        val text = "Đã chụp và lưu ảnh."
        val pcm = ByteArray(4800) { it.toByte() }
        val synth = GeminiSpeechSynthesizer(
            apiKey = "test-key",
            model = "test-tts-model",
            voice = "TestVoice",
            client = GeminiHttpClient { model, _, body ->
                assertEquals("test-tts-model", model)
                val root = JsonParser.parseString(body).asJsonObject
                val part = root.getAsJsonArray("contents")[0].asJsonObject
                    .getAsJsonArray("parts")[0].asJsonObject
                val prompt = part.get("text").asString
                assert(prompt.contains(text))
                assert(prompt.contains("Vietnamese"))
                assert(prompt.contains("English"))
                val config = root.getAsJsonObject("generationConfig")
                assertEquals(
                    "AUDIO",
                    config.getAsJsonArray("responseModalities")[0].asString,
                )
                assertEquals(
                    "TestVoice",
                    config.getAsJsonObject("speechConfig")
                        .getAsJsonObject("voiceConfig")
                        .getAsJsonObject("prebuiltVoiceConfig")
                        .get("voiceName").asString,
                )
                audioResponse(pcm)
            },
        )

        val audio = synth.synthesize(text)

        assertEquals(24000, audio.sampleRate)
        assertEquals(1, audio.channels)
        assertArrayEquals(pcm, audio.pcm)
    }

    @Test
    fun `http and malformed responses never return audio`() = runTest {
        val cases = listOf(
            Triple(400, "", SpeechError.InvalidRequest),
            Triple(401, "", SpeechError.AccessDenied),
            Triple(403, "", SpeechError.AccessDenied),
            Triple(404, "", SpeechError.ModelUnavailable),
            Triple(429, "", SpeechError.RateLimited),
            Triple(504, "", SpeechError.Timeout),
            Triple(500, "", SpeechError.Network),
            Triple(200, "{}", SpeechError.Unknown),
            Triple(200, "not json", SpeechError.Unknown),
            Triple(200, noAudioResponse(), SpeechError.Audio),
            Triple(200, wrongFormatResponse(), SpeechError.Audio),
        )
        for ((status, body, error) in cases) {
            val synth = GeminiSpeechSynthesizer(
                apiKey = "test-key",
                client = GeminiHttpClient { _, _, _ -> HttpResponse(status, body) },
            )
            try {
                synth.synthesize("Xin chào")
                fail("Expected $error")
            } catch (e: TranscriptionException) {
                assertEquals(error, e.error)
            }
        }
        val network = GeminiSpeechSynthesizer(
            apiKey = "test-key",
            client = GeminiHttpClient { _, _, _ -> throw IOException() },
        )
        try {
            network.synthesize("Xin chào")
            fail("Expected network error")
        } catch (e: TranscriptionException) {
            assertEquals(SpeechError.Network, e.error)
        }
    }

    @Test
    fun `cancellation is propagated`() = runTest {
        val synth = GeminiSpeechSynthesizer(
            apiKey = "test-key",
            client = GeminiHttpClient { _, _, _ -> throw CancellationException() },
        )
        try {
            synth.synthesize("Xin chào")
            fail("Expected cancellation")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `missing key never sends text`() = runTest {
        val synth = GeminiSpeechSynthesizer(
            apiKey = " ",
            client = GeminiHttpClient { _, _, _ ->
                fail("Missing key must not send a request")
                audioResponse(ByteArray(4800))
            },
        )
        try {
            synth.synthesize("Xin chào")
            fail("Expected missing key")
        } catch (e: TranscriptionException) {
            assertEquals(SpeechError.MissingApiKey, e.error)
        }
    }

    private fun audioResponse(pcm: ByteArray): HttpResponse {
        val inline = JsonObject().apply {
            addProperty("mimeType", "audio/L16;rate=24000")
            addProperty("data", Base64.getEncoder().encodeToString(pcm))
        }
        val part = JsonObject().apply { add("inlineData", inline) }
        return HttpResponse(
            200,
            """{"candidates":[{"finishReason":"STOP","content":{"parts":[$part]}}]}""",
        )
    }

    private fun noAudioResponse(): String =
        """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"hi"}]}}]}"""

    private fun wrongFormatResponse(): String {
        val inline = JsonObject().apply {
            addProperty("mimeType", "audio/L16;rate=24000;channels=2")
            addProperty("data", Base64.getEncoder().encodeToString(ByteArray(4800)))
        }
        val part = JsonObject().apply { add("inlineData", inline) }
        return """{"candidates":[{"finishReason":"STOP","content":{"parts":[$part]}}]}"""
    }
}
