package com.rayban.ai.domain.model

data class AssistantContextSnapshot(
    val sceneId: Long,
    val latestFrame: ImageFrame?,
    val latestOcrText: String?,
    val recentTurns: List<ConversationTurn>,
)