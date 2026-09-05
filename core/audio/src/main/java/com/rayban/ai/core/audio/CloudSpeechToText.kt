package com.rayban.ai.core.audio

import com.rayban.ai.domain.audio.AudioTranscriber
import com.rayban.ai.domain.audio.SpeechError
import com.rayban.ai.domain.audio.SpeechEvent
import com.rayban.ai.domain.audio.SpeechToText
import com.rayban.ai.domain.audio.TranscriptionException
import com.rayban.ai.domain.wearable.AudioSession
import com.rayban.ai.domain.wearable.AudioStreamRequest
import com.rayban.ai.domain.wearable.WearableMicrophone
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Public calls and event collection are main-dispatcher confined, like the system adapter. */
class CloudSpeechToText(
    private val microphone: WearableMicrophone,
    private val transcriber: AudioTranscriber,
    private val scope: CoroutineScope,
    private val transcriptionDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SpeechToText {
    private var generation = 0L
    private var job: Job? = null
    private var session: AudioSession? = null
    private var stopRequested = false
    private val exclusive = Mutex()
    private val pending = MutableSharedFlow<Pair<Long, SpeechEvent>>(extraBufferCapacity = 16)
    override val events = pending.transform { (id, event) -> if (id == generation) emit(event) }

    override fun startListening() {
        if (job?.isCompleted == false) return
        val id = ++generation
        stopRequested = false
        if (!transcriber.isAvailable) {
            pending.tryEmit(id to SpeechEvent.Error(SpeechError.MissingApiKey))
            return
        }
        job = scope.launch {
            exclusive.withLock {
                var audio: AudioSession? = null
                try {
                    audio = microphone.openStream(AudioStreamRequest(maxDurationMillis = 12_000))
                    session = audio
                    if (stopRequested) audio.stop()
                    pending.emit(id to SpeechEvent.Listening)
                    val pcm = ByteArrayOutputStream()
                    var speechBytes = 0
                    var quietBytes = 0
                    var heardSpeech = false
                    withTimeoutOrNull(12_000) {
                        audio.chunks.collect { chunk ->
                            if (chunk.sampleRate != 16_000 || chunk.data.size % 2 != 0) throw TranscriptionException(SpeechError.Audio)
                            val count = minOf(chunk.data.size, 384_000 - pcm.size())
                            pcm.write(chunk.data, 0, count)
                            var energy = 0.0
                            for (i in 0 until count step 2) {
                                val sample = ((chunk.data[i].toInt() and 255) or (chunk.data[i + 1].toInt() shl 8)).toShort().toInt()
                                energy += sample.toDouble() * sample
                            }
                            if (count > 0 && sqrt(energy / (count / 2)) >= 450) {
                                speechBytes += count
                                quietBytes = 0
                                if (speechBytes >= 6_400) heardSpeech = true
                            } else {
                                quietBytes += count
                                if (!heardSpeech) speechBytes = 0
                            }
                            if (pcm.size() >= 384_000 || (!heardSpeech && pcm.size() >= 128_000) ||
                                (heardSpeech && quietBytes >= 38_400)) audio.stop()
                        }
                    }
                    audio.close()
                    session = null
                    if (!heardSpeech) {
                        pending.emit(id to SpeechEvent.FinalResult(""))
                    } else {
                        pending.emit(id to SpeechEvent.Transcribing)
                        val transcript = withContext(transcriptionDispatcher) { transcriber.transcribe(toWav(pcm.toByteArray())) }
                        pending.emit(id to SpeechEvent.FinalResult(transcript))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val error = when (e) {
                        is SecurityException -> SpeechError.PermissionDenied
                        is TranscriptionException -> e.error
                        else -> SpeechError.Audio
                    }
                    pending.emit(id to SpeechEvent.Error(error))
                } finally {
                    audio?.close()
                    session = null
                }
            }
        }
    }

    override fun stopListening() {
        stopRequested = true
        session?.stop()
    }

    override fun cancel() {
        ++generation
        session?.stop()
        job?.cancel()
        job = null
        pending.tryEmit(generation to SpeechEvent.Cancelled)
    }

    private fun toWav(pcm: ByteArray): ByteArray = ByteBuffer.allocate(44 + pcm.size)
        .order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + pcm.size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1); putInt(16_000); putInt(32_000)
            putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(pcm.size); put(pcm)
        }.array()
}
