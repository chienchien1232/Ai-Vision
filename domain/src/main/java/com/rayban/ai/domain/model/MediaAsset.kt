package com.rayban.ai.domain.model

data class MediaAsset(
    val uri: String,
    val type: MediaType,
    val createdAtMillis: Long,
    val durationMillis: Long? = null,
)

enum class MediaType {
    Photo,
    Video,
}

data class VideoRecordingRequest(
    val withAudio: Boolean = true,
    val maxDurationMillis: Long = DEFAULT_MAX_DURATION_MILLIS,
) {
    companion object {
        const val DEFAULT_MAX_DURATION_MILLIS = 30_000L
    }
}