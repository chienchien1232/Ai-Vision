package com.rayban.ai.data.vision

import com.google.gson.JsonParser
import com.rayban.ai.domain.model.VisionError
import com.rayban.ai.domain.model.VisionException
import java.io.IOException
import java.net.SocketTimeoutException

internal suspend fun GeminiHttpClient.generateContentOrThrow(
    model: String,
    apiKey: String,
    requestJson: String,
): HttpResponse = try {
    generateContent(model, apiKey, requestJson)
} catch (e: SocketTimeoutException) {
    throw VisionException(VisionError.Timeout, "Gemini request timed out", e)
} catch (e: IOException) {
    throw VisionException(VisionError.NetworkError, "Network failure: ${e.message}", e)
}

internal object GeminiResponseParser {

    fun answerText(statusCode: Int, responseBody: String): String {
        when (statusCode) {
            in 200..299 -> return parseText(responseBody)
            400 -> throw VisionException(VisionError.InvalidImage, "Gemini rejected the image")
            401, 403 -> throw VisionException(
                VisionError.ProviderUnavailable,
                "Invalid Gemini API key",
            )
            429 -> throw VisionException(VisionError.RateLimited, "Gemini rate limit exceeded")
            in 500..599 -> throw VisionException(
                VisionError.NetworkError,
                "Gemini server error $statusCode",
            )
            else -> throw VisionException(
                VisionError.Unknown,
                "Unexpected Gemini status $statusCode",
            )
        }
    }

    private fun parseText(responseBody: String): String {
        if (responseBody.isBlank()) {
            throw VisionException(VisionError.Unknown, "Gemini returned an empty response")
        }
        val root = JsonParser.parseString(responseBody).asJsonObject
        val candidates = root.getAsJsonArray("candidates")
            ?: throw VisionException(VisionError.Unknown, "Gemini response missing candidates")
        if (candidates.isEmpty) {
            throw VisionException(VisionError.Unknown, "Gemini returned no candidates")
        }
        val parts = candidates[0].asJsonObject
            .getAsJsonObject("content")
            .getAsJsonArray("parts")
        val texts = parts.mapNotNull { part ->
            part.asJsonObject.get("text")?.takeUnless { it.isJsonNull }?.asString
        }
        if (texts.isEmpty()) {
            throw VisionException(VisionError.Unknown, "Gemini returned no text")
        }
        return texts.joinToString("\n").trim()
    }
}