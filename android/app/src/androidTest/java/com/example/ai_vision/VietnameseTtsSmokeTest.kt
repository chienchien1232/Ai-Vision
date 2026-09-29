package com.example.ai_vision

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ai_vision.device.inspectPcm16
import com.example.ai_vision.speech.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VietnameseTtsSmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun phoneDemoPcmPlaybackCompletesAndCancelReleasesTrack() = runBlocking {
        val player = PhonePcmPlayer()
        try {
            val pcm = context.assets.open("tts/replies/photo_saved.pcm").use { it.readBytes() }
            withTimeout(5000) { player.play(pcm) }
            val job = launch(Dispatchers.IO) { player.play(ByteArray(16000 * 2 * 5)) }
            delay(100)
            job.cancel(); player.stop()
            withTimeout(3000) { job.join() }
            assertTrue(job.isCancelled)
            withTimeout(5000) { player.play(pcm) }
        } finally { player.close() }
    }
    @Test fun allPresetsRenderWithoutLoadingNativeOrSystemVoice() = runBlocking {
        val renderer = LocalSpeechRenderer(context)
        try {
            for (text in vietnamesePresets.keys) {
                val started = SystemClock.elapsedRealtime()
                val pcm = renderer.renderPcm16k(text, "vi-VN")
                assertTrue(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= GLASS_PCM_MAX_BYTES)
                assertTrue(inspectPcm16(pcm).rms > 100)
                assertFalse(renderer.modelLoaded)
                Log.i("TTS_SMOKE", "presetReadMs=${SystemClock.elapsedRealtime() - started}")
            }
        } finally { renderer.close() }
    }
    @Test fun nativeModelReadsVietnameseNumbersAndReleasesAfterIdle() = runBlocking {
        val renderer = LocalSpeechRenderer(context, idleMillis = 500)
        try {
            for (text in listOf("Đây là tiếng Việt có dấu chạy hoàn toàn offline.", "Bây giờ là 12:30, ngày 28 tháng 9.",
                "Kết quả là âm 3,5. Một trăm hai mươi ba.")) {
                val started = SystemClock.elapsedRealtime()
                val pcm = renderer.renderPcm16k(text, "vi-VN")
                assertTrue(inspectPcm16(pcm).rms > 100)
                assertTrue(pcm.size <= GLASS_PCM_MAX_BYTES)
                Log.i("TTS_SMOKE", "nativeMs=${SystemClock.elapsedRealtime() - started} audioMs=${pcm.size * 1000L / 32000} pssKb=${Debug.getPss()}")
            }
            withTimeout(5000) { while (renderer.modelLoaded) delay(50) }
            assertFalse(renderer.modelLoaded)
        } finally { renderer.close() }
    }
    @Test fun nativeCancelDoesNotPublishOldAudioAndNextRequestWorks() = runBlocking {
        val renderer = LocalSpeechRenderer(context)
        try {
            renderer.renderPcm16k("Khởi động giọng nói tiếng Việt.", "vi-VN")
            val job = launch(Dispatchers.Default) { renderer.renderPcm16k("Đây là một phản hồi tiếng Việt. ".repeat(30), "vi-VN") }
            delay(100)
            job.cancel(); renderer.stop()
            withTimeout(10_000) { job.join() }
            assertTrue(job.isCancelled)
            assertTrue(renderer.renderPcm16k("Sau khi hủy vẫn đọc được.", "vi-VN").isNotEmpty())
        } finally { renderer.close() }
    }
    @Test fun overThirtySecondsIsRejectedWithoutTruncatingToPlayableAudio() = runBlocking {
        val renderer = LocalSpeechRenderer(context)
        try {
            try {
                renderer.renderPcm16k("Đây là câu thử tiếng Việt đủ dài để vượt giới hạn âm thanh. ".repeat(35), "vi-VN")
                fail("Expected duration rejection")
            } catch (error: java.io.IOException) { assertTrue(error.message.orEmpty().contains("30")) }
        } finally { renderer.close() }
    }
}
