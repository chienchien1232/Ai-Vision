package com.example.ai_vision.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val GLASS_PCM_SAMPLE_RATE = 16000
internal const val GLASS_PCM_MAX_BYTES = GLASS_PCM_SAMPLE_RATE * 2 * 30

/** One-second, low-level tone for testing V2 TCP/I2S without TTS or backend. */
internal fun speakerTestPcm(): ByteArray {
    val frames = GLASS_PCM_SAMPLE_RATE
    val fadeFrames = GLASS_PCM_SAMPLE_RATE / 100 // 10 ms avoids start/end clicks
    return ByteArray(frames * 2).also { output ->
        for (frame in 0 until frames) {
            val fade = minOf(frame, frames - 1 - frame, fadeFrames).toDouble() / fadeFrames
            val sample = (10000.0 * fade * sin(2.0 * PI * 440.0 * frame / GLASS_PCM_SAMPLE_RATE)).toInt()
            output[frame * 2] = (sample and 0xff).toByte()
            output[frame * 2 + 1] = ((sample shr 8) and 0xff).toByte()
        }
    }
}

/**
 * Resamples raw PCM (8/16-bit, mono/stereo) to mono 16 kHz 16-bit little-endian
 * for the glasses speaker. Pure function so it can be unit tested without TTS.
 */
