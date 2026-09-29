package com.example.ai_vision.ai

import kotlinx.coroutines.delay

/** Exercises session, image and TTS flow without pretending to be a cloud model. */
class DemoAiClient : AiClient {
    override suspend fun answer(config: AiBackendConfig, question: AiQuestion): String {
        delay(500)
        return if (question.jpeg != null) {
            "Bản demo: ảnh này do ứng dụng tạo để thử luồng chụp → hỏi → đọc câu trả lời. Chưa có phân tích AI hoặc ảnh từ kính."
        } else {
            "Bản demo: ứng dụng đã nhận câu hỏi và tạo phản hồi mẫu. Hãy cấu hình backend để nhận câu trả lời AI thật."
        }
    }

    override suspend fun speak(config: AiBackendConfig, sessionId: String, text: String, languageTag: String): ByteArray {
        throw UnsupportedOperationException("Demo mode uses the phone speaker, never cloud speech")
    }
}
