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

    @Test fun questionParticleAndZeroAreNotActionNegation() {
        listOf("Đây có phải chai nước không?", "Vật này có nguy hiểm không?", "Is this a bottle?").forEach {
            assertEquals(it, AppIntent.Question(it, true), IntentRouter.route(it))
        }
        listOf("Không khí là gì?", "Tại sao không có mưa?", "Why don't birds have teeth?").forEach {
            assertEquals(it, AppIntent.Question(it, false), IntentRouter.route(it))
        }
        assertEquals("2", LocalAnswerHandler.answer(IntentRouter.route("không cộng hai"), "vi-VN"))
        listOf("Kính ơi, đừng chụp ảnh", "Tôi không muốn chụp ảnh", "Không được đọc chữ", "do not record video").forEach {
            val intent = IntentRouter.route(it)
            assertTrue(it, intent is AppIntent.Clarification)
            assertNull(CloudPolicy.reason(intent))
        }
    }

    @Test fun naturalSceneRequestsRequireFreshPhotoButUnclearRequestsAskLocally() {
        listOf("Mô tả căn phòng", "Nhìn giúp tôi", "Vật bên trái là gì?", "Vật bên phải là gì?", "Describe the room").forEach {
            assertEquals(it, AppIntent.Question(it, true), IntentRouter.route(it))
            assertEquals("SCENE_QUESTION", CloudPolicy.reason(IntentRouter.route(it)))
        }
        listOf("Đọc giúp tôi", "Xem giúp tôi", "Xem cái này", "Look at this").forEach {
            val intent = IntentRouter.route(it)
            assertTrue(it, intent is AppIntent.Clarification)
            assertNull(CloudPolicy.reason(intent))
        }
    }

    @Test fun spokenNumbersUseLocalArithmeticWithoutGuessingUnsupportedNumbers() {
        val examples = mapOf(
            "hai cộng ba" to "5", "hai cộng ba bằng bao nhiêu" to "5", "Tính hai mươi mốt trừ bốn" to "17",
            "một trăm lẻ năm nhân hai" to "210", "hai mươi tư cộng mười lăm" to "39",
            "âm hai nhân ba" to "-6", "hai phẩy năm cộng 3" to "5.5",
            "chín trăm chín mươi chín trừ chín" to "990", "8 chia không" to "Không thể chia cho không.",
            "two plus three" to "5", "one hundred and five minus twenty one" to "84",
            "negative two times three" to "-6", "two point five divided by zero point five" to "5",
            "2+3" to "5", "-2 * -3" to "6"
        )
        examples.forEach { (text, result) ->
            val intent = IntentRouter.route(text)
            assertTrue(text, intent is AppIntent.Calculate)
            assertNull(CloudPolicy.reason(intent))
            assertEquals(text, result, LocalAnswerHandler.answer(intent, "vi-VN"))
        }
        listOf("Tính hai ba cộng bốn", "một triệu cộng hai", "một triệu cộng hai triệu", "one thousand plus two thousand",
            "calculate twenty hundred plus one").forEach {
            val intent = IntentRouter.route(it)
            assertTrue(it, intent is AppIntent.Clarification)
            assertNull(CloudPolicy.reason(intent))
        }
    }

    @Test fun everySupportedLocalIntentHasNoCloudReason() {
        listOf("Chụp ảnh", "Đọc chữ", "Đọc tiếp", "Phát lại", "Dừng", "Quay video", "Dừng quay video",
            "Trạng thái kính", "Ping", "Mấy giờ", "Hôm nay ngày mấy", "Hai cộng ba", "Tăng âm lượng",
            "Đừng chụp ảnh", "Xem giúp tôi").forEach { assertNull(it, CloudPolicy.reason(IntentRouter.route(it))) }
    }

    @Test fun incompleteArithmeticAndUnaccentedNegationAskLocally() {
        listOf("Tính", "Tính hai", "Hai cộng", "8 chia", "calculate", "two plus", "dung chup anh", "dung doc chu").forEach {
            assertTrue(it, IntentRouter.route(it) is AppIntent.Clarification)
            assertNull(it, CloudPolicy.reason(IntentRouter.route(it)))
        }
        assertEquals(AppIntent.StopSpeaking, IntentRouter.route("Dừng nói"))
        assertEquals(AppIntent.StopVideo, IntentRouter.route("dung quay video"))
        assertTrue(IntentRouter.route("Nhân vật này là ai?") is AppIntent.Question)
    }
}
