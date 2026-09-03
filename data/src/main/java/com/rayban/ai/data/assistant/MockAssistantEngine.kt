package com.rayban.ai.data.assistant

import com.rayban.ai.domain.assistant.AssistantEngine
import com.rayban.ai.domain.model.AssistantRequest
import com.rayban.ai.domain.model.AssistantResponse
import com.rayban.ai.domain.model.VisionError
import com.rayban.ai.domain.model.VisionException
import kotlinx.coroutines.delay

class MockAssistantEngine : AssistantEngine {
    override suspend fun respond(request: AssistantRequest): AssistantResponse {
        if (request.frame.data.isEmpty() || request.frame.width <= 0 || request.frame.height <= 0) {
            throw VisionException(
                error = VisionError.InvalidImage,
                message = "Frame failed assistant validation",
            )
        }
        delay(SIMULATED_LATENCY_MILLIS)
        val contextHint = if (request.recentTurns.isNotEmpty()) {
            " (following up on ${request.recentTurns.size} earlier question)"
        } else {
            ""
        }
        return AssistantResponse(
            answer = "Mock answer for \"${request.question}\"$contextHint",
            processingTimeMillis = SIMULATED_LATENCY_MILLIS,
        )
    }

    private companion object {
        const val SIMULATED_LATENCY_MILLIS = 400L
    }
}