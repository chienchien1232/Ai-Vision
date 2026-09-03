package com.rayban.ai.domain.model

data class ConversationTurn(
    val question: String,
    val answer: String,
    val sceneId: Long,
    val timestampMillis: Long,
)