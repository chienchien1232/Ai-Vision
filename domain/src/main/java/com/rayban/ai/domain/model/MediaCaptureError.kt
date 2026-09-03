package com.rayban.ai.domain.model

enum class MediaCaptureError {
    PermissionDenied,
    CameraUnavailable,
    AlreadyRecording,
    NotRecording,
    StorageUnavailable,
    CaptureFailed,
    FinalizeFailed,
    AudioUnavailable,
    Unknown,
}

class MediaCaptureException(
    val error: MediaCaptureError,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)