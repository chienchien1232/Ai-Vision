package com.rayban.ai.domain.usecase

import com.rayban.ai.domain.model.ConversationTurn
import com.rayban.ai.domain.model.ImageFrame

data class AssistantResult(
    val answer: String,
    val frame: ImageFrame,
    val processingTimeMillis: Long,
    val turns: List<ConversationTurn>,
)