package com.example.ai_vision.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Foreground Demo input only: PCM has the same format as the glasses microphone. */
class PhonePcmRecorder(private val context: Context) {
    suspend fun record(): ByteArray = withContext(Dispatchers.IO) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Cho phép quyền micro để dùng WAKE AI trong Demo."
        }
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Microphone PCM 16 kHz is unavailable" }
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum, 6400)).build()
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Cannot initialize microphone" }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Cannot start microphone" }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(640) // 20 ms; non-blocking reads keep cancellation responsive.
            val deadline = android.os.SystemClock.elapsedRealtime() + 8000
            while (android.os.SystemClock.elapsedRealtime() < deadline && output.size() < 16000 * 2 * 8) {
                currentCoroutineContext().ensureActive()
                val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                check(count >= 0 && count % 2 == 0) { "Microphone read failed: $count" }
                if (count == 0) { delay(10); continue }
                output.write(buffer, 0, count)
            }
            currentCoroutineContext().ensureActive()
            check(output.size() > 0) { "Microphone returned no PCM" }
            output.toByteArray()
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }
}
