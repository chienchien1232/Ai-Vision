package com.example.ai_vision

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ai_vision.device.DemoVoiceSession
import com.example.ai_vision.device.GlassVoiceEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Recording cancellation with a controllable PCM source; no speech model required. */
@RunWith(AndroidJUnit4::class)
class DemoVoiceFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @Test fun cancelRecordingEmitsNoOldAudioAndTheNextSessionCanReceiveInput() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val session = DemoVoiceSession(scope) {
            if (++calls == 1) { started.complete(Unit); awaitCancellation() }
            byteArrayOf(0, 16)
        }
        try {
            main { scope.launch { session.startListening("vi-VN", "", false) } }
            withTimeout(3000) { started.await() }
            withContext(Dispatchers.Main) { session.cancelActive() }
            val next = async { withTimeout(3000) { session.voiceEvents.first() } }
            val id = withContext(Dispatchers.Main) { session.startListening("vi-VN", "", false) }
            val event = next.await() as GlassVoiceEvent.Utterance
            assertEquals(id, event.sessionId)
            assertEquals(2, calls)
        } finally { main { session.disconnect(); scope.cancel() } }
    }
}
