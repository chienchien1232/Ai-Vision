package com.rayban.ai.core.audio

import com.rayban.ai.domain.audio.SpeakerEvent
import com.rayban.ai.domain.audio.SpeechAudio
import com.rayban.ai.domain.audio.SpeechError
import com.rayban.ai.domain.audio.SpeechSynthesizer
import com.rayban.ai.domain.audio.TranscriptionException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudTextSpeakerTest {

    @Test
    fun `speak synthesizes then plays exactly once`() = runTest {
        val synth = FakeSynthesizer()
        val player = FakePlayer()
        val speaker = CloudTextSpeaker(synth, player, backgroundScope, StandardTestDispatcher(testScheduler))
        val events = mutableListOf<SpeakerEvent>()
        backgroundScope.launch { speaker.events.toList(events) }

        speaker.speak("Xin chào")
        runCurrent()

        assertEquals(listOf("Xin chào"), synth.requests)
        assertEquals(1, player.plays)
        assertEquals(
            listOf(SpeakerEvent.Synthesizing, SpeakerEvent.Started, SpeakerEvent.Finished),
            events,
        )
    }

    @Test
    fun `long text is split at sentence boundary without losing content`() = runTest {
        val text = "a".repeat(999) + ". " + "b".repeat(50)
        val synth = FakeSynthesizer()
        val player = FakePlayer()
        val speaker = CloudTextSpeaker(synth, player, backgroundScope, StandardTestDispatcher(testScheduler))
        val events = mutableListOf<SpeakerEvent>()
        backgroundScope.launch { speaker.events.toList(events) }

        speaker.speak(text)
        runCurrent()

        assertEquals(2, synth.requests.size)
        assertEquals(1000, synth.requests[0].length)
        assertEquals(text, synth.requests.joinToString(""))
        assertEquals(2, player.plays)
        assertEquals(
            listOf(
                SpeakerEvent.Synthesizing, SpeakerEvent.Started,
                SpeakerEvent.Synthesizing, SpeakerEvent.Started,
                SpeakerEvent.Finished,
            ),
            events,
        )
    }

    @Test
    fun `stop during synthesis discards late audio and allows a new session`() = runTest {
        val gate = CompletableDeferred<SpeechAudio>()
        val synth = FakeSynthesizer { gate.await() }
        val player = FakePlayer()
        val speaker = CloudTextSpeaker(synth, player, backgroundScope, StandardTestDispatcher(testScheduler))
        val events = mutableListOf<SpeakerEvent>()
        backgroundScope.launch { speaker.events.toList(events) }

        speaker.speak("Xin chào")
        runCurrent()
        speaker.stop()
        runCurrent()
        gate.complete(SpeechAudio(ByteArray(4800)))
        runCurrent()

        assertEquals(0, player.plays)
        assertTrue(events.none { it is SpeakerEvent.Started || it is SpeakerEvent.Finished })

        val second = FakeSynthesizer()
        val speaker2 = CloudTextSpeaker(second, player, backgroundScope, StandardTestDispatcher(testScheduler))
        val events2 = mutableListOf<SpeakerEvent>()
        backgroundScope.launch { speaker2.events.toList(events2) }
        speaker2.speak("Chào")
        runCurrent()

        assertEquals(listOf("Chào"), second.requests)
        assertEquals(1, player.plays)
        assertEquals(
            listOf(SpeakerEvent.Synthesizing, SpeakerEvent.Started, SpeakerEvent.Finished),
            events2,
        )
    }

    @Test
    fun `synthesis failure maps provider error`() = runTest {
        val speaker = CloudTextSpeaker(
            FakeSynthesizer { throw TranscriptionException(SpeechError.RateLimited) },
            FakePlayer(),
            backgroundScope,
            StandardTestDispatcher(testScheduler),
        )
        val events = mutableListOf<SpeakerEvent>()
        backgroundScope.launch { speaker.events.toList(events) }

        speaker.speak("Xin chào")
        runCurrent()

        assertEquals(
            listOf(SpeakerEvent.Synthesizing, SpeakerEvent.SynthesisFailed(SpeechError.RateLimited)),
            events,
        )
    }

    @Test
    fun `invalid audio is rejected before playback`() = runTest {
        val player = FakePlayer()
        val speaker = CloudTextSpeaker(
            FakeSynthesizer { SpeechAudio(ByteArray(0)) },
            player,
            backgroundScope,
            StandardTestDispatcher(testScheduler),
        )
        val events = mutableListOf<SpeakerEvent>()
        backgroundScope.launch { speaker.events.toList(events) }

        speaker.speak("Xin chào")
        runCurrent()

        assertEquals(0, player.plays)
        assertEquals(
            listOf(SpeakerEvent.Synthesizing, SpeakerEvent.SynthesisFailed(SpeechError.Audio)),
            events,
        )
    }

    private class FakeSynthesizer(
        private val block: suspend (String) -> SpeechAudio = { SpeechAudio(ByteArray(4800)) },
    ) : SpeechSynthesizer {
        val requests = mutableListOf<String>()
        override suspend fun synthesize(text: String): SpeechAudio {
            requests += text
            return block(text)
        }
    }

    private class FakePlayer : SpeechAudioPlayer {
        var plays = 0
        override suspend fun play(audio: SpeechAudio) {
            plays++
        }
    }
}
