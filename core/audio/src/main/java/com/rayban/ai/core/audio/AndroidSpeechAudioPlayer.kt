package com.rayban.ai.core.audio

import android.content.*
import android.media.*
import android.os.Handler
import android.os.Looper
import com.rayban.ai.domain.audio.*
import kotlinx.coroutines.*

class AndroidSpeechAudioPlayer(private val context: Context) : SpeechAudioPlayer {
    override suspend fun play(audio: SpeechAudio) = withContext(Dispatchers.IO) {
        audio.validate()
        val owner = currentCoroutineContext()[Job]!!
        val manager = context.getSystemService(AudioManager::class.java)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
                if (change < 0) owner.cancel()
            }, Handler(Looper.getMainLooper())).build()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { owner.cancel() }
        }
        var registered = false
        var track: AudioTrack? = null
        try {
            if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) throw TranscriptionException(SpeechError.Audio)
            androidx.core.content.ContextCompat.registerReceiver(context, receiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            val minimum = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0)
            val output = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(AudioFormat.Builder()
                .setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum, 4800)).setTransferMode(AudioTrack.MODE_STREAM).build()
            track = output
            check(output.state == AudioTrack.STATE_INITIALIZED)
            output.play()
            withTimeout(audio.pcm.size.toLong() * 1000 / 48000 + 5000) {
                var offset = 0
                while (offset < audio.pcm.size) {
                    ensureActive()
                    val written = output.write(audio.pcm, offset, minOf(4800, audio.pcm.size - offset), AudioTrack.WRITE_NON_BLOCKING)
                    check(written >= 0)
                    offset += written
                    delay(10)
                }
                while ((output.playbackHeadPosition.toLong() and 0xffffffffL) < audio.pcm.size / 2) delay(10)
            }
        } finally {
            try { track?.pause(); track?.flush() } finally {
                track?.release()
                if (registered) context.unregisterReceiver(receiver)
                manager.abandonAudioFocusRequest(focus)
            }
        }
    }
}
