package com.rayban.ai.core.audio

import com.rayban.ai.domain.audio.*
import com.rayban.ai.domain.model.MicrophoneCapabilities
import com.rayban.ai.domain.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudSpeechToTextTest {
    @Test fun `PCM from any microphone becomes WAV and one bilingual final result`() = runTest {
        for (words in listOf("Chụp ảnh", "Take a photo", "Chụp ảnh please")) {
            val mic = Mic()
            var calls = 0
            val transcriber = object : AudioTranscriber {
                override val isAvailable = true
                override suspend fun transcribe(wav: ByteArray): String {
                    calls++
                    assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
                    assertEquals("WAVE", String(wav.copyOfRange(8, 12)))
                    assertEquals(44 + 6400, wav.size)
                    return words
                }
            }
            val stt = CloudSpeechToText(mic, transcriber, backgroundScope, StandardTestDispatcher(testScheduler))
            val events = mutableListOf<SpeechEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { stt.events.toList(events) }
            stt.startListening()
            stt.startListening()
            runCurrent()
            assertEquals(1, mic.opens)
            assertEquals(1, calls)
            assertEquals(listOf(SpeechEvent.Listening, SpeechEvent.Transcribing, SpeechEvent.FinalResult(words)), events)
            assertTrue(mic.closed)
        }
    }

    @Test fun `silence never calls cloud`() = runTest {
        val mic = Mic(silent = true)
        val stt = CloudSpeechToText(mic, transcriber { fail("Silence uploaded"); "" }, backgroundScope)
        val events = mutableListOf<SpeechEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { stt.events.toList(events) }
        stt.startListening()
        runCurrent()
        assertEquals(SpeechEvent.FinalResult(""), events.last())
        assertTrue(mic.closed)
    }

    @Test fun `cancel discards non cooperative cloud result and allows new session`() = runTest {
        val response = CompletableDeferred<String>()
        val mic = Mic()
        val stt = CloudSpeechToText(mic, transcriber { withContext(NonCancellable) { response.await() } },
            backgroundScope, StandardTestDispatcher(testScheduler))
        val events = mutableListOf<SpeechEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { stt.events.toList(events) }
        stt.startListening()
        runCurrent()
        assertEquals(SpeechEvent.Transcribing, events.last())
        stt.cancel()
        runCurrent()
        response.complete("Chụp ảnh")
        runCurrent()
        assertFalse(events.any { it is SpeechEvent.FinalResult })
        stt.startListening()
        runCurrent()
        assertEquals(2, mic.opens)
        assertEquals(1, events.filterIsInstance<SpeechEvent.FinalResult>().size)
    }

    @Test fun `cancel during capture closes stream without uploading`() = runTest {
        val mic = Mic(wait = true)
        val stt = CloudSpeechToText(mic, transcriber { fail("Cancelled audio uploaded"); "" }, backgroundScope)
        stt.startListening()
        runCurrent()
        stt.cancel()
        runCurrent()
        assertTrue(mic.closed)
    }

    private fun transcriber(block: suspend () -> String) = object : AudioTranscriber {
        override val isAvailable = true
        override suspend fun transcribe(wav: ByteArray) = block()
    }

    private class Mic(val silent: Boolean = false, val wait: Boolean = false) : WearableMicrophone {
        override val capabilities = MutableStateFlow(MicrophoneCapabilities())
        var opens = 0
        var closed = false
        override fun openStream(request: AudioStreamRequest): AudioSession {
            opens++
            return object : AudioSession {
                override val chunks = flow {
                    if (wait) awaitCancellation()
                    emit(AudioChunk(ByteArray(6400) { if (silent) 0 else if (it % 2 == 0) 0 else 4 }, 16000, 0))
                }
                override fun stop() = Unit
                override fun close() { closed = true }
            }
        }
    }
}
