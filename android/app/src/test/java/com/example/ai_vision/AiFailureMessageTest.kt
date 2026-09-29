package com.example.ai_vision

import com.example.ai_vision.ai.*
import org.junit.Assert.*
import org.junit.Test

class AiFailureMessageTest {
    @Test fun authenticationIsNotPresentedAsQuota() {
        val message = aiFailureMessage(AiBackendHttpException(502, "AI_AUTH_FAILED"))
        assertTrue(message.contains("không phải lỗi hạn mức"))
        assertTrue(message.contains("chưa xác thực"))
    }
    @Test fun quotaAndAppTokenAreDistinguished() {
        assertTrue(aiFailureMessage(AiBackendHttpException(429, "AI_QUOTA")).contains("chạm hạn mức"))
        assertTrue(aiFailureMessage(AiBackendHttpException(401, "UNAUTHORIZED")).contains("app token"))
    }
    @Test fun arbitraryServerOrExceptionContentIsNotLeaked() {
        assertFalse(aiFailureMessage(java.io.IOException("secret-example-token")).contains("secret-example-token"))
        assertFalse(aiFailureMessage(AiBackendHttpException(502, "secret-example-key")).contains("secret-example-key"))
    }
}
