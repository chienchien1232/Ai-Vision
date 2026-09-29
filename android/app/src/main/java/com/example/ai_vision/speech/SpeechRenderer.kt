package com.example.ai_vision.speech

interface SpeechRenderer {
    suspend fun renderPcm16k(text: String, languageTag: String): ByteArray
    fun stop()
    fun close()
}

internal const val PHOTO_SAVED_VI = "Đã chụp và lưu ảnh."
internal const val VIDEO_SAVED_VI = "Đã lưu video."
internal const val REPEAT_VI = "Không nghe rõ. Hãy nói lại."
internal val vietnamesePresets = mapOf(
    PHOTO_SAVED_VI to "photo_saved", VIDEO_SAVED_VI to "video_saved",
    "Xin chào. Bạn cần mình giúp gì?" to "greeting",
    "Bạn có thể chụp ảnh, quay hoặc dừng video, kiểm tra kính, hỏi giờ và tính toán. Câu hỏi khác có thể dùng AI." to "help",
    REPEAT_VI to "repeat"
)

/** Bound time between native cancellation callbacks without cutting words or playback. */
internal fun speechChunks(text: String): List<String> {
    val chunks = mutableListOf<String>()
    var current = ""
    for (word in text.trim().split(Regex("\\s+"))) {
        require(word.length <= 160) { "Nội dung có từ quá dài để đọc. Nội dung chữ vẫn được giữ." }
        if (current.isNotEmpty() && current.length + 1 + word.length > 160) {
            chunks += current
            current = ""
        }
        current = if (current.isEmpty()) word else "$current $word"
    }
    if (current.isNotEmpty()) chunks += current
    return chunks
}

internal fun floatPcm16k(samples: FloatArray, sampleRate: Int): ByteArray {
    require(sampleRate in 4000..96000 && samples.isNotEmpty()) { "Invalid TTS samples" }
    if (samples.size.toLong() * 16000 / sampleRate > 16000 * 30) {
        throw java.io.IOException("Phản hồi quá dài để phát (tối đa 30 giây). Nội dung chữ vẫn được giữ.")
    }
    val pcm = ByteArray(samples.size * 2)
    samples.forEachIndexed { index, value ->
        require(value.isFinite()) { "Invalid TTS samples" }
        val sample = (value.coerceIn(-1f, 1f) * 32767).toInt()
        pcm[index * 2] = sample.toByte()
        pcm[index * 2 + 1] = (sample shr 8).toByte()
    }
    return resampleToMono16k(pcm, 1, sampleRate, 16)
}
