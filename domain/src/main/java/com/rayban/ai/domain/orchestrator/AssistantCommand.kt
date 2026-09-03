package com.rayban.ai.domain.orchestrator

sealed interface AssistantCommand {
    data object DescribeScene : AssistantCommand
    data object IdentifyObject : AssistantCommand
    data object ReadText : AssistantCommand
    data object SummarizeScene : AssistantCommand
    data object TakePhoto : AssistantCommand
    data object RecordVideo : AssistantCommand
    data object Stop : AssistantCommand
    data object RepeatLastAnswer : AssistantCommand
    data class OpenQuestion(val question: String) : AssistantCommand
}

enum class CommandKind {
    DescribeScene,
    IdentifyObject,
    ReadText,
    SummarizeScene,
    TakePhoto,
    RecordVideo,
}