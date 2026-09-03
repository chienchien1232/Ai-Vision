package com.rayban.ai.domain.model

data class ProcessedFrame(
    val image: ImageFrame,
    val processedAtMillis: Long,
)
