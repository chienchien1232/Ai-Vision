package com.example.ai_vision.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

internal data class PcmPlaybackStats(val inputFrames: Int, val bufferFrames: Int,
    val playedFrames: Int, val underruns: Int)

/** Demo playback: preload one finite PCM buffer; no streaming refill or automatic replay. */
class PhonePcmPlayer : PcmOutput {
    private class Playback(val track: AudioTrack, val stopped: AtomicBoolean = AtomicBoolean())
    private val active = AtomicReference<Playback?>()
    @Volatile internal var lastPlayback: PcmPlaybackStats? = null
        private set
    internal val isPlaying: Boolean get() = active.get() != null

    override suspend fun play(pcm: ByteArray): Unit = withContext(Dispatchers.IO) {
        require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= GLASS_PCM_MAX_BYTES)
        val minimum = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Phone audio is unavailable" }
        // Let the final spoken frames reach the output device before releasing the track.
        // Padding is silence; it never repeats source samples and is not put in the reply cache.
        val bufferBytes = maxOf(pcm.size + GLASS_PCM_SAMPLE_RATE * 2 / 10, (minimum + 1) / 2 * 2)
        val loaded = pcm.copyOf(bufferBytes)
        val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(bufferBytes).setTransferMode(AudioTrack.MODE_STATIC).build()
        val playback = Playback(track)
        if (!active.compareAndSet(null, playback)) { track.release(); error("Phone playback is busy") }
        try {
            lastPlayback = null
            // A new static AudioTrack is STATE_NO_STATIC_DATA until its first successful write.
            currentCoroutineContext().ensureActive()
            if (playback.stopped.get()) throw CancellationException("Phone playback stopped")
            check(track.write(loaded, 0, loaded.size, AudioTrack.WRITE_BLOCKING) == loaded.size) {
                "Phone PCM preload failed"
            }
            check(track.state == AudioTrack.STATE_INITIALIZED)
            check(track.setLoopPoints(0, 0, 0) == AudioTrack.SUCCESS) { "Cannot disable PCM looping" }
            currentCoroutineContext().ensureActive()
            if (playback.stopped.get()) throw CancellationException("Phone playback stopped")
            track.play()
            val expectedFrames = loaded.size / 2
            try {
                withTimeout(loaded.size * 1000L / 32000 + 5000) {
                    while (track.playbackHeadPosition < expectedFrames) {
                        if (playback.stopped.get()) throw CancellationException("Phone playback stopped")
                        delay(10)
                    }
                    currentCoroutineContext().ensureActive()
                    if (playback.stopped.get()) throw CancellationException("Phone playback stopped")
                }
            } catch (timeout: TimeoutCancellationException) {
                // Preserve cancellation by the caller, but report a stalled output with
                // enough information to distinguish it from STT/TTS or network failures.
                currentCoroutineContext().ensureActive()
                val message = "Phone PCM timed out: played=${track.playbackHeadPosition}/$expectedFrames, state=${track.playState}, underruns=${track.underrunCount}"
                Log.e("PCM_PLAYBACK", message)
                throw IllegalStateException(message, timeout)
            }
            val played = track.playbackHeadPosition
            check(played == expectedFrames) { "Phone PCM advanced beyond its one-shot buffer" }
            lastPlayback = PcmPlaybackStats(pcm.size / 2, expectedFrames, played, track.underrunCount)
            Log.i("PCM_PLAYBACK", "mode=static inputFrames=${pcm.size / 2} bufferFrames=$expectedFrames playedFrames=$played underruns=${track.underrunCount}")
        } finally {
            runCatching { track.stop() }
            track.release()
            active.compareAndSet(playback, null)
        }
    }
    override fun stop() {
        active.get()?.let { playback ->
            playback.stopped.set(true)
            runCatching { playback.track.pause() }
        }
    }
    override fun close() = stop()
}
