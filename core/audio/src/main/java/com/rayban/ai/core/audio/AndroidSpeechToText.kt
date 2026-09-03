package com.rayban.ai.core.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.rayban.ai.domain.audio.SpeechError
import com.rayban.ai.domain.audio.SpeechEvent
import com.rayban.ai.domain.audio.SpeechToText
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@Singleton
class AndroidSpeechToText @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechToText {

    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = EVENT_BUFFER)
    override val events: Flow<SpeechEvent> = _events.asSharedFlow()

    private var recognizer: SpeechRecognizer? = null

    override fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "[Speech] Recognition not available on this device")
            _events.tryEmit(SpeechEvent.Error(SpeechError.NotAvailable))
            return
        }
        destroyRecognizer()
        val newRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = newRecognizer
        newRecognizer.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        _events.tryEmit(SpeechEvent.Listening)
        newRecognizer.startListening(intent)
        Log.d(TAG, "[Speech] Listening started")
    }

    override fun stopListening() {
        recognizer?.stopListening()
    }

    override fun cancel() {
        destroyRecognizer()
        _events.tryEmit(SpeechEvent.Cancelled)
        Log.d(TAG, "[Speech] Cancelled")
    }

    fun release() {
        destroyRecognizer()
    }

    private fun destroyRecognizer() {
        recognizer?.destroy()
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _events.tryEmit(SpeechEvent.Ready)
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            Log.w(TAG, "[Speech] Recognizer error code=$error")
            destroyRecognizer()
            _events.tryEmit(SpeechEvent.Error(error.toSpeechError()))
        }

        override fun onResults(results: Bundle?) {
            destroyRecognizer()
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            _events.tryEmit(SpeechEvent.FinalResult(text))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrEmpty()) {
                _events.tryEmit(SpeechEvent.PartialResult(text))
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun Int.toSpeechError(): SpeechError = when (this) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_NETWORK -> SpeechError.Network
        SpeechRecognizer.ERROR_AUDIO -> SpeechError.Audio
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.PermissionDenied
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechError.NoMatch
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> SpeechError.Busy
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> SpeechError.NotAvailable
        else -> SpeechError.Unknown
    }

    private companion object {
        const val TAG = "AndroidSpeechToText"
        const val EVENT_BUFFER = 32
    }
}