package com.rayban.ai.domain.model

data class ImageFrame(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val timestampMillis: Long,
    val rotationDegrees: Int,
    val format: ImageFormat,
)

enum class ImageFormat {
    JPEG,
}
