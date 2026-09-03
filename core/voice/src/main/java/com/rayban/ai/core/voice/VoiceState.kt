package com.rayban.ai.core.voice

import com.rayban.ai.domain.model.ConversationTurn
import com.rayban.ai.domain.model.MediaType

enum class AssistantPhase {
    Idle,
    Listening,
    Processing,
    Speaking,
    Recording,
}

data class MediaMessage(
    val type: MediaType,
    val uri: String,
)

data class VoiceUiState(
    val phase: AssistantPhase = AssistantPhase.Idle,
    val turns: List<ConversationTurn> = emptyList(),
    val error: VoiceError? = null,
    val errorMessage: String? = null,
    val mediaMessage: MediaMessage? = null,
    val recordingStartedAtMillis: Long? = null,
)

enum class VoiceError {
    MicPermissionDenied,
    RecognizerUnavailable,
    NoMatch,
    SpeechNetwork,
    SpeechAudio,
    Network,
    Timeout,
    InvalidImage,
    ProviderUnavailable,
    RateLimited,
    Cancelled,
    MediaFailed,
    NoPreviousAnswer,
    AmbiguousCommand,
    UnsupportedAction,
    Busy,
    Unknown,
}