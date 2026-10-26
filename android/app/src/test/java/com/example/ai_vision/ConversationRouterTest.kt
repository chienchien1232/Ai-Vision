package com.example.ai_vision

import com.example.ai_vision.core.*
import org.junit.Assert.*
import org.junit.Test

class ConversationRouterTest {
    @Test fun shortReplyResolvesOnlyTheCurrentClarification() {
        val router = ConversationRouter()
        assertTrue(router.route("Xem giúp tôi") is AppIntent.Clarification)
        assertEquals(AppIntent.ReadText, router.route("đọc"))
        assertTrue(router.route("đọc") is AppIntent.Clarification)
        router.route("Look at this")
        assertEquals(AppIntent.Question("scene", true), router.route("scene"))
        assertEquals("SCENE_QUESTION", CloudPolicy.reason(AppIntent.Question("scene", true)))
    }

    @Test fun expiredOrClearedContextCannotExecuteAnOldChoice() {
        var now = 100L
        val router = ConversationRouter { now }
        router.route("Xem giúp tôi")
        now += 30_001
        assertEquals(AppIntent.Question("mô tả", false), router.route("mô tả"))
        router.route("Xem giúp tôi")
        router.clear()
        assertEquals(AppIntent.Question("text", false), router.route("text"))
    }

    @Test fun negationAndNewCommandsSupersedePendingChoice() {
        val router = ConversationRouter()
        router.route("Xem giúp tôi")
        val negated = router.route("Đừng đọc")
        assertTrue(negated is AppIntent.Clarification)
        assertNull(CloudPolicy.reason(negated))
        assertEquals(AppIntent.Question("scene", false), router.route("scene"))
        router.route("Xem giúp tôi")
        assertEquals(AppIntent.Capture, router.route("Chụp ảnh"))
        assertEquals(AppIntent.Question("text", false), router.route("text"))
    }

    @Test fun clarificationHasAnEnglishPromptAndExplicitFollowupStillWorks() {
        val router = ConversationRouter()
        val prompt = router.route("Look at this")
        assertTrue(LocalAnswerHandler.answer(prompt, "en-US").startsWith("Would you like"))
        assertEquals(AppIntent.ReadText, router.route("read text"))
        assertNull(CloudPolicy.reason(AppIntent.ReadText))
    }
}
