package com.rayban.ai.domain.audio

interface AudioTranscriber {
    val isAvailable: Boolean
    /** WAV, PCM16 mono 16 kHz, at most 12 seconds. Empty transcript means silence. */
    suspend fun transcribe(wav: ByteArray): String
}

class TranscriptionException(val error: SpeechError) : Exception("Audio transcription failed: $error")
