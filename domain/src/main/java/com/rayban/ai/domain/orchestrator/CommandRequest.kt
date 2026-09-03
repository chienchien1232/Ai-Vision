package com.rayban.ai.domain.orchestrator

data class CommandRequest(
    val interactionId: Long,
    val utterance: String,
    val source: CommandSource,
    val requestedAtMillis: Long,
)

enum class CommandSource {
    Voice,
    Typed,
    QuickAction,
    Hardware,
}