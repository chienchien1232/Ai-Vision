package com.rayban.ai.core.audio

import com.rayban.ai.domain.audio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface SpeechAudioPlayer { suspend fun play(audio: SpeechAudio) }

/** Calls and event collection are main-thread confined. */
class CloudTextSpeaker(
    private val synthesizer: SpeechSynthesizer,
    private val player: SpeechAudioPlayer,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TextSpeaker {
    private var generation = 0L
    private var job: Job? = null
    private val exclusive = Mutex()
    private val pending = MutableSharedFlow<Pair<Long, SpeakerEvent>>(extraBufferCapacity = 16)
    override val events = pending.transform { (id, event) -> if (id == generation) emit(event) }
    override fun speak(text: String) {
        stop()
        if (text.isBlank()) return
        val id = generation
        job = scope.launch {
            exclusive.withLock {
                try {
                    for (part in speechChunks(text)) {
                        pending.emit(id to SpeakerEvent.Synthesizing)
                        val audio = withTimeout(60_000) { withContext(dispatcher) { synthesizer.synthesize(part) } }
                        ensureActive()
                        audio.validate()
                        pending.emit(id to SpeakerEvent.Started)
                        player.play(audio)
                    }
                    pending.emit(id to SpeakerEvent.Finished)
                } catch (e: TimeoutCancellationException) {
                    pending.tryEmit(id to SpeakerEvent.SynthesisFailed(SpeechError.Timeout))
                } catch (e: CancellationException) {
                    pending.tryEmit(id to SpeakerEvent.Finished)
                    throw e
                } catch (e: Exception) {
                    pending.emit(id to SpeakerEvent.SynthesisFailed((e as? TranscriptionException)?.error ?: SpeechError.Audio))
                }
            }
        }
    }
    override fun stop() { generation++; job?.cancel(); job = null }
    override fun release() = stop()
}

internal fun speechChunks(text: String): List<String> {
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + 1000, text.length)
        if (end < text.length) {
            val boundary = (end - 1 downTo start).firstOrNull { text[it] in ".!?。！？\n" }
                ?: (end - 1 downTo start).firstOrNull { text[it].isWhitespace() }
            if (boundary != null) end = boundary + 1
            if (end > start && Character.isHighSurrogate(text[end - 1])) end--
        }
        val part = text.substring(start, end)
        if (part.isNotBlank()) chunks.add(part)
        start = end
    }
    return chunks
}
