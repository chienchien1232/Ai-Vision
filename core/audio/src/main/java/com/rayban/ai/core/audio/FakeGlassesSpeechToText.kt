package com.rayban.ai.core.audio

import android.util.Log
import com.rayban.ai.domain.audio.SpeechError
import com.rayban.ai.domain.audio.SpeechEvent
import com.rayban.ai.domain.audio.SpeechToText
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Stand-in for the future ESP32 microphone transcription path
 * (PCM stream over Wi-Fi -> voice activity detection -> transcription).
 *
 * Emits the same [SpeechEvent] contract as [CloudSpeechToText] so selecting
 * [com.rayban.ai.domain.wearable.DeviceSource.GLASSES] exercises the real routing
 * path today. Replaced 1:1 by `Esp32SpeechToText` once firmware exists.
 */
@Singleton
class FakeGlassesSpeechToText @Inject constructor() : SpeechToText {

    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = EVENT_BUFFER)
    override val events: Flow<SpeechEvent> = _events.asSharedFlow()

    override fun startListening() {
        Log.w(TAG, "[GlassesSpeech] Transcription is not available")
        _events.tryEmit(SpeechEvent.Error(SpeechError.NotAvailable))
    }

    override fun stopListening() {
        _events.tryEmit(SpeechEvent.Cancelled)
    }

    override fun cancel() {
        _events.tryEmit(SpeechEvent.Cancelled)
        Log.d(TAG, "[GlassesSpeech] Cancelled")
    }

    /** Test/demo hook to simulate a transcription result from the glasses mic. */
    fun emit(event: SpeechEvent) {
        _events.tryEmit(event)
    }

    private companion object {
        const val TAG = "FakeGlassesSpeechToText"
        const val EVENT_BUFFER = 32
    }
}
