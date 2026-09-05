package com.rayban.ai.core.audio

import com.rayban.ai.domain.audio.SpeechError
import com.rayban.ai.domain.audio.SpeechEvent
import com.rayban.ai.domain.audio.SpeechToText
import com.rayban.ai.domain.wearable.ActiveWearableSession
import com.rayban.ai.domain.wearable.DeviceSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves [ActiveSpeechToText] forwards listening and events to the phone
 * recognizer or the glasses backend based on [ActiveWearableSession.micSource].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveSpeechToTextTest {

    @Test
    fun `defaults to phone mic`() = runTest {
        val session = TestSession()
        val phone = StubSpeechToText()
        val glasses = StubSpeechToText()
        val active = ActiveSpeechToText(session, phone, glasses)

        active.startListening()

        assertEquals(1, phone.startCount)
        assertEquals(0, glasses.startCount)
    }

    @Test
    fun `follows glasses mic selection`() = runTest {
        val session = TestSession()
        val phone = StubSpeechToText()
        val glasses = StubSpeechToText()
        val active = ActiveSpeechToText(session, phone, glasses)

        session.setMicSource(DeviceSource.GLASSES)
        active.startListening()
        active.cancel()

        assertEquals(0, phone.startCount)
        assertEquals(1, glasses.startCount)
        assertEquals(1, glasses.cancelCount)
    }

    @Test
    fun `events come from the active mic`() = runTest(UnconfinedTestDispatcher()) {
        val session = TestSession()
        val phone = StubSpeechToText()
        val glasses = StubSpeechToText()
        val active = ActiveSpeechToText(session, phone, glasses)

        session.setMicSource(DeviceSource.GLASSES)
        val collected = mutableListOf<SpeechEvent>()
        val job = launch {
            active.events.collect { collected += it }
        }
        active.startListening()
        glasses.emit(SpeechEvent.FinalResult("Chụp ảnh"))

        assertEquals(listOf(SpeechEvent.FinalResult("Chụp ảnh")), collected)
        job.cancel()
    }

    @Test
    fun `camera and mic sources switch independently`() {
        val session = TestSession()

        session.setCameraSource(DeviceSource.GLASSES)

        assertEquals(DeviceSource.GLASSES, session.cameraSource.value)
        assertEquals(DeviceSource.PHONE, session.micSource.value)
    }

    @Test
    fun `stop and final result stay with listening owner after source switch`() = runTest(UnconfinedTestDispatcher()) {
        val session = TestSession()
        val phone = StubSpeechToText()
        val glasses = StubSpeechToText()
        val active = ActiveSpeechToText(session, phone, glasses)
        val collected = mutableListOf<SpeechEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            active.events.collect { collected += it }
        }

        active.startListening()
        session.setMicSource(DeviceSource.GLASSES)
        active.stopListening()
        glasses.emit(SpeechEvent.FinalResult("wrong mic"))
        phone.emit(SpeechEvent.FinalResult("original mic"))
        phone.emit(SpeechEvent.PartialResult("late"))
        active.stopListening()

        assertEquals(1, phone.stopCount)
        assertEquals(0, glasses.stopCount)
        assertEquals(listOf(SpeechEvent.FinalResult("original mic")), collected)
    }

    @Test
    fun `cancel reaches original owner and signals cancellation even when backend is silent`() = runTest(UnconfinedTestDispatcher()) {
        val session = TestSession()
        val phone = StubSpeechToText()
        val glasses = StubSpeechToText()
        val active = ActiveSpeechToText(session, phone, glasses)
        val collected = mutableListOf<SpeechEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            active.events.collect { collected += it }
        }

        session.setMicSource(DeviceSource.GLASSES)
        active.startListening()
        session.setMicSource(DeviceSource.PHONE)
        active.cancel()
        glasses.emit(SpeechEvent.FinalResult("late"))
        active.cancel()

        assertEquals(1, glasses.cancelCount)
        assertEquals(0, phone.cancelCount)
        assertEquals(listOf(SpeechEvent.Cancelled), collected)
    }

    @Test
    fun `new listening cancels previous owner and receives synchronous startup events`() = runTest(UnconfinedTestDispatcher()) {
        val session = TestSession()
        val phone = StubSpeechToText()
        val glasses = StubSpeechToText(startEvent = SpeechEvent.Listening)
        val active = ActiveSpeechToText(session, phone, glasses)
        val collected = mutableListOf<SpeechEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            active.events.collect { collected += it }
        }

        active.startListening()
        session.setMicSource(DeviceSource.GLASSES)
        active.startListening()
        phone.emit(SpeechEvent.Cancelled)
        phone.emit(SpeechEvent.FinalResult("stale"))
        glasses.emit(SpeechEvent.FinalResult("new"))

        assertEquals(1, phone.cancelCount)
        assertEquals(listOf(SpeechEvent.Listening, SpeechEvent.FinalResult("new")), collected)
    }

    @Test
    fun `synchronous startup error is delivered and releases owner`() = runTest(UnconfinedTestDispatcher()) {
        val session = TestSession()
        val error = SpeechEvent.Error(SpeechError.NotAvailable)
        val phone = StubSpeechToText(startEvent = error)
        val active = ActiveSpeechToText(session, phone, StubSpeechToText())
        val collected = mutableListOf<SpeechEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            active.events.collect { collected += it }
        }

        active.startListening()
        active.stopListening()
        active.cancel()

        assertEquals(listOf(error), collected)
        assertEquals(0, phone.stopCount)
        assertEquals(0, phone.cancelCount)
    }

    private class TestSession : ActiveWearableSession {
        private val _camera = MutableStateFlow(DeviceSource.PHONE)
        override val cameraSource: StateFlow<DeviceSource> = _camera.asStateFlow()
        private val _mic = MutableStateFlow(DeviceSource.PHONE)
        override val micSource: StateFlow<DeviceSource> = _mic.asStateFlow()

        override fun setCameraSource(source: DeviceSource) {
            _camera.value = source
        }

        override fun setMicSource(source: DeviceSource) {
            _mic.value = source
        }
    }

    private class StubSpeechToText(private val startEvent: SpeechEvent? = null) : SpeechToText {
        private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 16)
        override val events: Flow<SpeechEvent> = _events.asSharedFlow()

        var startCount = 0
        var stopCount = 0
        var cancelCount = 0

        override fun startListening() {
            startCount++
            startEvent?.let(::emit)
        }

        override fun stopListening() {
            stopCount++
        }

        override fun cancel() {
            cancelCount++
        }

        fun emit(event: SpeechEvent) {
            _events.tryEmit(event)
        }
    }
}
