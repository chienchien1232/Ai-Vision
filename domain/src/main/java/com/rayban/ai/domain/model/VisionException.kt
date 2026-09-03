package com.rayban.ai.domain.model

class VisionException(
    val error: VisionError,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)