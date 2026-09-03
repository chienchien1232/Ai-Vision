package com.rayban.ai.data.vision

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.rayban.ai.domain.model.ImageFrame
import com.rayban.ai.domain.model.VisionError
import com.rayban.ai.domain.model.VisionException
import com.rayban.ai.domain.model.VisionResult
import com.rayban.ai.domain.vision.VisionEngine
import java.util.Base64

class GeminiVisionEngine(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val httpClient: GeminiHttpClient = OkHttpGeminiHttpClient(),
) : VisionEngine {

    override suspend fun analyze(
        frame: ImageFrame,
        question: String,
    ): VisionResult {
        if (apiKey.isBlank()) {
            throw VisionException(
                VisionError.ProviderUnavailable,
                "Gemini API key is not configured",
            )
        }
        if (frame.data.isEmpty() || frame.width <= 0 || frame.height <= 0) {
            throw VisionException(
                VisionError.InvalidImage,
                "Frame failed vision validation",
            )
        }

        val start = System.currentTimeMillis()
        val response = httpClient.generateContentOrThrow(
            model = model,
            apiKey = apiKey,
            requestJson = buildRequest(frame, question),
        )
        val description = GeminiResponseParser.answerText(response.statusCode, response.body)

        return VisionResult(
            description = description,
            processingTimeMillis = System.currentTimeMillis() - start,
        )
    }

    private fun buildRequest(frame: ImageFrame, question: String): String {
        val inlineData = JsonObject().apply {
            addProperty("mime_type", "image/jpeg")
            addProperty("data", Base64.getEncoder().encodeToString(frame.data))
        }
        val parts = JsonArray().apply {
            add(JsonObject().apply { addProperty("text", buildPrompt(question)) })
            add(JsonObject().apply { add("inline_data", inlineData) })
        }
        val contents = JsonArray().apply {
            add(JsonObject().apply { add("parts", parts) })
        }
        val generationConfig = JsonObject().apply {
            addProperty("maxOutputTokens", 1024)
            addProperty("temperature", 0.4f)
        }
        return JsonObject().apply {
            add("contents", contents)
            add("generationConfig", generationConfig)
        }.toString()
    }

    private fun buildPrompt(question: String): String =
        "You are the vision assistant of a pair of smart glasses. " +
            "Look at the image and answer the user's question concisely. " +
            "Question: $question"

    private companion object {
        const val DEFAULT_MODEL = "gemini-3.6-flash"
    }
}