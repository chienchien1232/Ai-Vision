package com.example.ai_vision.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Explicit phone-mic demo; never used to claim that the glasses microphone works. */
class PhoneSpeechInput(private val context: Context) {
    suspend fun listen(languageTag: String): String = suspendCancellableCoroutine { continuation ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            continuation.resumeWithException(IllegalStateException("Phone mic demo requires Android 12 or newer"))
            return@suspendCancellableCoroutine
        }
        check(SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            "On-device speech recognition is unavailable on this phone"
        }
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission is missing"
        }
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onError(error: Int) {
                if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("Speech recognition failed ($error)"))
                }
                recognizer.destroy()
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (continuation.isActive) {
                    if (text.isNullOrBlank()) continuation.resumeWithException(IllegalStateException("No speech recognized"))
                    else continuation.resume(text)
                }
                recognizer.destroy()
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        recognizer.startListening(intent)
        continuation.invokeOnCancellation {
            recognizer.cancel()
            recognizer.destroy()
        }
    }
}
