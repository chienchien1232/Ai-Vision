package com.rayban.ai.domain.audio

interface SpeechSynthesizer {
    suspend fun synthesize(text: String): SpeechAudio
}

data class SpeechAudio(val pcm: ByteArray, val sampleRate: Int = 24000, val channels: Int = 1) {
    fun validate() {
        if (sampleRate != 24000 || channels != 1 || pcm.isEmpty() || pcm.size % 2 != 0 || pcm.size > 12_000_000) {
            throw TranscriptionException(SpeechError.Audio)
        }
    }
}
