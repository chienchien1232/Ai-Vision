package com.rayban.ai.domain.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBasedCommandRouterTest {

    private val router = RuleBasedCommandRouter()

    private fun route(utterance: String): CommandMatch = router.route(
        CommandRequest(
            interactionId = 1L,
            utterance = utterance,
            source = CommandSource.Voice,
            requestedAtMillis = 0L,
        ),
    )

    private fun assertCommand(utterance: String, expected: AssistantCommand) {
        val match = route(utterance)
        assertEquals("utterance: $utterance", CommandMatch.Matched(expected), match)
    }

    @Test
    fun `vietnamese describe scene phrases`() {
        assertCommand("Tôi đang nhìn thấy gì?", AssistantCommand.DescribeScene)
        assertCommand("Trước mặt có gì", AssistantCommand.DescribeScene)
        assertCommand("Mô tả cảnh trước mặt giúp tôi", AssistantCommand.DescribeScene)
        assertCommand("Xung quanh có gì", AssistantCommand.DescribeScene)
    }

    @Test
    fun `ascii vietnamese describe scene without diacritics`() {
        assertCommand("toi dang nhin thay gi", AssistantCommand.DescribeScene)
        assertCommand("truoc mat co gi", AssistantCommand.DescribeScene)
    }

    @Test
    fun `english describe scene phrases`() {
        assertCommand("What am I looking at?", AssistantCommand.DescribeScene)
        assertCommand("What do you see?", AssistantCommand.DescribeScene)
        assertCommand("Describe the scene", AssistantCommand.DescribeScene)
        assertCommand("What's in front of me?", AssistantCommand.DescribeScene)
    }

    @Test
    fun `identify object phrases in both languages`() {
        assertCommand("Cái này là gì?", AssistantCommand.IdentifyObject)
        assertCommand("Đây là vật gì", AssistantCommand.IdentifyObject)
        assertCommand("What is this?", AssistantCommand.IdentifyObject)
        assertCommand("What's that?", AssistantCommand.IdentifyObject)
    }

    @Test
    fun `read text phrases in both languages`() {
        assertCommand("Đọc chữ này", AssistantCommand.ReadText)
        assertCommand("Biển này ghi gì", AssistantCommand.ReadText)
        assertCommand("doc chu truoc mat", AssistantCommand.ReadText)
        assertCommand("Read the text in front of me", AssistantCommand.ReadText)
        assertCommand("What does this say?", AssistantCommand.ReadText)
    }

    @Test
    fun `take photo phrases in both languages`() {
        assertCommand("Chụp ảnh", AssistantCommand.TakePhoto)
        assertCommand("Chụp hình giúp tôi", AssistantCommand.TakePhoto)
        assertCommand("Take a photo", AssistantCommand.TakePhoto)
        assertCommand("Take a picture please", AssistantCommand.TakePhoto)
    }

    @Test
    fun `record video phrases in both languages`() {
        assertCommand("Quay video", AssistantCommand.RecordVideo)
        assertCommand("Quay clip 30 giây", AssistantCommand.RecordVideo)
        assertCommand("Record a video", AssistantCommand.RecordVideo)
        assertCommand("Start recording", AssistantCommand.RecordVideo)
    }

    @Test
    fun `summarize scene phrases in both languages`() {
        assertCommand("Tóm tắt thứ trước mặt", AssistantCommand.SummarizeScene)
        assertCommand("tom tat canh", AssistantCommand.SummarizeScene)
        assertCommand("Summarize the scene", AssistantCommand.SummarizeScene)
        assertCommand("Give me a summary", AssistantCommand.SummarizeScene)
    }

    @Test
    fun `stop phrases in both languages`() {
        assertCommand("Dừng lại", AssistantCommand.Stop)
        assertCommand("Hủy", AssistantCommand.Stop)
        assertCommand("Thôi", AssistantCommand.Stop)
        assertCommand("Stop", AssistantCommand.Stop)
        assertCommand("Cancel", AssistantCommand.Stop)
        assertCommand("Stop recording", AssistantCommand.Stop)
    }

    @Test
    fun `repeat phrases in both languages`() {
        assertCommand("Lặp lại câu trả lời", AssistantCommand.RepeatLastAnswer)
        assertCommand("Nói lại", AssistantCommand.RepeatLastAnswer)
        assertCommand("Repeat that", AssistantCommand.RepeatLastAnswer)
        assertCommand("Say that again", AssistantCommand.RepeatLastAnswer)
        assertCommand("What did you say?", AssistantCommand.RepeatLastAnswer)
    }

    @Test
    fun `polite prefixes are stripped`() {
        assertCommand("Làm ơn dừng lại", AssistantCommand.Stop)
        assertCommand("Hay chụp ảnh", AssistantCommand.TakePhoto)
        assertCommand("Please stop", AssistantCommand.Stop)
        assertCommand("Can you take a photo", AssistantCommand.TakePhoto)
    }

    @Test
    fun `case and punctuation are ignored`() {
        assertCommand("CHỤP ẢNH!", AssistantCommand.TakePhoto)
        assertCommand("Quay Video?", AssistantCommand.RecordVideo)
        assertCommand("  Dừng lại.  ", AssistantCommand.Stop)
    }

    @Test
    fun `bus stop does not trigger stop command`() {
        val match = route("What is at the bus stop?")
        val command = (match as CommandMatch.Matched).command
        assertTrue(command is AssistantCommand.OpenQuestion)
    }

    @Test
    fun `compound commands are rejected as ambiguous`() {
        val match = route("Take a photo and describe the scene")
        val error = (match as CommandMatch.Rejected).error
        assertTrue(error is CommandError.Ambiguous)
        assertEquals(
            setOf(CommandKind.TakePhoto, CommandKind.DescribeScene),
            (error as CommandError.Ambiguous).commands,
        )
    }

    @Test
    fun `chup anh roi doc chu is ambiguous`() {
        val match = route("Chụp ảnh rồi đọc chữ")
        assertTrue((match as CommandMatch.Rejected).error is CommandError.Ambiguous)
    }

    @Test
    fun `unsupported actions are rejected`() {
        assertRejected("Gửi tin nhắn cho mẹ", CommandError.UnsupportedAction)
        assertRejected("Send a message to mom", CommandError.UnsupportedAction)
        assertRejected("Phát nhạc", CommandError.UnsupportedAction)
        assertRejected("Delete this photo", CommandError.UnsupportedAction)
    }

    @Test
    fun `unmatched questions fall back to open question with original text`() {
        val match = route("What color is the sky today?")
        val command = (match as CommandMatch.Matched).command
        assertEquals(
            AssistantCommand.OpenQuestion("What color is the sky today?"),
            command,
        )
    }

    @Test
    fun `vietnamese open question keeps diacritics`() {
        val match = route("Chiếc kính này chạy bằng gì?")
        val command = (match as CommandMatch.Matched).command
        assertEquals(
            AssistantCommand.OpenQuestion("Chiếc kính này chạy bằng gì?"),
            command,
        )
    }

    @Test
    fun `blank input is rejected`() {
        assertRejected("   ", CommandError.EmptyInput)
        assertRejected("", CommandError.EmptyInput)
    }

    private fun assertRejected(utterance: String, expected: CommandError) {
        val match = route(utterance)
        assertEquals("utterance: $utterance", CommandMatch.Rejected(expected), match)
    }
}