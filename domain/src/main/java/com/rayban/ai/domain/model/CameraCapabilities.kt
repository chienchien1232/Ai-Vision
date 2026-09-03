package com.rayban.ai.domain.model

data class CameraCapabilities(
    val supportsStillCapture: Boolean = true,
    val supportsPersistentPhoto: Boolean = false,
    val supportsVideoRecording: Boolean = false,
    val supportsAudioRecording: Boolean = false,
    val maxVideoDurationMillis: Long? = null,
    val formats: List<ImageFormat> = listOf(ImageFormat.JPEG),
)

data class MicrophoneCapabilities(
    val supportsRawStream: Boolean = false,
    val sampleRate: Int = 16_000,
)