package com.team.smartglasses.speech

/** TTS + dau ra am thanh. Nguoi 3. */
interface TextSpeaker {
    suspend fun speak(text: String, language: String)
}

interface AudioOutput {
    fun play(pcm: ByteArray)
    fun stop()
}
