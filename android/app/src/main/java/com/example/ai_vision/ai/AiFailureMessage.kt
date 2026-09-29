package com.example.ai_vision.ai

/** Sanitized UI errors: never echo proxy bodies, keys or arbitrary exception text. */
fun aiFailureMessage(error: Exception): String = when {
    error is AiBackendHttpException && error.errorCode == "AI_AUTH_FAILED" ->
        "Gemini chưa xác thực được khóa hoặc quyền truy cập. Đây không phải lỗi hạn mức. OCR và lệnh local vẫn dùng được."
    error is AiBackendHttpException && (error.statusCode == 429 || error.errorCode == "AI_QUOTA") ->
        "Gemini đang chạm hạn mức. Không tự gọi lại; OCR và lệnh local vẫn dùng được."
    error is AiBackendHttpException && error.statusCode in setOf(401, 403) ->
        "Backend từ chối app token. Kiểm tra cài đặt kết nối; lệnh local vẫn dùng được."
    error is AiBackendHttpException && (error.statusCode == 504 || error.errorCode == "AI_TIMEOUT") ->
        "AI quá thời gian phản hồi. Không tự gọi lại; hãy dùng OCR hoặc lệnh local."
    error is IllegalArgumentException -> "Cấu hình backend hoặc câu hỏi chưa hợp lệ. Lệnh local vẫn dùng được."
    else -> "Chưa nhận được câu trả lời AI. Kiểm tra mạng hoặc backend; OCR và lệnh local vẫn dùng được."
}
