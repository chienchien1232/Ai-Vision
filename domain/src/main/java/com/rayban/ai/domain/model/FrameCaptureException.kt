package com.rayban.ai.domain.model

class FrameCaptureException(
    val error: FrameCaptureError,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
