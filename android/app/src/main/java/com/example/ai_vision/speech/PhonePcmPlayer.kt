package com.example.ai_vision.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

/** Demo playback only. stop() does not release a track still owned by the writer. */
class PhonePcmPlayer : PcmOutput {
    private val active = AtomicReference<AudioTrack?>()
    override suspend fun play(pcm: ByteArray) = withContext(Dispatchers.IO) {
        require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= GLASS_PCM_MAX_BYTES)
        val minimum = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Phone audio is unavailable" }
        val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum, 4096)).setTransferMode(AudioTrack.MODE_STREAM).build()
        if (!active.compareAndSet(null, track)) { track.release(); error("Phone playback is busy") }
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED)
            track.play()
            var offset = 0
            while (offset < pcm.size) {
                currentCoroutineContext().ensureActive()
                val count = track.write(pcm, offset, minOf(2048, pcm.size - offset), AudioTrack.WRITE_BLOCKING)
                currentCoroutineContext().ensureActive()
                check(count > 0) { "Phone PCM playback failed" }
                offset += count
            }
            withTimeout(pcm.size * 1000L / 32000 + 5000) {
                while (track.playbackHeadPosition < pcm.size / 2) delay(10)
            }
        } finally {
            active.compareAndSet(track, null)
            runCatching { track.stop() }
            track.release()
        }
    }
    override fun stop() { active.get()?.let { runCatching { it.pause(); it.flush() } } }
    override fun close() = stop()
}
