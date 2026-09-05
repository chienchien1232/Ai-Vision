package com.rayban.ai.domain.wearable

sealed interface WearableCommand {
    val requestId: String

    data class Ping(override val requestId: String) : WearableCommand
    data class GetCapabilities(override val requestId: String) : WearableCommand
    data class CapturePhoto(override val requestId: String) : WearableCommand
    data class StartPreview(override val requestId: String) : WearableCommand
    data class StopPreview(override val requestId: String) : WearableCommand
    data class StartMicrophone(override val requestId: String) : WearableCommand
    data class StopMicrophone(override val requestId: String) : WearableCommand
    data class StartVideoRecording(
        override val requestId: String,
        val withAudio: Boolean = true,
        val maxDurationMillis: Long = 30_000L,
    ) : WearableCommand
    data class StopVideoRecording(override val requestId: String) : WearableCommand
    data class CancelOperation(override val requestId: String) : WearableCommand
}

sealed interface WearableCommandResult {
    data class Ok(val payload: String? = null) : WearableCommandResult
    data class Failed(val error: WearableCommandError) : WearableCommandResult
}

enum class WearableCommandError {
    Timeout,
    ConnectionLost,
    Unsupported,
    DeviceError,
    InvalidResponse,
    Unknown,
}