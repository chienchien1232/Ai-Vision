package com.rayban.ai.domain.audio

enum class SpeechError {
    PermissionDenied,
    NotAvailable,
    MissingApiKey,
    AccessDenied,
    ModelUnavailable,
    InvalidRequest,
    NoMatch,
    Network,
    RateLimited,
    Timeout,
    Audio,
    Busy,
    Unknown,
}
