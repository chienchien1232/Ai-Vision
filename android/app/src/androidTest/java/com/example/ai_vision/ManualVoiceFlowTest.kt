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
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
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
        var startHook: (suspend () -> String)? = null
        var captureHook: (suspend () -> CapturedImage)? = null
        var early: GlassVoiceEvent? = null
        override suspend fun startListening(languageTag: String, wakeWord: String, diagnostic: Boolean): String {
            assertEquals("", wakeWord); starts++
            startError?.let { throw it }
            startHook?.let { return it() }
            early?.let { events.send(it); delay(50) }
            return "session-$starts"
        }
        override suspend fun stopListening() { stops++ }
        override suspend fun cancelActive() { stops++ }
        override suspend fun ping() = "PONG"
        override suspend fun getStatus() = GlassStatus(true)
        override suspend fun capture(): CapturedImage { captures++; return captureHook?.invoke() ?: FakeGlassSession().capture() }
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
        var session = Session()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var transcriptions = 0; var answers = 0; var renders = 0; var plays = 0
        var blockedAnswer: CompletableDeferred<String>? = null
        var transcriptionHook: (suspend () -> String)? = null
        var audioStops = 0
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
                override fun stop() { audioStops++ }; override fun close() {}
            }, demoAiClient = ai,
            localSpeech = object : SpeechToTextEngine {
                override suspend fun transcribe(pcm: ByteArray, languageTag: String): String { transcriptions++; return transcriptionHook?.invoke() ?: transcript }
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

    @Test fun diagnosticMicCleansUpAndAllowsAnotherManualWake() {
        val fixture = Fixture(); val c = fixture.controller
        try {
            main {
                c.demoMode = true; c.connect(); c.testMicrophone()
                fixture.session.events.trySend(GlassVoiceEvent.Utterance("session-1", byteArrayOf(0, 16), "vi-VN"))
            }
            Thread.sleep(100); idle(c)
            main {
                assertEquals(VoicePhase.IDLE, c.voicePhase)
                assertTrue(c.actionState is ActionState.Success)
                assertEquals(0, fixture.transcriptions); assertEquals(0, fixture.answers)
                assertEquals(1, fixture.session.stops)
                c.listenOnGlasses(); assertEquals(2, fixture.session.starts)
                c.cancelAction()
            }
            idle(c)
        } finally { main { fixture.close() } }
    }

    @Test fun cancelledStartIgnoresLateAcknowledgmentAndCanStartAgain() {
        val fixture = Fixture(); val c = fixture.controller
        var acknowledgment: Continuation<String>? = null
        try {
            main {
                c.demoMode = true; c.connect()
                fixture.session.startHook = { suspendCoroutine { acknowledgment = it } }
                c.listenOnGlasses(); assertNotNull(acknowledgment)
                c.cancelAction()
                acknowledgment!!.resume("session-1")
            }
            idle(c)
            main {
                assertEquals(ActionState.Idle, c.actionState); assertEquals(VoicePhase.IDLE, c.voicePhase)
                assertTrue(c.deviceState is DeviceState.Connected)
                fixture.session.startHook = null
                c.listenOnGlasses(); assertEquals(2, fixture.session.starts)
                c.cancelAction()
            }
            idle(c)
        } finally { main { fixture.close() } }
    }

    @Test fun cancelledCaptureDoesNotPublishOrSaveLatePhotoBeforeNextLocalAnswer() {
        val fixture = Fixture(); val c = fixture.controller
        var result: Continuation<CapturedImage>? = null
        try {
            main {
                c.demoMode = true; c.connect()
                // A non-cooperative callback can still return after its coroutine is cancelled.
                fixture.session.captureHook = { suspendCoroutine { result = it } }
                c.capture(); assertNotNull(result)
                c.cancelAction()
                result!!.resume(CapturedImage(byteArrayOf(1, 2), 1, 1))
            }
            idle(c)
            main {
                assertNull(c.image); assertNull(c.savedPhotoUri); assertFalse(c.canReplayAudio)
                assertEquals(0, fixture.plays)
                c.inputText = "Hai cộng ba"; c.submitInput()
            }
            idle(c)
            main { assertEquals("5", c.answerText); assertEquals(1, fixture.plays); assertEquals(0, fixture.answers) }
        } finally { main { fixture.close() } }
    }

    @Test fun stopTextCancelsBusyActionAndOfflineCommandsNeverUseAi() {
        val fixture = Fixture(); val c = fixture.controller
        try {
            main { c.demoMode = true; c.cloudEnabled = false; c.backendUrl = ""; c.backendToken = ""; c.connect() }
            listOf("Chụp ảnh", "Bây giờ là mấy giờ", "Hai cộng ba", "Phát lại").forEach { text ->
                main { c.inputText = text; c.submitInput() }; idle(c)
                main { assertTrue(text, c.actionState is ActionState.Success) }
            }
            main { assertEquals(1, fixture.session.captures); assertEquals(0, fixture.answers) }

            val wait = CompletableDeferred<String>(); fixture.blockedAnswer = wait
            main {
                c.inputText = "Vì sao bầu trời màu xanh?"; c.submitInput()
                assertTrue(c.actionState is ActionState.Running)
                c.inputText = "Dừng"; c.submitInput()
            }
            idle(c)
            main { assertEquals(ActionState.Idle, c.actionState) }
        } finally { main { fixture.close() } }
    }

    @Test fun disconnectDuringSttDropsLateTranscriptAfterReconnect() {
        val fixture = Fixture(); val c = fixture.controller
        var transcript: Continuation<String>? = null
        try {
            main {
                fixture.transcriptionHook = { suspendCoroutine { transcript = it } }
                c.demoMode = true; c.connect(); c.listenOnGlasses()
                fixture.session.events.trySend(GlassVoiceEvent.Utterance("session-1", byteArrayOf(0, 16), "vi-VN"))
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (transcript == null && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
            main {
                assertNotNull(transcript)
                c.disconnect()
                fixture.session = Session(); fixture.transcriptionHook = null
                c.connect(); c.inputText = "Hai cộng ba"; c.submitInput()
            }
            idle(c)
            main { transcript!!.resume("Chụp ảnh") }
            Thread.sleep(100)
            main {
                assertEquals("5", c.answerText); assertEquals(0, fixture.session.captures)
                assertEquals(VoicePhase.IDLE, c.voicePhase); assertEquals(1, fixture.plays)
                assertTrue(c.deviceState is DeviceState.Connected)
            }
        } finally { main { fixture.close() } }
    }

    @Test fun transportFailureStopsAudioAndReconnectDoesNotResendOldAiRequest() {
        val fixture = Fixture("Vì sao bầu trời màu xanh?"); val c = fixture.controller
        val result = CompletableDeferred<String>(); fixture.blockedAnswer = result
        try {
            main { c.demoMode = true; c.connect(); c.listenOnGlasses(); fixture.session.events.trySend(GlassVoiceEvent.Utterance("session-1", byteArrayOf(0, 16), "vi-VN")) }
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (fixture.answers == 0 && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
            main { assertEquals(1, fixture.answers); fixture.session.events.trySend(GlassVoiceEvent.Failure("Demo link lost")) }
            Thread.sleep(100); idle(c)
            main {
                assertTrue(c.deviceState is DeviceState.Error)
                assertTrue(fixture.audioStops > 0)
                assertEquals(VoicePhase.IDLE, c.voicePhase)
                fixture.session = Session(); c.connect()
                c.inputText = "Hai cộng ba"; c.submitInput()
                result.complete("Old AI reply must not play")
            }
            idle(c)
            main { assertEquals("5", c.answerText); assertEquals(1, fixture.answers); assertEquals(1, fixture.plays) }
        } finally { main { fixture.close() } }
    }
}
