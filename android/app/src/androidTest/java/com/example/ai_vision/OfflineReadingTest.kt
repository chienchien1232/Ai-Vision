package com.example.ai_vision

import android.app.Application
import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ai_vision.ai.*
import com.example.ai_vision.speech.*
import com.example.ai_vision.ui.*
import com.example.ai_vision.vision.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineReadingTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun waitIdle(controller: DeviceController) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (true) {
            var busy = true
            main { busy = controller.actionState is ActionState.Running }
            if (!busy) return
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "Action timed out" }
            Thread.sleep(20)
        }
    }
    private fun jpeg(text: String = "", turn: Int = 0): ByteArray {
        val original = Bitmap.createBitmap(800, 300, Bitmap.Config.ARGB_8888)
        Canvas(original).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 44f }
            drawText(text, 30f, 120f, paint)
        }
        val rotated = if (turn == 0) original else Bitmap.createBitmap(original, 0, 0, original.width, original.height,
            Matrix().apply { postRotate(turn.toFloat()) }, true)
        return try {
            ByteArrayOutputStream().use { out -> assertTrue(rotated.compress(Bitmap.CompressFormat.JPEG, 95, out)); out.toByteArray() }
        } finally { if (rotated !== original) rotated.recycle(); original.recycle() }
    }
    private fun renderer(onRender: (String) -> Unit = {}): SpeechRenderer = object : SpeechRenderer {
        override suspend fun renderPcm16k(text: String, languageTag: String): ByteArray { onRender(text); return byteArrayOf(0, 0) }
        override fun stop() {}
        override fun close() {}
    }
    private fun audio(onPlay: () -> Unit = {}): PcmOutput = object : PcmOutput {
        override suspend fun play(pcm: ByteArray) { onPlay() }
        override fun stop() {}
        override fun close() {}
    }
    private fun forbiddenAi(): AiClient = object : AiClient {
        override suspend fun answer(config: AiBackendConfig, question: AiQuestion): String = error("OCR must not call Gemini")
        override suspend fun speak(config: AiBackendConfig, sessionId: String, text: String, languageTag: String): ByteArray = error("No cloud TTS")
    }

    @Test fun bundledOcrReadsVietnameseAndEnglishAndRotation() = runBlocking {
        val reader = LocalTextReader()
        val vietnamese = reader.read(jpeg("Xin chào Việt Nam"), 0)
        assertTrue("OCR returned: $vietnamese", vietnamese.contains("Việt Nam"))
        assertTrue(reader.read(jpeg("OFFLINE TEXT TEST", 90), 270).contains("OFFLINE TEXT TEST"))
    }
    @Test fun blankAndBrokenImagesDoNotInventText() = runBlocking {
        val reader = LocalTextReader()
        assertTrue(reader.read(jpeg(), 0).isBlank())
        try { reader.read(byteArrayOf(1, 2, 3), 0); fail("Broken JPEG was accepted") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun nativeOcrToVietnameseTtsToPhonePcmPlaybackCompletes() = runBlocking {
        val text = LocalTextReader().read(jpeg("Xin chào Việt Nam"), 0)
        assertTrue(text.contains("Việt Nam"))
        val speech = LocalSpeechRenderer(app)
        val player = PhonePcmPlayer()
        try {
            val pcm = speech.renderPcm16k(text, "vi-VN")
            assertTrue(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= 960_000)
            assertTrue(com.example.ai_vision.device.inspectPcm16(pcm).rms > 100)
            withTimeout(15_000) { player.play(pcm) }
        } finally { player.close(); speech.close() }
    }
    @Test fun readNextAndReplayReuseTextAndPhotoWithoutAi() {
        var reads = 0; var renders = 0; var plays = 0
        val reader = TextReader { _, _ -> reads++; "Đây là đoạn thứ nhất.\nĐây là đoạn thứ hai." }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = DeviceController(app, scope, renderer { renders++ }, audio { plays++ }, forbiddenAi(), reader)
        try {
            main { controller.demoMode = true; controller.connect(); controller.inputText = "Đọc chữ trước mặt tôi"; controller.submitInput() }
            waitIdle(controller)
            var captured: com.example.ai_vision.device.CapturedImage? = null
            main { assertTrue(controller.canReadNext); captured = controller.image; controller.readNext() }
            waitIdle(controller)
            main { assertFalse(controller.canReadNext); controller.replayAnswer() }
            waitIdle(controller)
            main { assertSame(captured, controller.image); assertTrue(controller.answerText.contains("thứ hai")) }
            assertEquals(1, reads); assertEquals(2, renders); assertEquals(3, plays)
        } finally { main { controller.disconnect() }; scope.cancel() }
    }
    @Test fun ocrFailureDoesNotDisconnectAndLocalCommandsStillWork() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = DeviceController(app, scope, renderer(), audio(), forbiddenAi(), TextReader { _, _ -> throw IOException("Missing OCR") })
        try {
            main { controller.demoMode = true; controller.connect(); controller.readText() }
            waitIdle(controller)
            main { assertTrue(controller.deviceState is DeviceState.Connected); assertTrue(controller.actionState is ActionState.Error); controller.ping() }
            waitIdle(controller)
            main { assertTrue(controller.actionState is ActionState.Success) }
        } finally { main { controller.disconnect() }; scope.cancel() }
    }
    @Test fun failedSpeechPreservesOcrAndRetryDoesNotReadAgain() {
        var reads = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = DeviceController(app, scope, renderer { throw IOException("Missing TTS") }, audio(), forbiddenAi(),
            TextReader { _, _ -> reads++; "Văn bản vẫn được giữ." })
        try {
            main { controller.demoMode = true; controller.connect(); controller.readText() }
            waitIdle(controller)
            main { assertEquals("Văn bản vẫn được giữ.", controller.answerText); assertTrue(controller.actionState is ActionState.Success); controller.replayAnswer() }
            waitIdle(controller)
            assertEquals(1, reads)
            main { assertTrue(controller.audioStatus.contains("Missing TTS")) }
        } finally { main { controller.disconnect() }; scope.cancel() }
    }
    @Test fun cancellationDropsOldOcrResultAndNeverSpeaksIt() {
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        var plays = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = DeviceController(app, scope, renderer(), audio { plays++ }, forbiddenAi(), TextReader { _, _ ->
            withContext(NonCancellable) { entered.complete(Unit); finish.await(); "STALE TEXT" }
        })
        try {
            main { controller.demoMode = true; controller.connect(); controller.readText() }
            runBlocking { withTimeout(5000) { entered.await() } }
            main { controller.cancelAction(); assertTrue(controller.actionState is ActionState.Running) }
            finish.complete(Unit)
            waitIdle(controller)
            main { assertEquals(ActionState.Idle, controller.actionState); assertEquals("", controller.answerText); assertFalse(controller.canReplayAudio) }
            assertEquals(0, plays)
        } finally { finish.complete(Unit); main { controller.disconnect() }; scope.cancel() }
    }
    @Test fun sceneAlwaysUsesFreshPhotoAndQuotaDoesNotBreakLocalReading() {
        val photos = mutableListOf<ByteArray>()
        val quotaAi = object : AiClient {
            override suspend fun answer(config: AiBackendConfig, question: AiQuestion): String {
                photos += requireNotNull(question.jpeg)
                throw AiBackendHttpException(429, "AI_QUOTA")
            }
            override suspend fun speak(config: AiBackendConfig, sessionId: String, text: String, languageTag: String): ByteArray = error("No cloud speech")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = DeviceController(app, scope, renderer(), audio(), quotaAi, TextReader { _, _ -> "Đọc local sau lỗi cloud." })
        try {
            main { controller.demoMode = true; controller.connect(); controller.inputText = "Trước mặt tôi có gì?"; controller.submitInput() }
            waitIdle(controller)
            main { assertTrue((controller.actionState as ActionState.Error).message.contains("hạn mức")); controller.submitInput() }
            waitIdle(controller)
            assertEquals(2, photos.size); assertNotSame(photos[0], photos[1])
            main { controller.readText() }; waitIdle(controller)
            main { assertTrue(controller.actionState is ActionState.Success); assertTrue(controller.answerText.contains("local")) }
            assertEquals(2, photos.size)
        } finally { main { controller.disconnect() }; scope.cancel() }
    }

    @Test fun cancelledAiResultCannotSpeakOrOverwriteTheNextLocalAnswer() {
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val spoken = mutableListOf<String>()
        val ai = object : AiClient {
            override suspend fun answer(config: AiBackendConfig, question: AiQuestion): String = withContext(NonCancellable) {
                entered.complete(Unit); finish.await(); "STALE AI ANSWER"
            }
            override suspend fun speak(config: AiBackendConfig, sessionId: String, text: String, languageTag: String): ByteArray = error("No cloud TTS")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = DeviceController(app, scope, renderer { spoken += it }, audio(), ai)
        try {
            main { controller.demoMode = true; controller.connect(); controller.inputText = "Trước mặt tôi có gì?"; controller.submitInput() }
            runBlocking { withTimeout(5000) { entered.await() } }
            main { controller.cancelAction() }; finish.complete(Unit); waitIdle(controller)
            main { controller.inputText = "Tính 2 cộng 3"; controller.submitInput() }; waitIdle(controller)
            main { assertEquals("5", controller.answerText); assertTrue(controller.actionState is ActionState.Success) }
            assertEquals(listOf("5"), spoken)
        } finally { finish.complete(Unit); main { controller.disconnect() }; scope.cancel() }
    }

    @Test fun realBundledOcrWorksThroughDemoCaptureWithoutAiOrModelDownload() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = DeviceController(app, scope, renderer(), audio(), forbiddenAi())
        try {
            main { controller.demoMode = true; controller.connect(); controller.readText() }; waitIdle(controller)
            main {
                assertTrue(controller.actionState is ActionState.Success)
                assertTrue("Demo OCR: ${controller.answerText}", controller.answerText.contains("AI-VISION DEMO"))
                assertTrue(controller.ocrStatus.contains("Đoạn"))
            }
        } finally { main { controller.disconnect() }; scope.cancel() }
    }
}
