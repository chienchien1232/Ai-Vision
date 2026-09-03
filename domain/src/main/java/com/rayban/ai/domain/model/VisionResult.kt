package com.rayban.ai.domain.model

data class VisionResult(
    val description: String,
    val detectedText: List<String> = emptyList(),
    val objects: List<DetectedObject> = emptyList(),
    val confidence: Float? = null,
    val processingTimeMillis: Long,
)