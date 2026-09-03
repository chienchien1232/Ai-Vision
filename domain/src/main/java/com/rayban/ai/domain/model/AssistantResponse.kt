package com.rayban.ai.domain.model

data class AssistantResponse(
    val answer: String,
    val processingTimeMillis: Long,
)