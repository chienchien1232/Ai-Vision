package com.rayban.ai.domain.model

enum class VisionError {
    InvalidImage,
    ProviderUnavailable,
    NetworkError,
    Timeout,
    RateLimited,
    Cancelled,
    Unknown,
}