package com.rayban.ai.domain.model

data class AssistantRequest(
    val question: String,
    val frame: ImageFrame,
    val latestOcrText: String?,
    val recentTurns: List<ConversationTurn>,
    val task: AssistantTask = AssistantTask.OpenQuestion,
)