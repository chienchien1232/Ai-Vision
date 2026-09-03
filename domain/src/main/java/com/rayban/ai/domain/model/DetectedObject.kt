package com.rayban.ai.domain.model

data class DetectedObject(
    val label: String,
    val confidence: Float,
)