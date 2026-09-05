package com.rayban.ai.core.audio

import android.util.Log
import com.rayban.ai.domain.audio.SpeechEvent
import com.rayban.ai.domain.audio.SpeechToText
import com.rayban.ai.domain.wearable.ActiveWearableSession
import com.rayban.ai.domain.wearable.DeviceSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transform

/**
 * [SpeechToText] bound in DI. Forwards listening to the phone recognizer or the
 * glasses transcription backend depending on [ActiveWearableSession.micSource].
 * An in-flight listen retains its original backend until termination. Like the
 * phone recognizer, calls and the single events collector must run on the main
 * dispatcher; establish event collection before starting a listen.
 *
 * Constructed explicitly in the app DI module (same rationale as the camera
 * delegates: interface-typed constructor for testability).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveSpeechToText(
    private val session: ActiveWearableSession,
    private val phone: SpeechToText,
    private val glasses: SpeechToText,
) : SpeechToText {

    private var owner: SpeechToText? = null
    private var generation = 0L
    private val cancellations = MutableSharedFlow<Pair<Long, SpeechEvent>>(extraBufferCapacity = 16)

    // Subscribe to both backends before starting: startListening can emit synchronously.
    override val events: Flow<SpeechEvent> = merge(
        phone.events.map { (if (owner === phone) generation else -1L) to it },
        glasses.events.map { (if (owner === glasses) generation else -1L) to it },
        cancellations,
    ).transform { (eventGeneration, event) ->
        if (eventGeneration == generation) {
            if (event is SpeechEvent.FinalResult || event is SpeechEvent.Error || event == SpeechEvent.Cancelled) {
                owner = null
                generation++
            }
            emit(event)
        }
    }

    override fun startListening() {
        val source = session.micSource.value
        Log.d(TAG, "[Speech] startListening via $source")
        val previous = owner
        owner = null
        generation++
        previous?.cancel()
        val next = active(source)
        owner = next
        try {
            next.startListening()
        } catch (error: Exception) {
            owner = null
            generation++
            throw error
        }
    }

    override fun stopListening() {
        owner?.stopListening()
    }

    override fun cancel() {
        val previous = owner ?: return
        owner = null
        generation++
        try {
            previous.cancel()
        } finally {
            cancellations.tryEmit(generation to SpeechEvent.Cancelled)
        }
    }

    private fun active(source: DeviceSource): SpeechToText = when (source) {
        DeviceSource.GLASSES -> glasses
        DeviceSource.PHONE -> phone
    }

    private companion object {
        const val TAG = "ActiveSpeechToText"
    }
}
