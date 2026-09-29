package com.example.ai_vision.device

import kotlinx.coroutines.flow.Flow

data class CapturedImage(val jpeg: ByteArray, val width: Int, val height: Int)

/** Commands available in both the real glasses session and the in-app demo. */
interface GlassSession {
    suspend fun ping(): String
    suspend fun getStatus(): GlassStatus
    suspend fun capture(): CapturedImage
    fun disconnect()
}

/** A finished glasses recording waiting on storage. */
data class VideoDownload(val mediaId: String, val size: Int)

data class PhotoReference(val mediaId: String, val size: Int, val width: Int, val height: Int)

sealed interface GlassVoiceEvent {
    data class Utterance(val sessionId: String, val pcm: ByteArray, val languageTag: String) : GlassVoiceEvent
    data class LocalCommand(val sessionId: String, val command: String, val result: String,
        val photo: PhotoReference? = null, val confirmation: String? = null) : GlassVoiceEvent
    data class MediaAvailable(val mediaId: String, val mime: String, val size: Int) : GlassVoiceEvent
    data class DeviceError(val code: String, val sessionId: String?) : GlassVoiceEvent
    data class Failure(val message: String) : GlassVoiceEvent
}

/** Available only after HELLO|2 confirms the firmware audio protocol. */
interface GlassVoiceSession : GlassSession {
    val voiceEvents: Flow<GlassVoiceEvent>
    suspend fun startListening(languageTag: String, wakeWord: String = "", diagnostic: Boolean = false): String
    suspend fun downloadPhoto(reference: PhotoReference): CapturedImage
    fun supportsWake(): Boolean
    suspend fun configureVoice(languageTag: String, wakeWord: String)
    suspend fun stopListening()
    suspend fun cancelActive()
    /** True when the firmware advertised AUDIO_OUT (glasses speaker). */
    fun supportsAudioOut(): Boolean
    /** Streams PCM_S16LE mono 16 kHz answer audio to the glasses speaker. */
    suspend fun playAudio(pcm: ByteArray)
    /** True when the firmware advertised VIDEO (camera plus SD storage). */
    fun supportsVideo(): Boolean
    /** Starts MJPEG recording on the glasses; returns the recordingId. */
    suspend fun startVideo(): String
    /** Stops recording and returns the stored video reference. */
    suspend fun stopVideo(): VideoDownload
    /** Downloads a stored video by mediaId. */
    suspend fun downloadMedia(mediaId: String, size: Int): ByteArray
    /** Marks a video saved to Gallery. The SD file is not deleted. */
    suspend fun acknowledgeMedia(mediaId: String)
}
