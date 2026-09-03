package com.rayban.ai.data.vision

import com.rayban.ai.domain.model.ImageFormat
import com.rayban.ai.domain.model.ImageFrame
import com.rayban.ai.domain.model.VisionError
import com.rayban.ai.domain.model.VisionException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiVisionEngineTest {

    @Test
    fun `successful analysis returns parsed description`() = runTest {
        val client = FakeClient(statusCode = 200, body = RESPONSE_JSON)
        val engine = GeminiVisionEngine(apiKey = "test-key", httpClient = client)

        val result = engine.analyze(sampleFrame(), "What am I looking at?")

        assertEquals("This is a photo of a desk.", result.description)
        assertTrue(result.processingTimeMillis >= 0)
    }

    @Test
    fun `request contains base64 image and the user question`() = runTest {
        val client = FakeClient(statusCode = 200, body = RESPONSE_JSON)
        val engine = GeminiVisionEngine(apiKey = "test-key", httpClient = client)

        engine.analyze(sampleFrame(), "Read the text in front of me")

        assertTrue(client.requestJson!!.contains("Read the text in front of me"))
        assertTrue(client.requestJson!!.contains("image/jpeg"))
        assertTrue(client.requestJson!!.contains("inline_data"))
        assertEquals("gemini-3.6-flash", client.model)
        assertEquals("test-key", client.apiKey)
    }

    @Test
    fun `blank api key throws ProviderUnavailable`() = runTest {
        val engine = GeminiVisionEngine(apiKey = "  ", httpClient = FakeClient(200, RESPONSE_JSON))

        val e = assertVisionFails { engine.analyze(sampleFrame(), "What is this?") }

        assertEquals(VisionError.ProviderUnavailable, e.error)
    }

    @Test
    fun `empty frame throws InvalidImage`() = runTest {
        val engine = GeminiVisionEngine(apiKey = "test-key", httpClient = FakeClient(200, RESPONSE_JSON))
        val frame = ImageFrame(
            data = ByteArray(0),
            width = 0,
            height = 0,
            timestampMillis = 1L,
            rotationDegrees = 0,
            format = ImageFormat.JPEG,
        )

        val e = assertVisionFails { engine.analyze(frame, "What is this?") }

        assertEquals(VisionError.InvalidImage, e.error)
    }

    @Test
    fun `http 429 maps to RateLimited`() = runTest {
        val engine = GeminiVisionEngine(apiKey = "test-key", httpClient = FakeClient(429, ""))

        val e = assertVisionFails { engine.analyze(sampleFrame(), "What is this?") }

        assertEquals(VisionError.RateLimited, e.error)
    }

    @Test
    fun `http 401 maps to ProviderUnavailable`() = runTest {
        val engine = GeminiVisionEngine(apiKey = "bad-key", httpClient = FakeClient(401, ""))

        val e = assertVisionFails { engine.analyze(sampleFrame(), "What is this?") }

        assertEquals(VisionError.ProviderUnavailable, e.error)
    }

    @Test
    fun `http 400 maps to InvalidImage`() = runTest {
        val engine = GeminiVisionEngine(apiKey = "test-key", httpClient = FakeClient(400, ""))

        val e = assertVisionFails { engine.analyze(sampleFrame(), "What is this?") }

        assertEquals(VisionError.InvalidImage, e.error)
    }

    @Test
    fun `http 500 maps to NetworkError`() = runTest {
        val engine = GeminiVisionEngine(apiKey = "test-key", httpClient = FakeClient(500, ""))

        val e = assertVisionFails { engine.analyze(sampleFrame(), "What is this?") }

        assertEquals(VisionError.NetworkError, e.error)
    }

    @Test
    fun `socket timeout maps to Timeout`() = runTest {
        val engine = GeminiVisionEngine(
            apiKey = "test-key",
            httpClient = FakeClient(200, "", throwable = SocketTimeoutException("timeout")),
        )

        val e = assertVisionFails { engine.analyze(sampleFrame(), "What is this?") }

        assertEquals(VisionError.Timeout, e.error)
    }

    @Test
    fun `io exception maps to NetworkError`() = runTest {
        val engine = GeminiVisionEngine(
            apiKey = "test-key",
            httpClient = FakeClient(200, "", throwable = IOException("boom")),
        )

        val e = assertVisionFails { engine.analyze(sampleFrame(), "What is this?") }

        assertEquals(VisionError.NetworkError, e.error)
    }

    @Test
    fun `success response without candidates maps to Unknown`() = runTest {
        val engine = GeminiVisionEngine(
            apiKey = "test-key",
            httpClient = FakeClient(200, """{"candidates":[]}"""),
        )

        val e = assertVisionFails { engine.analyze(sampleFrame(), "What is this?") }

        assertEquals(VisionError.Unknown, e.error)
    }

    private fun sampleFrame() = ImageFrame(
        data = ByteArray(16) { it.toByte() },
        width = 640,
        height = 480,
        timestampMillis = 1000L,
        rotationDegrees = 0,
        format = ImageFormat.JPEG,
    )

    private suspend fun assertVisionFails(block: suspend () -> Unit): VisionException {
        try {
            block()
        } catch (e: Throwable) {
            if (e is VisionException) return e
            throw e
        }
        throw AssertionError("Expected VisionException")
    }

    private class FakeClient(
        private val statusCode: Int,
        private val body: String,
        private val throwable: Throwable? = null,
    ) : GeminiHttpClient {
        var model: String? = null
        var apiKey: String? = null
        var requestJson: String? = null

        override suspend fun generateContent(
            model: String,
            apiKey: String,
            requestJson: String,
        ): HttpResponse {
            this.model = model
            this.apiKey = apiKey
            this.requestJson = requestJson
            throwable?.let { throw it }
            return HttpResponse(statusCode, body)
        }
    }

    private companion object {
        val RESPONSE_JSON = """
            {"candidates":[{"content":{"parts":[{"text":"This is a photo of a desk."}]}}]}
        """.trimIndent()
    }
}
