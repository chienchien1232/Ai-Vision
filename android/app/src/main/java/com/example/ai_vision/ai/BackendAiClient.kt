package com.example.ai_vision.ai

import android.util.Base64
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.example.ai_vision.BuildConfig

data class AiBackendConfig(val baseUrl: String, val token: String)
data class AiQuestion(val sessionId: String, val text: String, val jpeg: ByteArray?)

/** Only debug APKs can reach the PC backend through an authenticated ADB tunnel. */
internal fun validateBackendUrl(base: String, debug: Boolean): URL {
    val url = try { URL(base) } catch (_: Exception) { throw IllegalArgumentException("Invalid backend URL") }
    val localDebug = debug && base == "http://127.0.0.1:8080"
    require((url.protocol == "https" || localDebug) && url.userInfo == null &&
        url.host.isNotBlank() && url.query == null && url.ref == null) { "Invalid backend URL" }
    return url
}

class AiBackendHttpException(val statusCode: Int, val errorCode: String?) : IOException(
    "AI backend returned HTTP $statusCode" + (errorCode?.let { " ($it)" } ?: "")
)

/** Glasses speaker format shared by cloud TTS and on-device rendering. Pure for unit tests. */
internal fun requireGlassesPcm(pcm: ByteArray): ByteArray {
    require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= 16000 * 2 * 30) {
        "Invalid answer audio"
    }
    return pcm
}

interface AiClient {
    suspend fun answer(config: AiBackendConfig, question: AiQuestion): String
    suspend fun speak(config: AiBackendConfig, sessionId: String, text: String, languageTag: String): ByteArray
}

/** Calls our backend; the Gemini API key remains on the server. */
class BackendAiClient : AiClient {
    override suspend fun answer(config: AiBackendConfig, question: AiQuestion): String = withContext(Dispatchers.IO) {
        require(question.text.isNotBlank()) { "Question is empty" }
        val body = JSONObject().apply {
            put("sessionId", question.sessionId)
            put("question", question.text)
            question.jpeg?.let { put("imageBase64", Base64.encodeToString(it, Base64.NO_WRAP)) }
        }
        val json = post(config, "answer", body)
        requireSession(json, question.sessionId)
        json.optString("answer").takeIf { it.isNotBlank() }
            ?: throw IOException("AI returned an empty answer")
    }

    override suspend fun speak(config: AiBackendConfig, sessionId: String, text: String, languageTag: String): ByteArray = withContext(Dispatchers.IO) {
        require(text.isNotBlank()) { "Answer text is empty" }
        require(languageTag == "vi-VN" || languageTag == "en-US") { "Unsupported speech language" }
        val body = JSONObject().apply {
            put("sessionId", sessionId)
            put("text", text)
            put("languageTag", languageTag)
        }
        val json = post(config, "speak", body)
        requireSession(json, sessionId)
        if (json.optInt("sampleRate", -1) != 16000) throw IOException("Unexpected answer audio format")
        requireGlassesPcm(Base64.decode(json.optString("audioBase64"), Base64.DEFAULT))
    }

    suspend fun transcribe(config: AiBackendConfig, sessionId: String, pcm: ByteArray, languageTag: String): String = withContext(Dispatchers.IO) {
        require(languageTag == "vi-VN" || languageTag == "en-US") { "Unsupported speech language" }
        val wav = pcmToWav(pcm)
        val body = JSONObject().apply {
            put("sessionId", sessionId)
            put("languageTag", languageTag)
            put("audioBase64", Base64.encodeToString(wav, Base64.NO_WRAP))
        }
        val json = post(config, "transcribe", body)
        requireSession(json, sessionId)
        json.optString("transcript").trim().takeIf { it.isNotBlank() }
            ?: throw IOException("No speech recognized from glasses")
    }

    private suspend fun post(config: AiBackendConfig, path: String, body: JSONObject): JSONObject {
        val base = config.baseUrl.trim().trimEnd('/')
        require(config.token.isNotBlank()) {
            "Enter an HTTPS backend URL and app token"
        }
        validateBackendUrl(base, BuildConfig.DEBUG)
        val url = URL("$base/v1/$path")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 40_000
            doOutput = true
            instanceFollowRedirects = false // Never forward the app token to a redirect target.
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer ${config.token}")
        }
        return cancelableHttp(connection) {
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            if (status !in 200..299) {
                // Our backend returns a short JSON error; proxies may return
                // HTML instead. Never show raw upstream bodies or credentials.
                val code = runCatching {
                    val body = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                        val chars = CharArray(1024)
                        val count = reader.read(chars)
                        if (count > 0) String(chars, 0, count) else ""
                    } ?: ""
                    JSONObject(body).optString("error").takeIf { it.isNotBlank() }
                }.getOrNull()
                throw AiBackendHttpException(status, code)
            }
            val limit = if (path == "speak") 2 * 1024 * 1024 else 65536
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val output = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (output.length + count > limit) throw IOException("AI response is too large")
                    output.append(buffer, 0, count)
                }
                output.toString()
            }
            JSONObject(response)
        }
    }

    private fun requireSession(json: JSONObject, sessionId: String) {
        if (json.optString("sessionId") != sessionId) throw IOException("AI response belongs to another session")
    }

    private fun pcmToWav(pcm: ByteArray): ByteArray {
        require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= 16000 * 2 * 30) {
            "Glasses audio must be PCM16 mono 16 kHz, at most 30 seconds"
        }
        return ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcm.size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(16000)
            putInt(32000)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcm.size)
            put(pcm)
        }.array()
    }
}
