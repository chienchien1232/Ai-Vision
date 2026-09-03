package com.rayban.ai.domain.orchestrator

import com.rayban.ai.domain.model.AssistantTask
import com.rayban.ai.domain.model.FramePolicy
import com.rayban.ai.domain.model.MediaAsset
import com.rayban.ai.domain.model.MediaCaptureException
import com.rayban.ai.domain.usecase.AskAssistantUseCase
import com.rayban.ai.domain.usecase.StartVideoRecordingUseCase
import com.rayban.ai.domain.usecase.StopVideoRecordingUseCase
import com.rayban.ai.domain.usecase.TakePhotoUseCase

sealed interface CommandOutcome {
    data class Speak(
        val text: String,
        val turns: List<com.rayban.ai.domain.model.ConversationTurn>,
        val repeated: Boolean = false,
    ) : CommandOutcome

    data class PhotoSaved(val asset: MediaAsset) : CommandOutcome
    data class VideoSaved(val asset: MediaAsset) : CommandOutcome
    data object Stopped : CommandOutcome
    data object NothingRunning : CommandOutcome
    data object NoPreviousAnswer : CommandOutcome
}

class AiOrchestrator(
    private val router: CommandRouter,
    private val askAssistantUseCase: AskAssistantUseCase,
    private val takePhotoUseCase: TakePhotoUseCase,
    private val startVideoRecordingUseCase: StartVideoRecordingUseCase,
    private val stopVideoRecordingUseCase: StopVideoRecordingUseCase,
) {

    fun route(request: CommandRequest): CommandMatch = router.route(request)

    suspend fun execute(
        command: AssistantCommand,
        utterance: String,
        requestedAtMillis: Long = System.currentTimeMillis(),
    ): CommandOutcome = when (command) {
        AssistantCommand.DescribeScene ->
            askVisual(utterance, AssistantTask.DescribeScene, FramePolicy.Fresh)

        AssistantCommand.IdentifyObject ->
            askVisual(utterance, AssistantTask.IdentifyObject, FramePolicy.Fresh)

        AssistantCommand.ReadText ->
            askVisual(utterance, AssistantTask.ReadText, FramePolicy.Fresh)

        AssistantCommand.SummarizeScene ->
            askVisual(utterance, AssistantTask.SummarizeScene, FramePolicy.Fresh)

        is AssistantCommand.OpenQuestion ->
            askVisual(command.question, AssistantTask.OpenQuestion, FramePolicy.RecentOrFresh)

        AssistantCommand.TakePhoto -> takePhotoUseCase()
            .fold(
                onSuccess = { CommandOutcome.PhotoSaved(it) },
                onFailure = { throw it },
            )

        AssistantCommand.RecordVideo -> startVideoRecordingUseCase()
            .fold(
                onSuccess = { CommandOutcome.VideoSaved(it) },
                onFailure = { throw it },
            )

        AssistantCommand.Stop, AssistantCommand.RepeatLastAnswer ->
            throw IllegalStateException(
                "Command ${command::class.simpleName} is handled by the presentation layer",
            )
    }

    suspend fun lastAnswer(): String? =
        askAssistantUseCase.recentTurns().lastOrNull()?.answer

    suspend fun newConversation() = askAssistantUseCase.newConversation()

    suspend fun changeScene() = askAssistantUseCase.changeScene()

    fun stopVideoEarly() = stopVideoRecordingUseCase()

    private suspend fun askVisual(
        question: String,
        task: AssistantTask,
        framePolicy: FramePolicy,
    ): CommandOutcome {
        val result = askAssistantUseCase(question, task, framePolicy)
        return result.fold(
            onSuccess = { CommandOutcome.Speak(it.answer, it.turns) },
            onFailure = { throw it },
        )
    }
}