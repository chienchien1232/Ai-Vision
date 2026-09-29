package com.example.ai_vision

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ai_vision.ai.*
import com.example.ai_vision.speech.*
import com.example.ai_vision.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReplyPlaybackTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun waitIdle(controller: DeviceController) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (true) {
            var busy = true
            main { busy = controller.actionState is ActionState.Running }
            if (!busy) return
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "Action timed out" }
            Thread.sleep(20)
        }
    }
    @Test fun replayUsesSavedPcmWithoutCallingAiOrRendererAgain() {
        var renders = 0
        var answers = 0
        var failPlayback = true
        val renderer = object : SpeechRenderer {
            override suspend fun renderPcm16k(text: String, languageTag: String): ByteArray { renders++; return byteArrayOf(0, 0) }
            override fun stop() {}
            override fun close() {}
        }
        val audio = object : PcmOutput {
            override suspend fun play(pcm: ByteArray) { if (failPlayback) error("Speaker unavailable") }
            override fun stop() {}
            override fun close() {}
        }
        val ai = object : AiClient {
            override suspend fun answer(config: AiBackendConfig, question: AiQuestion): String { answers++; return "Bầu trời có màu xanh." }
            override suspend fun speak(config: AiBackendConfig, sessionId: String, text: String, languageTag: String): ByteArray = error("Cloud TTS must never be called")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = DeviceController(app, scope, renderer, audio, ai)
        try {
            main { controller.demoMode = true; controller.connect(); controller.inputText = "Vì sao bầu trời màu xanh?"; controller.submitInput() }
            waitIdle(controller)
            main {
                assertTrue(controller.actionState is ActionState.Success)
                assertEquals("Bầu trời có màu xanh.", controller.answerText)
                assertTrue(controller.audioStatus.contains("Chưa phát"))
                assertTrue(controller.canReplayAudio)
                failPlayback = false; controller.replayAnswer()
            }
            waitIdle(controller)
            assertEquals(1, answers); assertEquals(1, renders)
            main { assertEquals("Đã phát lại", controller.audioStatus) }
        } finally { main { controller.disconnect() }; scope.cancel() }
    }
    @Test fun savedPhotoRemainsSuccessfulWhenSynthesisFailsAndReplayDoesNotCaptureAgain() {
        val renderer = object : SpeechRenderer {
            override suspend fun renderPcm16k(text: String, languageTag: String): ByteArray = error("Missing model")
            override fun stop() {}
            override fun close() {}
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = DeviceController(app, scope, renderer)
        try {
            main { controller.demoMode = true; controller.connect(); controller.capture() }
            waitIdle(controller)
            var saved: com.example.ai_vision.device.CapturedImage? = null
            main {
                assertTrue(controller.actionState is ActionState.Success)
                assertTrue(controller.taskResultText.contains("Ảnh đã lưu"))
                assertTrue(controller.audioStatus.contains("Missing model"))
                saved = controller.image
                controller.replayAnswer()
            }
            waitIdle(controller)
            main { assertSame(saved, controller.image); assertTrue(controller.actionState is ActionState.Success) }
        } finally { main { controller.disconnect() }; scope.cancel() }
    }
    @Test fun speakingKeepsBusyAndCancelStopsPlayback() {
        val playback = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val renderer = object : SpeechRenderer {
            override suspend fun renderPcm16k(text: String, languageTag: String) = byteArrayOf(0, 0)
            override fun stop() {}
            override fun close() {}
        }
        val audio = object : PcmOutput {
            override suspend fun play(pcm: ByteArray) { started.complete(Unit); playback.await() }
            override fun stop() { playback.cancel() }
            override fun close() = stop()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = DeviceController(app, scope, renderer, audio)
        try {
            main { controller.demoMode = true; controller.connect(); controller.inputText = "Xin chào"; controller.submitInput() }
            runBlocking { withTimeout(5000) { started.await() } }
            main { assertTrue(controller.actionState is ActionState.Running); controller.cancelAction() }
            waitIdle(controller)
            main { assertEquals(ActionState.Idle, controller.actionState) }
        } finally { main { controller.disconnect() }; scope.cancel() }
    }
}
