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
    val channels: Int = 1,
    val encoding: AudioEncoding = AudioEncoding.PCM_16BIT,
    val maxDurationMillis: Long = 10_000L,
)

/** PCM encoding of [AudioChunk.data]. XIAO ESP32S3 Sense I2S default is 16 kHz mono PCM16. */
enum class AudioEncoding {
    PCM_16BIT,
}

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