package com.rayban.ai.domain.assistant

import com.rayban.ai.domain.model.AssistantRequest
import com.rayban.ai.domain.model.AssistantResponse

interface AssistantEngine {
    suspend fun respond(request: AssistantRequest): AssistantResponse
}