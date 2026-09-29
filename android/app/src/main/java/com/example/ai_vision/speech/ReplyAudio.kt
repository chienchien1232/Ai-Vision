package com.example.ai_vision.speech

/** Only the most recent reply is kept in RAM; replay never repeats the original action/API call. */
data class ReplyAudio(val text: String, val languageTag: String, val pcm: ByteArray? = null, val resultText: String = "")

interface PcmOutput {
    suspend fun play(pcm: ByteArray)
    fun stop()
    fun close()
}
