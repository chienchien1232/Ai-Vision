package com.rayban.ai.domain.context

import com.rayban.ai.domain.model.AssistantContextSnapshot
import com.rayban.ai.domain.model.ImageFrame

interface ConversationContext {
    suspend fun snapshot(): AssistantContextSnapshot
    suspend fun cacheFrame(frame: ImageFrame)
    suspend fun cacheOcrText(text: String?)
    suspend fun recordTurn(question: String, answer: String, timestampMillis: Long)
    suspend fun changeScene()
    suspend fun clear()
}