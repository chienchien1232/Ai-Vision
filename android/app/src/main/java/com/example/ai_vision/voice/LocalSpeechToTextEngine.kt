package com.example.ai_vision.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface SpeechToTextEngine {
    suspend fun transcribe(pcm: ByteArray, languageTag: String): String
}

internal fun pcm16ToFloat(pcm: ByteArray): FloatArray {
    require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= 16000 * 2 * 30)
    return FloatArray(pcm.size / 2) { index ->
        (((pcm[index * 2 + 1].toInt() shl 8) or (pcm[index * 2].toInt() and 255)).toShort()).toFloat() / 32768f
    }
}

/** Decodes glasses PCM on the phone. Never opens a microphone or an HTTP connection. */
class LocalSpeechToTextEngine(context: Context) : SpeechToTextEngine {
    private val assets = context.applicationContext.assets
    private val mutex = Mutex()

    override suspend fun transcribe(pcm: ByteArray, languageTag: String): String = mutex.withLock {
        withContext(Dispatchers.Default) {
            val language = when (languageTag) {
                "vi-VN" -> "vi"
                "en-US" -> "en"
                else -> error("Unsupported speech language")
            }
            val samples = pcm16ToFloat(pcm)
            currentCoroutineContext().ensureActive()
            val config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    encoder = "asr/$language/encoder_model.ort",
                    mergedDecoder = "asr/$language/decoder_model_merged.ort"
                ),
                tokens = "asr/$language/tokens.txt", numThreads = 2, provider = "cpu"
            ))
            // Scope native allocations to one utterance: cancelled sessions cannot
            // leave a model resident or publish a result to the next session.
            val recognizer = OfflineRecognizer(assetManager = assets, config = config)
            try {
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(samples, 16000)
                    recognizer.decode(stream)
                    currentCoroutineContext().ensureActive()
                    recognizer.getResult(stream).text.trim().takeIf { it.isNotBlank() }
                        ?: error("Không nghe rõ. Hãy nói lại.")
                } finally { stream.release() }
            } finally { recognizer.release() }
        }
    }
}
