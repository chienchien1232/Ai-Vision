package com.rayban.ai.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.rayban.ai.domain.model.MicrophoneCapabilities
import com.rayban.ai.domain.wearable.AudioChunk
import com.rayban.ai.domain.wearable.AudioSession
import com.rayban.ai.domain.wearable.AudioStreamRequest
import com.rayban.ai.domain.wearable.WearableMicrophone
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

@Singleton
class PhoneWearableMicrophone @Inject constructor() : WearableMicrophone {
    override val capabilities = MutableStateFlow(MicrophoneCapabilities())
    private val opened = AtomicBoolean(false)

    override fun openStream(request: AudioStreamRequest): AudioSession {
        require(request.sampleRate == 16_000 && request.channels == 1)
        check(opened.compareAndSet(false, true)) { "Microphone already open" }
        return object : AudioSession {
            private val stopped = AtomicBoolean(false)
            private val collected = AtomicBoolean(false)

            @SuppressLint("MissingPermission") // Permission failures are mapped by CloudSpeechToText.
            override val chunks = flow {
                check(collected.compareAndSet(false, true))
                var recorder: AudioRecord? = null
                try {
                    if (stopped.get()) return@flow
                    val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    check(minimum > 0)
                    val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 6_400))
                    recorder = audio
                    check(audio.state == AudioRecord.STATE_INITIALIZED)
                    audio.startRecording()
                    check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    val buffer = ByteArray(3_200)
                    var sequence = 0L
                    var bytes = 0
                    val limit = request.maxDurationMillis.coerceIn(1, 12_000).toInt() * 32
                    while (!stopped.get() && bytes < limit) {
                        val count = audio.read(buffer, 0, minOf(buffer.size, limit - bytes), AudioRecord.READ_NON_BLOCKING)
                        check(count >= 0) { "Microphone read failed" }
                        if (count > 0) {
                            emit(AudioChunk(buffer.copyOf(count), 16_000, sequence++))
                            bytes += count
                        }
                        delay(10)
                    }
                } finally {
                    try {
                        recorder?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() }
                    } finally {
                        recorder?.release()
                        opened.set(false)
                    }
                }
            }.flowOn(Dispatchers.IO)

            override fun stop() { stopped.set(true) }
            override fun close() {
                stop()
                if (collected.compareAndSet(false, true)) opened.set(false)
            }
        }
    }
}
