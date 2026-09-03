package com.rayban.ai.domain.wearable

import com.rayban.ai.domain.model.MicrophoneCapabilities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface WearableMicrophone {
    val capabilities: StateFlow<MicrophoneCapabilities>

    fun openStream(request: AudioStreamRequest): AudioSession
}

data class AudioStreamRequest(
    val sampleRate: Int = 16_000,
    val maxDurationMillis: Long = 10_000L,
)

interface AudioSession {
    val chunks: Flow<AudioChunk>
    fun stop()
    fun close()
}

class AudioChunk(
    val data: ByteArray,
    val sampleRate: Int,
    val sequence: Long,
    val isLast: Boolean = false,
)