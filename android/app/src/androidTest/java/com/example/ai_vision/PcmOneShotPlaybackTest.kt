package com.example.ai_vision

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ai_vision.speech.LocalSpeechRenderer
import com.example.ai_vision.speech.PhonePcmPlayer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PcmOneShotPlaybackTest {
    @Test fun shortAndLongVietnameseRepliesConsumeOneBufferWithoutUnderrun() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val renderer = LocalSpeechRenderer(context)
        val player = PhonePcmPlayer()
        try {
            for (text in listOf("9", "Bản demo: ứng dụng đã nhận câu hỏi và tạo phản hồi mẫu. Hãy cấu hình backend để nhận câu trả lời AI thật.")) {
                val pcm = renderer.renderPcm16k(text, "vi-VN")
                withTimeout(20_000) { player.play(pcm) }
                val stats = requireNotNull(player.lastPlayback)
                assertEquals(pcm.size / 2, stats.inputFrames)
                assertEquals(stats.bufferFrames, stats.playedFrames)
                assertTrue(stats.bufferFrames >= stats.inputFrames + 1600)
                assertEquals(0, stats.underruns)
                assertFalse(player.isPlaying)
                delay(200)
                assertEquals(stats, player.lastPlayback)
                assertFalse(player.isPlaying)
            }
        } finally { player.close(); renderer.close() }
    }

    @Test fun stopWithoutCancellingCallerEndsPlaybackAndNextPlayStartsFresh() = runBlocking {
        val player = PhonePcmPlayer()
        try {
            val job = launch(Dispatchers.IO) { player.play(ByteArray(16000 * 2 * 5)) }
            withTimeout(3000) { while (!player.isPlaying) delay(5) }
            player.stop()
            withTimeout(3000) { job.join() }
            assertTrue(job.isCancelled)
            assertFalse(player.isPlaying)
            assertNull(player.lastPlayback)
            // Cover a shorter-than-minimum-buffer reply, including one complete PCM frame.
            player.play(byteArrayOf(0, 0))
            val stats = requireNotNull(player.lastPlayback)
            assertEquals(1, stats.inputFrames)
            assertEquals(stats.bufferFrames, stats.playedFrames)
            assertEquals(0, stats.underruns)
        } finally { player.close() }
    }
}
