package com.example.ai_vision

import com.example.ai_vision.core.AppIntent
import com.example.ai_vision.core.IntentRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.example.ai_vision.core.LocalAnswerHandler
import com.example.ai_vision.core.CloudPolicy
import org.junit.Test

class IntentRouterTest {
    @Test
    fun knownLocalCommandsStayLocal() {
        assertEquals(AppIntent.Capture, IntentRouter.route("Chụp ảnh!"))
        assertEquals(AppIntent.Ping, IntentRouter.route("ping"))
        assertEquals(AppIntent.Status, IntentRouter.route("Trạng thái kính"))
        assertEquals(AppIntent.StartVideo, IntentRouter.route("Quay video"))
        assertEquals(AppIntent.StartVideo, IntentRouter.route("Bắt đầu quay video"))
        assertEquals(AppIntent.StopVideo, IntentRouter.route("Dừng quay!"))
        assertEquals(AppIntent.StopVideo, IntentRouter.route("stop video"))
        assertEquals(
            AppIntent.UnsupportedLocal("Audio recording protocol is not available yet"),
            IntentRouter.route("Bắt đầu ghi âm")
        )
    }

    @Test
    fun sceneQuestionRequestsNewPhoto() {
        val intent = IntentRouter.route("Trước mặt tôi là gì?")
        assertEquals(AppIntent.Question("Trước mặt tôi là gì?", true), intent)
        assertEquals(AppIntent.Question("Thời tiết hôm nay?", false), IntentRouter.route("Thời tiết hôm nay?"))
    }

    @Test fun naturalCommandsStripWakeAndPolitenessWithoutCloud() {
        listOf("Hi ESP chụp giúp tôi một ảnh nhé", "Kính ơi, hãy chụp ảnh", "Please take a photo").forEach {
            val intent = IntentRouter.route(it, "Hi ESP")
            assertEquals(it, AppIntent.Capture, intent)
            assertNull(CloudPolicy.reason(intent))
        }
    }

    @Test fun negatedAndAmbiguousCommandsNeverExecuteOrUseCloud() {
        listOf("Đừng chụp ảnh", "Không quay video", "Don't take a photo", "Chụp cái gì đó").forEach {
            val intent = IntentRouter.route(it)
            assertTrue(it, intent is AppIntent.Clarification)
            assertNull(CloudPolicy.reason(intent))
        }
        assertEquals(AppIntent.StopVideo, IntentRouter.route("Dừng quay video"))
    }

    @Test fun simpleAnswersStayLocalAndArithmeticIsNotEvaluatedAsCode() {
        listOf("Bây giờ là mấy giờ?", "Hôm nay ngày mấy?", "Xin chào", "Bạn làm được gì?").forEach {
            val intent = IntentRouter.route(it)
            assertTrue(intent is AppIntent.LocalQuery)
            assertNull(CloudPolicy.reason(intent))
            assertTrue(LocalAnswerHandler.answer(intent, "vi-VN").isNotBlank())
        }
        assertEquals("5.5", LocalAnswerHandler.answer(IntentRouter.route("Tính 2,5 cộng 3"), "vi-VN"))
        assertEquals("-6", LocalAnswerHandler.answer(IntentRouter.route("-2 * 3"), "en-US"))
        assertEquals("Cannot divide by zero.", LocalAnswerHandler.answer(IntentRouter.route("8 / 0"), "en-US"))
        assertEquals("BEYOND_LOCAL_HANDLERS", CloudPolicy.reason(IntentRouter.route("Vì sao bầu trời màu xanh?")))
        assertNull(CloudPolicy.reason(IntentRouter.route("Đọc chữ trước mặt tôi")))
    }

    @Test fun readingCommandsNeverCallGeminiButExplanationsStillCan() {
        listOf("Đọc chữ", "Đọc chữ trước mặt tôi", "Kính ơi hãy đọc văn bản này", "Hi ESP đọc nhãn", "read this text",
            "Đọc văn bản này cho tôi", "Đọc chữ trên nhãn giúp mình", "Đọc chữ trong ảnh này", "Đọc nội dung trước mặt tôi").forEach {
            val intent = IntentRouter.route(it, "Hi ESP")
            assertEquals(it, AppIntent.ReadText, intent)
            assertNull(CloudPolicy.reason(intent))
        }
        assertEquals(AppIntent.ReadNext, IntentRouter.route("Đọc tiếp"))
        assertTrue(IntentRouter.route("Đừng đọc chữ") is AppIntent.Clarification)
        assertEquals("SCENE_QUESTION", CloudPolicy.reason(IntentRouter.route("Giải thích chữ trong ảnh này")))
        assertEquals("SCENE_QUESTION", CloudPolicy.reason(IntentRouter.route("Trước mặt tôi có gì?")))
    }
}
