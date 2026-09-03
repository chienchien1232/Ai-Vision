package com.rayban.ai.domain.orchestrator

interface CommandRouter {
    fun route(request: CommandRequest): CommandMatch
}

sealed interface CommandMatch {
    data class Matched(val command: AssistantCommand) : CommandMatch
    data class Rejected(val error: CommandError) : CommandMatch
}