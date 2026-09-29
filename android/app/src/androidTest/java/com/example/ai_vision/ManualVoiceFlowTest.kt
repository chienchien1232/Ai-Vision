package com.example.ai_vision

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ai_vision.ai.*
import com.example.ai_vision.device.*
import com.example.ai_vision.speech.*
import com.example.ai_vision.ui.*
import com.example.ai_vision.voice.SpeechToTextEngine
import java.net.SocketTimeoutException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualVoiceFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun idle(controller: DeviceController) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (true) {
            var busy = false
            main { busy = controller.actionState is ActionState.Running }
            if (!busy) return
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "Voice action stuck" }
            Thread.sleep(20)
        }
    }
    private class Session : GlassVoiceSession {
        val events = Channel<GlassVoiceEvent>(Channel.UNLIMITED)
        override val voiceEvents = events.receiveAsFlow()
        var starts = 0; var stops = 0; var captures = 0; var downloads = 0
        var startError: Exception? = null
        var early: GlassVoiceEvent? = null
        override suspend fun startListening(languageTag: String, wakeWord: String, diagnostic: Boolean): String {
            assertEquals("", wakeWord); starts++
            startError?.let { throw it }
            early?.let { events.send(it); delay(50) }
            return "session-$starts"
        }
        override suspend fun stopListening() { stops++ }
        override suspend fun cancelActive() { stops++ }
        override suspend fun ping() = "PONG"
        override suspend fun getStatus() = GlassStatus(true)
        override suspend fun capture(): CapturedImage { captures++; return FakeGlassSession().capture() }
        override suspend fun downloadPhoto(reference: PhotoReference): CapturedImage { downloads++; return FakeGlassSession().capture() }
        override fun disconnect() { events.close() }
        override fun supportsWake() = false
        override suspend fun configureVoice(languageTag: String, wakeWord: String) { assertEquals("", wakeWord) }
        override fun supportsAudioOut() = true
        override suspend fun playAudio(pcm: ByteArray) {}
        override fun supportsVideo() = false
        override suspend fun startVideo(): String = error("Unsupported")
        override suspend fun stopVideo(): VideoDownload = error("Unsupported")
        override suspend fun downloadMedia(mediaId: String, size: Int): ByteArray = error("Unsupported")
        override suspend fun acknowledgeMedia(mediaId: String) = Unit
    }
    private class Fixture(val transcript: String = "xin chào") {
        val session = Session()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var transcriptions = 0; var answers = 0; var renders = 0; var plays = 0
        var blockedAnswer: CompletableDeferred<String>? = null
        val ai = object : AiClient {
            override suspend fun answer(config: AiBackendConfig, question: AiQuestion): String {
                answers++; return blockedAnswer?.await() ?: "Câu trả lời kiểm thử."
            }
            override suspend fun speak(config: AiBackendConfig, sessionId: String, text: String, languageTag: String): ByteArray = error("No cloud TTS")
        }
        val controller = DeviceController(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application, scope,
            speaker = object : SpeechRenderer {
                override suspend fun renderPcm16k(text: String, languageTag: String): ByteArray { renders++; return byteArrayOf(0, 0) }
                override fun stop() {}; override fun close() {}
            }, demoAudio = object : PcmOutput {
                override suspend fun play(pcm: ByteArray) { plays++ }
                override fun stop() {}; override fun close() {}
            }, demoAiClient = ai,
            localSpeech = object : SpeechToTextEngine {
                override suspend fun transcribe(pcm: ByteArray, languageTag: String): String { transcriptions++; return transcript }
            }, demoSessionFactory = { session })
        fun close() { controller.disconnect(); scope.cancel() }
    }
    @Test fun onlyAppWakeAcceptsTheCurrentRecordingAndLocalAnswerNeverCallsCloud() {
        val fixture = Fixture(); val c = fixture.controller
        try {
            main { c.demoMode = true; c.connect(); fixture.session.events.trySend(GlassVoiceEvent.Utterance("unsolicited", byteArrayOf(0, 16), "vi-VN")) }
            Thread.sleep(100)
            main {
                assertEquals(0, fixture.transcriptions)
                c.listenOnGlasses(); c.listenOnGlasses()
                assertEquals(1, fixture.session.starts)
                fixture.session.events.trySend(GlassVoiceEvent.Utterance("old", byteArrayOf(0, 16), "vi-VN"))
                fixture.session.events.trySend(GlassVoiceEvent.Utterance("session-1", byteArrayOf(0, 16), "vi-VN"))
            }
            Thread.sleep(100); idle(c)
            main {
                assertEquals(1, fixture.transcriptions); assertEquals(0, fixture.answers)
                assertEquals(VoicePhase.IDLE, c.voicePhase)
                assertTrue(fixture.session.stops > 0)
                assertTrue(c.actionState is ActionState.Success)
            }
        } finally { main { fixture.close() } }
    }
    @Test fun timedOutStartCannotLeaveTheAppBusy() {
        val fixture = Fixture(); val c = fixture.controller
        try {
            main { c.demoMode = true; c.connect(); fixture.session.startError = SocketTimeoutException("START_LISTENING timed out"); c.listenOnGlasses() }
            idle(c)
            main { assertTrue(c.actionState is ActionState.Error); assertEquals(VoicePhase.IDLE, c.voicePhase) }
        } finally { main { fixture.close() } }
    }
    @Test fun earlyLocalPhotoResultDownloadsAndSavesWithoutCapturingTwice() {
        val fixture = Fixture(); val c = fixture.controller
        try {
            main {
                c.demoMode = true; c.connect()
                fixture.session.early = GlassVoiceEvent.LocalCommand("session-1", "TAKE_PHOTO", "SUCCESS", PhotoReference("photo-1", 4, 320, 240))
                c.listenOnGlasses()
            }
            Thread.sleep(150); idle(c)
            main {
                assertEquals(1, fixture.session.downloads); assertEquals(0, fixture.session.captures)
                assertEquals(0, fixture.transcriptions); assertEquals(0, fixture.answers)
                assertNotNull(c.savedPhotoUri); assertFalse(c.canSavePhoto)
                val uri = c.savedPhotoUri
                c.savePhoto(); assertEquals(uri, c.savedPhotoUri)
                assertTrue(c.actionState is ActionState.Success); assertEquals(VoicePhase.IDLE, c.voicePhase)
            }
        } finally { main { fixture.close() } }
    }
    @Test fun waitingForAiBlocksWakeAndCancellationDropsLateResponse() {
        val fixture = Fixture("Vì sao bầu trời màu xanh?"); val c = fixture.controller
        val deferred = CompletableDeferred<String>(); fixture.blockedAnswer = deferred
        try {
            main { c.demoMode = true; c.connect(); c.listenOnGlasses(); fixture.session.events.trySend(GlassVoiceEvent.Utterance("session-1", byteArrayOf(0, 16), "vi-VN")) }
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (fixture.answers == 0 && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
            main {
                assertEquals(1, fixture.answers); assertEquals(VoicePhase.WAITING_AI, c.voicePhase)
                c.listenOnGlasses(); assertEquals(1, fixture.session.starts)
                c.cancelAction()
            }
            idle(c); deferred.complete("Late response must not play")
            Thread.sleep(100)
            main { assertEquals(0, fixture.plays); assertEquals(VoicePhase.IDLE, c.voicePhase); assertEquals(ActionState.Idle, c.actionState) }
        } finally { main { fixture.close() } }
    }

    @Test fun earlyDeviceErrorCompletesTheRightSession() {
        val fixture = Fixture(); val c = fixture.controller
        try {
            main {
                c.demoMode = true; c.connect()
                fixture.session.early = GlassVoiceEvent.DeviceError("MIC_NO_DATA", "session-1")
                c.listenOnGlasses()
            }
            Thread.sleep(150); idle(c)
            main {
                assertTrue(c.actionState is ActionState.Error)
                assertEquals(VoicePhase.IDLE, c.voicePhase)
                assertEquals(0, fixture.transcriptions)
            }
        } finally { main { fixture.close() } }
    }

    @Test fun twentySequentialManualSessionsReturnToIdleWithoutCloudOrDuplicateCommands() {
        val fixture = Fixture(); val c = fixture.controller
        try {
            main { c.demoMode = true; c.connect() }
            repeat(20) { index ->
                main {
                    c.listenOnGlasses()
                    fixture.session.events.trySend(GlassVoiceEvent.Utterance("session-${index + 1}", byteArrayOf(0, 16), "vi-VN"))
                }
                Thread.sleep(50); idle(c)
                main { assertEquals(VoicePhase.IDLE, c.voicePhase); assertTrue(c.actionState is ActionState.Success) }
            }
            assertEquals(20, fixture.transcriptions); assertEquals(0, fixture.answers)
        } finally { main { fixture.close() } }
    }

    @Test fun futureChipRepeatReusesAndroidCacheWithoutSttAiOrResynthesis() {
        val fixture = Fixture(); val c = fixture.controller
        try {
            main { c.demoMode = true; c.connect(); c.inputText = "Xin chào"; c.submitInput() }
            idle(c)
            val renders = fixture.renders
            main {
                c.listenOnGlasses()
                fixture.session.events.trySend(GlassVoiceEvent.LocalCommand("session-1", "REPEAT", "REPLAY_ON_ANDROID"))
            }
            Thread.sleep(100); idle(c)
            main {
                assertEquals(renders, fixture.renders); assertEquals(2, fixture.plays)
                assertEquals(0, fixture.transcriptions); assertEquals(0, fixture.answers)
                assertEquals(VoicePhase.IDLE, c.voicePhase)
            }
        } finally { main { fixture.close() } }
    }
}
