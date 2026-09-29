package com.example.ai_vision

import com.example.ai_vision.speech.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OfflineTtsTest {
    @Test fun callbackHasTypedBoxedSignatureRequiredBySherpaJni() {
        val callback = TtsProgressCallback { 1 }
        val method = callback.javaClass.getMethod("invoke", FloatArray::class.java)
        assertEquals(Integer::class.java, method.returnType)
        assertEquals(1, method.invoke(callback, floatArrayOf(0f)))
    }
    @Test fun floatAudioIsBoundedAndConvertedTo16k() {
        val pcm = floatPcm16k(FloatArray(22050) { if (it % 2 == 0) 2f else -2f }, 22050)
        assertEquals(32000, pcm.size)
        assertThrows(IllegalArgumentException::class.java) { floatPcm16k(floatArrayOf(Float.NaN), 16000) }
        assertThrows(java.io.IOException::class.java) { floatPcm16k(FloatArray(16000 * 30 + 1), 16000) }
    }
    @Test fun chunksPreserveWordsIncludingTimeAndDecimal() {
        val text = "Bây giờ là 12:30. Kết quả -3,5. " + "tiếng Việt ".repeat(40).trim()
        val chunks = speechChunks(text)
        assertTrue(chunks.all { it.length <= 160 })
        assertEquals(text, chunks.joinToString(" "))
        assertThrows(IllegalArgumentException::class.java) { speechChunks("a".repeat(161)) }
    }
    @Test fun exactlyFivePresetsIncludeGalleryAndRetry() {
        assertEquals(5, vietnamesePresets.size)
        assertEquals("photo_saved", vietnamesePresets[PHOTO_SAVED_VI])
        assertEquals("video_saved", vietnamesePresets[VIDEO_SAVED_VI])
        assertEquals("repeat", vietnamesePresets[REPEAT_VI])
    }
    @Test fun engineReusesThenReleasesAfterIdle() = runBlocking {
        val loaded = AtomicInteger()
        val released = CountDownLatch(1)
        val engine = SerialSpeechEngine({ loaded.incrementAndGet(); object : NativeSpeechHandle {
            override fun render(text: String, cancelled: () -> Boolean) = byteArrayOf(0, 0)
            override fun release() { released.countDown() }
        } }, 40)
        try {
            engine.render("one"); engine.render("two")
            assertEquals(1, loaded.get())
            assertTrue(released.await(3, TimeUnit.SECONDS))
        } finally { engine.close() }
    }
    @Test fun closeDuringGenerationNeverFreesBusyNativeObject() = runBlocking {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val rendering = AtomicBoolean()
        val unsafe = AtomicBoolean()
        val engine = SerialSpeechEngine({ object : NativeSpeechHandle {
            override fun render(text: String, cancelled: () -> Boolean): ByteArray {
                rendering.set(true); started.countDown()
                try { while (!cancelled()) Thread.sleep(2); return byteArrayOf(0, 0) }
                finally { rendering.set(false) }
            }
            override fun release() { if (rendering.get()) unsafe.set(true); released.countDown() }
        } })
        val job = launch(Dispatchers.Default) { engine.render("hello") }
        assertTrue(started.await(3, TimeUnit.SECONDS))
        engine.close()
        withTimeout(3000) { job.join() }
        assertTrue(released.await(3, TimeUnit.SECONDS))
        assertFalse(unsafe.get())
        assertTrue(job.isCancelled)
    }
    @Test fun stopDropsResultWithoutReleasingRunningHandle() = runBlocking {
        val started = CountDownLatch(1)
        val engine = SerialSpeechEngine({ object : NativeSpeechHandle {
            override fun render(text: String, cancelled: () -> Boolean): ByteArray {
                started.countDown(); while (!cancelled()) Thread.sleep(2); return byteArrayOf(0, 0)
            }
            override fun release() {}
        } })
        try {
            val job = launch(Dispatchers.Default) { engine.render("hello") }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            engine.stop()
            withTimeout(3000) { job.join() }
            assertTrue(job.isCancelled)
            assertTrue(engine.modelLoaded)
        } finally { engine.close() }
    }
    @Test fun failedModelLoadDoesNotPublishAudio() = runBlocking {
        val engine = SerialSpeechEngine({ throw java.io.IOException("Missing model") })
        try {
            try { engine.render("hello"); fail("Expected failure") } catch (_: java.io.IOException) {}
            assertFalse(engine.modelLoaded)
        } finally { engine.close() }
    }
}