internal fun resampleToMono16k(input: ByteArray, channels: Int, sampleRate: Int, bitsPerSample: Int): ByteArray {
    require(channels in 1..2 && sampleRate in 4000..96000 && (bitsPerSample == 8 || bitsPerSample == 16)) {
        "Unsupported TTS audio format"
    }
    require(input.isNotEmpty())
    val frameSize = channels * bitsPerSample / 8
    require(input.size % frameSize == 0) { "Truncated TTS audio" }
    val frames = input.size / frameSize
    val mono = FloatArray(frames)
    var offset = 0
    for (frame in 0 until frames) {
        var sum = 0f
        repeat(channels) {
            sum += when (bitsPerSample) {
                8 -> ((input[offset].toInt() and 0xff) - 128) / 128f
                else -> {
                    val low = input[offset].toInt() and 0xff
                    val high = input[offset + 1].toInt()
                    ((high shl 8) or low).toShort().toFloat() / 32768f
                }
            }
            offset += bitsPerSample / 8
        }
        mono[frame] = sum / channels
    }
    val outFrames = ((frames.toLong() * GLASS_PCM_SAMPLE_RATE) / sampleRate).toInt()
    if (outFrames < 1 || outFrames.toLong() * 2 > GLASS_PCM_MAX_BYTES) {
        throw IOException("Answer audio is longer than 30 seconds")
    }
    val output = ByteArray(outFrames * 2)
    for (index in 0 until outFrames) {
        val position = index.toFloat() * frames / outFrames
        val first = position.toInt().coerceIn(0, frames - 1)
        val second = (first + 1).coerceIn(0, frames - 1)
        val fraction = (position - first).coerceIn(0f, 1f)
        val sample = mono[first] * (1 - fraction) + mono[second] * fraction
        val pcm = (sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        output[index * 2] = (pcm.toInt() and 0xff).toByte()
        output[index * 2 + 1] = ((pcm.toInt() shr 8) and 0xff).toByte()
    }
    return output
}

/** Phone audio output; the same TTS engine also renders answer PCM for the glasses speaker. */
class PhoneSpeaker(context: Context) : SpeechRenderer {
    private val appContext = context.applicationContext
    private val ready = CompletableDeferred<Unit>()
    private val tts = TextToSpeech(appContext) { status ->
        if (status == TextToSpeech.SUCCESS) ready.complete(Unit)
        else ready.completeExceptionally(IllegalStateException("Text-to-speech is unavailable"))
    }

    suspend fun speak(text: String, languageTag: String, sessionId: String) {
        withTimeout(5_000) { ready.await() }
        selectOfflineVoice(languageTag)
        val done = CompletableDeferred<Unit>()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == sessionId) done.complete(Unit) }
            override fun onError(utteranceId: String?) { if (utteranceId == sessionId) done.completeExceptionally(IOException("Text-to-speech failed")) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == sessionId) done.cancel() }
        })
        check(tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, sessionId) == TextToSpeech.SUCCESS) {
            "Text-to-speech failed"
        }
        try { withTimeout(35_000) { done.await() } } finally { tts.stop() }
    }

    private fun selectOfflineVoice(languageTag: String) {
        val locale = Locale.forLanguageTag(languageTag)
        tts.setLanguage(locale)
        val voice = tts.voices.orEmpty().filter { !it.isNetworkConnectionRequired && it.locale.language == locale.language &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
            .sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale.country == locale.country }.thenByDescending { it.quality })
            .firstOrNull() ?: error("Install an offline $languageTag TTS voice in phone Settings > Text-to-speech.")
        check(tts.setVoice(voice) == TextToSpeech.SUCCESS) { "Offline TTS voice is unavailable" }
    }

    /**
     * Renders answer text to PCM_S16LE mono 16 kHz bytes for the glasses speaker.
     * Sequential calls only: the utterance listener is replaced on every call.
     */
    override suspend fun renderPcm16k(text: String, languageTag: String): ByteArray {
        require(text.isNotBlank() && text.length <= 4000) { "Answer text must contain 1..4000 characters" }
        withTimeout(5_000) { ready.await() }
        selectOfflineVoice(languageTag)
        val file = File(appContext.cacheDir, "answer-${UUID.randomUUID()}.wav")
        val renderingId = UUID.randomUUID().toString()
        try {
            val done = CompletableDeferred<Unit>()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == renderingId) done.complete(Unit)
                }
                override fun onError(utteranceId: String?) {
                    if (utteranceId == renderingId) done.completeExceptionally(IOException("Text-to-speech synthesis failed"))
                }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == renderingId) done.cancel() }
            })
            check(tts.synthesizeToFile(text, null, file, renderingId) == TextToSpeech.SUCCESS) {
                "Text-to-speech synthesis failed"
            }
            withTimeout(30_000) { done.await() }
            return withContext(Dispatchers.Default) { wavToPcm16k(file) }
        } finally {
            tts.stop()
            file.delete()
        }
    }

    private fun wavToPcm16k(file: File): ByteArray {
        val bytes = file.readBytes()
        require(bytes.size >= 44 && bytes[0] == 'R'.code.toByte() && bytes[8] == 'W'.code.toByte()) {
            "Invalid TTS audio"
        }
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var data: ByteArray? = null
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = ((bytes[offset + 7].toInt() and 0xff) shl 24) or
                ((bytes[offset + 6].toInt() and 0xff) shl 16) or
                ((bytes[offset + 5].toInt() and 0xff) shl 8) or
                (bytes[offset + 4].toInt() and 0xff)
            require(size >= 0 && offset + 8 + size <= bytes.size) { "Invalid TTS audio" }
            when (id) {
                "fmt " -> {
                    require(size >= 16) { "Invalid TTS audio" }
                    val format = (bytes[offset + 8].toInt() and 0xff) or
                        ((bytes[offset + 9].toInt() and 0xff) shl 8)
                    require(format == 1) { "Unsupported TTS audio format" }
                    channels = (bytes[offset + 10].toInt() and 0xff) or
                        ((bytes[offset + 11].toInt() and 0xff) shl 8)
                    sampleRate = ((bytes[offset + 15].toInt() and 0xff) shl 24) or
                        ((bytes[offset + 14].toInt() and 0xff) shl 16) or
                        ((bytes[offset + 13].toInt() and 0xff) shl 8) or
                        (bytes[offset + 12].toInt() and 0xff)
                    bitsPerSample = (bytes[offset + 22].toInt() and 0xff) or
                        ((bytes[offset + 23].toInt() and 0xff) shl 8)
                }
                "data" -> data = bytes.copyOfRange(offset + 8, offset + 8 + size)
            }
            offset += 8 + size + (size % 2)
        }
        val audio = data ?: throw IOException("TTS audio has no sound data")
        return resampleToMono16k(audio, channels, sampleRate, bitsPerSample)
    }

    override fun stop() { tts.stop() }
    override fun close() { tts.shutdown() }
}
