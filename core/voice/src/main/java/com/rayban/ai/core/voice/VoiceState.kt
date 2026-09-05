package com.rayban.ai.core.voice

import com.rayban.ai.domain.model.ConversationTurn
import com.rayban.ai.domain.model.MediaType

enum class AssistantPhase {
    Idle,
    Listening,
    Transcribing,
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
    val isSynthesizing: Boolean = false,
    val turns: List<ConversationTurn> = emptyList(),
    val error: VoiceError? = null,
    val errorMessage: String? = null,
    val mediaMessage: MediaMessage? = null,
    val recordingStartedAtMillis: Long? = null,
)

enum class VoiceError {
    MicPermissionDenied,
    VoiceLanguageUnavailable,
    VoiceSynthesisFailed,
    RecognizerUnavailable,
    SpeechMissingApiKey,
    SpeechAccessDenied,
    SpeechModelUnavailable,
    SpeechInvalidRequest,
    NoMatch,
    SpeechNetwork,
    SpeechRateLimited,
    SpeechTimeout,
    SpeechAudio,
    Network,
    Timeout,
    InvalidImage,
    SourceDisconnected,
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
