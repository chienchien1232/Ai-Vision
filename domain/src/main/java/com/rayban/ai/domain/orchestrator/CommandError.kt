package com.rayban.ai.domain.orchestrator

sealed interface CommandError {
    data object EmptyInput : CommandError
    data class Ambiguous(val commands: Set<CommandKind>) : CommandError
    data object UnsupportedAction : CommandError
    data object Unknown : CommandError
}