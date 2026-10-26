package com.example.ai_vision.device

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Simulates glasses input with foreground Android PCM; photos remain explicitly synthetic. */
class DemoVoiceSession(
    private val scope: CoroutineScope,
    private val record: suspend (diagnostic: Boolean) -> ByteArray
) : GlassVoiceSession {
    private val camera = FakeGlassSession()
    private val events = Channel<GlassVoiceEvent>(Channel.UNLIMITED)
    override val voiceEvents: Flow<GlassVoiceEvent> = events.receiveAsFlow()
    private var connected = true
    private var recording: Job? = null

    override suspend fun startListening(languageTag: String, wakeWord: String, diagnostic: Boolean): String {
        check(connected) { "Demo device is disconnected" }
        check(recording?.isActive != true) { "Demo microphone is busy" }
        require(languageTag in setOf("vi-VN", "en-US") && wakeWord.isEmpty())
        val id = UUID.randomUUID().toString()
        recording = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val pcm = record(diagnostic)
                currentCoroutineContext().ensureActive()
                events.send(GlassVoiceEvent.Utterance(id, pcm, languageTag))
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                events.send(GlassVoiceEvent.DeviceError(error.message ?: "DEMO_MIC_FAILED", id))
            }
        }.also { it.start() }
        return id
    }

    override suspend fun stopListening() {
        val previous = recording
        recording = null
        previous?.cancel()
        previous?.join()
    }
    override suspend fun cancelActive() = stopListening()
    override suspend fun ping() = camera.ping()
    override suspend fun getStatus() = camera.getStatus()
    override suspend fun capture() = camera.capture()
    override fun disconnect() {
        connected = false
        recording?.cancel()
        recording = null
        camera.disconnect()
        events.close()
    }
    override fun supportsWake() = false
    override suspend fun configureVoice(languageTag: String, wakeWord: String) {
        check(connected)
        require(languageTag in setOf("vi-VN", "en-US") && wakeWord.isEmpty())
    }
    // Demo output is owned by DeviceController's PcmOutput, not a simulated glass speaker.
    override fun supportsAudioOut() = false
    override suspend fun playAudio(pcm: ByteArray): Unit = error("Use Android Demo audio output")
    override suspend fun downloadPhoto(reference: PhotoReference): CapturedImage = error("No stored Demo photos")
    override fun supportsVideo() = false
    override suspend fun startVideo(): String = error("Demo video is unavailable")
    override suspend fun stopVideo(): VideoDownload = error("Demo video is unavailable")
    override suspend fun downloadMedia(mediaId: String, size: Int): ByteArray = error("No stored Demo media")
    override suspend fun acknowledgeMedia(mediaId: String) = Unit
}
