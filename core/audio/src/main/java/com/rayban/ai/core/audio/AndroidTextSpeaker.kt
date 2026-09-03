package com.rayban.ai.core.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.rayban.ai.domain.audio.SpeakerEvent
import com.rayban.ai.domain.audio.TextSpeaker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@Singleton
class AndroidTextSpeaker @Inject constructor(
    @ApplicationContext private val context: Context,
) : TextSpeaker {

    private val _events = MutableSharedFlow<SpeakerEvent>(extraBufferCapacity = EVENT_BUFFER)
    override val events: Flow<SpeakerEvent> = _events.asSharedFlow()

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null

    override fun speak(text: String) {
        if (text.isBlank()) return
        val engine = obtain()
        if (ready) {
            doSpeak(engine, text)
        } else {
            pendingText = text
        }
    }

    override fun stop() {
        pendingText = null
        tts?.stop()
    }

    override fun release() {
        tts?.shutdown()
        tts = null
        ready = false
        pendingText = null
    }

    private fun obtain(): TextToSpeech {
        tts?.let { return it }
        val engine = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.US
                _events.tryEmit(SpeakerEvent.Ready)
                Log.d(TAG, "[TTS] Ready")
                pendingText?.let { pending ->
                    pendingText = null
                    doSpeak(requireNotNull(tts), pending)
                }
            } else {
                Log.e(TAG, "[TTS] Init failed status=$status")
                _events.tryEmit(SpeakerEvent.Error("Text-to-speech init failed"))
            }
        }
        tts = engine
        return engine
    }

    private fun doSpeak(engine: TextToSpeech, text: String) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _events.tryEmit(SpeakerEvent.Started)
            }

            override fun onDone(utteranceId: String?) {
                _events.tryEmit(SpeakerEvent.Finished)
                Log.d(TAG, "[TTS] Finished")
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _events.tryEmit(SpeakerEvent.Error("Text-to-speech error"))
                Log.e(TAG, "[TTS] Error")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                _events.tryEmit(SpeakerEvent.Error("Text-to-speech error $errorCode"))
                Log.e(TAG, "[TTS] Error code=$errorCode")
            }
        })
        val result = engine.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            UTTERANCE_ID,
        )
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "[TTS] speak() failed result=$result")
            _events.tryEmit(SpeakerEvent.Error("Text-to-speech failed to start"))
        }
    }

    private companion object {
        const val TAG = "AndroidTextSpeaker"
        const val EVENT_BUFFER = 32
        const val UTTERANCE_ID = "assistant_answer"
    }
}