package com.example.ai_vision

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ai_vision.voice.LocalSpeechToTextEngine
import com.example.ai_vision.speech.resampleToMono16k
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real native inference, not a mocked recognizer. Fixtures are pinned upstream model samples. */
@RunWith(AndroidJUnit4::class)
class LocalSpeechSmokeTest {
    @Test fun bundledVietnameseAndEnglishModelsDecodeSpeech() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val engine = LocalSpeechToTextEngine(instrumentation.targetContext)
        for ((language, tag) in listOf("vi" to "vi-VN", "en" to "en-US")) {
            val wav = instrumentation.context.assets.open("asr-smoke/$language.wav").use { it.readBytes() }
            val pcm = pcmFromWav(wav)
            val startedAt = SystemClock.elapsedRealtime()
            val transcript = engine.transcribe(pcm, tag)
            Log.i("ASR_SMOKE", "$tag decoded ${transcript.length} characters in ${SystemClock.elapsedRealtime() - startedAt} ms")
            assertTrue("$tag: no words decoded", transcript.trim().split(Regex("\\s+")).size >= 3)
        }
    }
    private fun pcmFromWav(wav: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        check(String(wav, 0, 4) == "RIFF" && String(wav, 8, 4) == "WAVE")
        var position = 12
        var formatValid = false
        var sampleRate = 0
        while (position + 8 <= wav.size) {
            val name = String(wav, position, 4)
            val size = buffer.getInt(position + 4)
            check(size >= 0 && position.toLong() + 8 + size <= wav.size)
            if (name == "fmt ") {
                check(size >= 16)
                sampleRate = buffer.getInt(position + 12)
                formatValid = buffer.getShort(position + 8).toInt() == 1 && buffer.getShort(position + 10).toInt() == 1 &&
                    sampleRate in 4000..96000 && buffer.getShort(position + 22).toInt() == 16
            }
            if (name == "data") {
                check(formatValid)
                return resampleToMono16k(wav.copyOfRange(position + 8, position + 8 + size), 1, sampleRate, 16)
            }
            position += 8 + size + size % 2
        }
        error("Missing PCM fixture")
    }
}
