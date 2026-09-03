package com.rayban.ai.domain.model

enum class FrameCaptureError {
    CameraUnavailable,
    PermissionDenied,
    CaptureFailed,
    InvalidFrame,
    ProcessingFailed,
    SourceDisconnected,
    Unknown,
}
