package com.team.smartglasses.voice

/** STT + anh xa ngon ngu Viet/Anh. Nguoi 3. */
interface SpeechRecognizer {
    suspend fun transcribe(pcm16k: ByteArray, language: String): String
}

class LanguageMapper {
    fun toCode(text: String): String {
        val t = text.lowercase()
        return if (t.contains("tiếng anh") || t.contains("english")) "en" else "vi"
    }
}
