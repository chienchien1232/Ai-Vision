package com.example.ai_vision.device

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** The only TCP transport; connect requires an explicit HELLO|2 handshake. */
class V2GlassTransport : GlassVoiceSession {
    private data class Frame(val type: String, val id: String, val name: String, val meta: JSONObject, val payload: ByteArray)
    private data class Pending(val id: String, val frames: Channel<Frame>)
    private data class AudioBuffer(
        val sessionId: String,
        val languageTag: String,
        val bytes: ByteArrayOutputStream = ByteArrayOutputStream(),
        var nextSeq: Int = 0
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandMutex = Mutex()
    private val eventChannel = Channel<GlassVoiceEvent>(Channel.BUFFERED)
    override val voiceEvents: Flow<GlassVoiceEvent> = eventChannel.receiveAsFlow()
    @Volatile private var socket: Socket? = null
    @Volatile private var pending: Pending? = null
    @Volatile private var closed = false
    private var readerJob: Job? = null
    private var capabilities = emptySet<String>()
    private var audioBuffer: AudioBuffer? = null // reader coroutine owns this field
    @Volatile private var requestedLanguageTag = "vi-VN"
    @Volatile private var playbackRequestId: String? = null

    suspend fun connect(endpoint: GlassEndpoint) = withContext(Dispatchers.IO) {
        require(endpoint.host.isNotBlank() && endpoint.port in 1..65535)
        val newSocket = Socket()
        try {
            newSocket.connect(InetSocketAddress(endpoint.host, endpoint.port), 4_000)
            newSocket.tcpNoDelay = true // avoid delayed small-frame sends during stop-and-wait PCM
            newSocket.soTimeout = 3_000
            newSocket.getOutputStream().write("HELLO|2\n".toByteArray(Charsets.US_ASCII))
            newSocket.getOutputStream().flush()
            val hello = readLine(newSocket.getInputStream(), 128)
            if (!hello.startsWith("HELLO|2|")) throw IOException("ESP32 does not support V2: $hello")
            capabilities = hello.removePrefix("HELLO|2|").split(',').filter { it.isNotBlank() }.toSet()
            newSocket.soTimeout = 0
            closed = false
            socket = newSocket
            readerJob = scope.launch { readLoop(newSocket) }
        } catch (error: Exception) {
            newSocket.close()
            throw error
        }
    }

    override fun disconnect() {
        closed = true
        val old = socket
        socket = null
        runCatching { old?.close() }
        pending?.frames?.close(IOException("Glasses disconnected"))
        readerJob?.cancel()
        readerJob = null
        eventChannel.close()
    }

    override suspend fun ping(): String = request("PING", JSONObject(), 3_000) { frames ->
        expect(frames.receive(), "PONG")
        "PONG"
    }

    override suspend fun getStatus(): GlassStatus = request("GET_STATUS", JSONObject(), 3_000) { frames ->
        val meta = expect(frames.receive(), "STATUS").meta
        if (!meta.has("wifi") || meta.isNull("wifi")) throw IOException("STATUS missing wifi")
        val wifi = meta.getBoolean("wifi")
        val rssi = if (meta.has("rssi") && !meta.isNull("rssi")) meta.getInt("rssi") else null
        GlassStatus(
            wifiConnected = wifi,
            rssi = rssi,
            cameraReady = "PHOTO" in capabilities,
            micReady = "AUDIO_IN" in capabilities,
            speakerReady = "AUDIO_OUT" in capabilities
        )
    }

    override suspend fun capture(): CapturedImage {
        check("PHOTO" in capabilities) { "Firmware V2 does not support PHOTO" }
        val photo = request("TAKE_PHOTO", JSONObject(), 15_000) { frames ->
            expect(frames.receive(), "PHOTO_CAPTURED").meta
        }
        val mediaId = photo.optString("mediaId")
        val mime = photo.optString("mime")
        val size = photo.optInt("size", -1)
        val width = photo.optInt("width", -1)
        val height = photo.optInt("height", -1)
        if (mediaId.isBlank() || mime != "image/jpeg" || size !in 1..GlassProtocol.MAX_JPEG_BYTES || width !in 1..4096 || height !in 1..4096) {
            throw IOException("Invalid PHOTO_CAPTURED metadata")
        }
        return downloadPhoto(PhotoReference(mediaId, size, width, height))
    }

    override suspend fun downloadPhoto(reference: PhotoReference): CapturedImage {
        val (mediaId, size, width, height) = reference
        require(mediaId.isNotBlank() && size in 1..GlassProtocol.MAX_JPEG_BYTES &&
            width in 1..4096 && height in 1..4096) { "Invalid photo reference" }
        val jpeg = requestMediaBytes(mediaId, size, GlassProtocol.MAX_JPEG_BYTES)
        if (jpeg.size < 4 || jpeg[0] != 0xff.toByte() || jpeg[1] != 0xd8.toByte() ||
            jpeg[jpeg.lastIndex - 1] != 0xff.toByte() || jpeg.last() != 0xd9.toByte()) {
            throw IOException("Invalid JPEG data")
        }
        return CapturedImage(jpeg, width, height)
    }

    private suspend fun requestMediaBytes(mediaId: String, size: Int, maxSize: Int): ByteArray =
        request("GET_MEDIA", JSONObject().put("mediaId", mediaId), 60_000) { frames ->
            val output = ByteArrayOutputStream(size.coerceAtMost(maxSize))
            var nextSeq = 0
            while (true) {
                val frame = frames.receive()
                if (frame.type == "error") throw GlassCommandException(frame.meta.optString("code", "ERROR"))
                if (frame.name == "MEDIA_END") {
                    if (frame.payload.isNotEmpty() || output.size() != size || frame.meta.optString("mediaId") != mediaId) {
                        throw IOException("Incomplete media")
                    }
                    break
                }
                if (frame.name != "MEDIA_CHUNK" || frame.meta.optString("mediaId") != mediaId ||
                    frame.meta.optInt("seq", -1) != nextSeq || frame.meta.optInt("offset", -1) != output.size() ||
                    frame.meta.optInt("totalSize", -1) != size || frame.payload.isEmpty() || output.size() + frame.payload.size > size) {
                    throw IOException("Invalid media chunk")
                }
                output.write(frame.payload)
                nextSeq++
            }
            output.toByteArray()
        }

    override fun supportsVideo(): Boolean = "VIDEO" in capabilities

    override suspend fun startVideo(): String {
        check(supportsVideo()) { "Firmware V2 does not support VIDEO" }
        var submittedId: String? = null
        try {
            val started = request("START_VIDEO", JSONObject(), 15_000, onSubmitted = { submittedId = it }) { frames ->
                expect(frames.receive(), "VIDEO_STARTED").meta
            }
            return started.optString("recordingId").takeIf { it.isNotBlank() }
                ?: throw IOException("Missing recordingId")
        } catch (error: CancellationException) {
            // START_VIDEO may already have succeeded while its response was
            // drained. Cancel that exact request, not an unrelated mic session.
            withContext(NonCancellable) {
                submittedId?.let { target ->
                    try { request("CANCEL", JSONObject().put("targetId", target), 5_000) { expect(it.receive(), "CANCELLED") } }
                    catch (cleanupError: Exception) { disconnect() }
                }
            }
            throw error
        }
    }

    override suspend fun stopVideo(): VideoDownload {
        val stopped = request("STOP_VIDEO", JSONObject(), 15_000) { frames ->
            expect(frames.receive(), "VIDEO_STOPPED").meta
        }
        val mediaId = stopped.optString("mediaId")
        val size = stopped.optInt("size", -1)
        if (mediaId.isBlank() || stopped.optString("mime") != "video/x-msvideo" || size !in 1..20 * 1024 * 1024) {
            throw IOException("Invalid VIDEO_STOPPED metadata")
        }
        return VideoDownload(mediaId, size)
    }

    override suspend fun downloadMedia(mediaId: String, size: Int): ByteArray {
        require(mediaId.isNotBlank() && size in 1..20 * 1024 * 1024) { "Invalid video reference" }
        return requestMediaBytes(mediaId, size, 20 * 1024 * 1024)
    }

    override suspend fun acknowledgeMedia(mediaId: String) {
        request("ACK_MEDIA", JSONObject().put("mediaId", mediaId), 5_000) { expect(it.receive(), "MEDIA_ACKNOWLEDGED") }
    }

    override fun supportsWake(): Boolean = "WAKE_EVENT" in capabilities

    override suspend fun configureVoice(languageTag: String, wakeWord: String) {
        check(wakeWord.isEmpty() || supportsWake()) { "No wake model on glasses. Use manual listening." }
        require(languageTag in setOf("vi-VN", "en-US"))
        require(wakeWord in setOf("Hi ESP", "")) { "Custom wake word needs a trained firmware model" }
        request("SET_VOICE_CONFIG", JSONObject().put("language", languageTag).put("wakeWord", wakeWord), 5_000) {
            expect(it.receive(), "VOICE_CONFIGURED")
        }
    }

    override suspend fun startListening(languageTag: String, wakeWord: String, diagnostic: Boolean): String {
        check("AUDIO_IN" in capabilities) { "Firmware V2 does not support AUDIO_IN" }
        require(languageTag == "vi-VN" || languageTag == "en-US")
        require(wakeWord.length <= 32) { "Wake word is too long" }
        requestedLanguageTag = languageTag
        val meta = JSONObject().put("language", languageTag)
        if (diagnostic) meta.put("mode", "diagnostic")
        if (wakeWord.isNotBlank()) meta.put("wakeWord", wakeWord)
        return request("START_LISTENING", meta, 8_000) { frames ->
            val reply = expect(frames.receive(), "LISTENING_STARTED").meta
            reply.optString("sessionId").takeIf { it.isNotBlank() }
                ?: throw IOException("Missing audio sessionId")
        }
    }

    override fun supportsAudioOut(): Boolean = "AUDIO_OUT" in capabilities

    override suspend fun stopListening() {
        request("STOP_LISTENING", JSONObject(), 5_000) { frames ->
            expect(frames.receive(), "LISTENING_STOPPED")
        }
    }

    override suspend fun cancelActive() {
        val target = playbackRequestId
        if (target != null) {
            request("CANCEL", JSONObject().put("targetId", target), 5_000) { frames ->
                expect(frames.receive(), "CANCELLED")
            }
            playbackRequestId = null
        } else if ("AUDIO_IN" in capabilities) {
            stopListening()
        }
    }

    override suspend fun playAudio(pcm: ByteArray): Unit = withContext(Dispatchers.IO) {
        check(supportsAudioOut()) { "Firmware V2 does not support AUDIO_OUT" }
        require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= 16000 * 2 * 30) {
            "Answer audio must be PCM16 mono 16 kHz, at most 30 seconds"
        }
        try {
            val ready = request("PLAY_AUDIO", JSONObject()
                .put("encoding", "PCM_S16LE")
                .put("sampleRate", 16000)
                .put("channels", 1)
                .put("totalBytes", pcm.size), 8_000) { frames ->
                expect(frames.receive(), "AUDIO_READY").meta
            }
            val sessionId = ready.optString("sessionId")
            if (sessionId.isBlank()) throw IOException("Missing playback sessionId")
            var offset = 0
            var seq = 0
            while (offset < pcm.size) {
                val count = minOf(8192, pcm.size - offset)
                val chunk = pcm.copyOfRange(offset, offset + count)
                val ack = request("AUDIO_OUT_CHUNK", JSONObject()
                    .put("sessionId", sessionId)
                    .put("seq", seq), 8_000, chunk) { frames ->
                    expect(frames.receive(), "AUDIO_OUT_ACK").meta
                }
                if (ack.optString("sessionId") != sessionId || ack.optInt("seq", -1) != seq) {
                    throw IOException("Invalid playback ACK")
                }
                offset += count
                seq++
            }
            val played = request("AUDIO_OUT_END", JSONObject()
                .put("sessionId", sessionId)
                .put("seq", seq), playbackCompletionTimeout(pcm.size)) { frames ->
                expect(frames.receive(), "AUDIO_PLAYED").meta.also { playbackRequestId = null }
            }
            if (played.optString("sessionId") != sessionId) throw IOException("Playback session mismatch")
            playbackRequestId = null
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                try { cancelActive() } catch (cancelError: Exception) { disconnect() }
            }
            throw error
        }
    }

    private suspend fun <T> request(name: String, meta: JSONObject, timeoutMs: Long, payload: ByteArray = byteArrayOf(), onSubmitted: (String) -> Unit = {}, read: suspend (Channel<Frame>) -> T): T =
        commandMutex.withLock {
            currentCoroutineContext().ensureActive()
            val active = socket ?: throw IOException("ESP32 is not connected")
            require(payload.size <= 8192) { "V2 playback chunk is too large" }
            val id = UUID.randomUUID().toString()
            val frames = Channel<Frame>(Channel.BUFFERED)
            pending = Pending(id, frames)
            onSubmitted(id)
            if (name == "PLAY_AUDIO") playbackRequestId = id
            var responseDrained = false
            val finished = AtomicBoolean(false)
            // Closing the socket is the only reliable deadline for a blocking Java write.
            val deadline = scope.launch(Dispatchers.Default) {
                delay(timeoutMs)
                if (finished.compareAndSet(false, true)) runCatching { active.close() }
            }
            try {
                val header = JSONObject().put("v", 2).put("type", "request").put("id", id)
                    .put("name", name).put("payloadBytes", payload.size).put("meta", meta)
                    .toString().toByteArray(Charsets.UTF_8)
                if (header.size > 1023) throw IOException("V2 request header too long")
                val frame = ByteArray(header.size + 1 + payload.size)
                header.copyInto(frame)
                frame[header.size] = '\n'.code.toByte()
                payload.copyInto(frame, header.size + 1)
                // Drain this bounded response before honoring cancellation so a
                // CANCEL/STOP_LISTENING can reuse the framed connection safely.
                withContext(NonCancellable + Dispatchers.IO) {
                    active.getOutputStream().write(frame)
                    active.getOutputStream().flush()
                    withTimeout(timeoutMs) { read(frames) }.also {
                        responseDrained = true
                        finished.set(true) // Publish before returning to Main/starting the next request.
                        deadline.cancel()
                    }
                }
            } catch (error: TimeoutCancellationException) {
                disconnect()
                throw SocketTimeoutException("$name timed out").also { it.initCause(error) }
            } catch (error: GlassCommandException) {
                if (name == "PLAY_AUDIO") playbackRequestId = null
                throw error
            } catch (error: CancellationException) {
                if (!responseDrained) disconnect()
                throw error
            } catch (error: Exception) {
                disconnect() // A late response could otherwise be mistaken for another request.
                throw error
            } finally {
                finished.set(true)
                deadline.cancel()
                pending = null
                frames.close()
            }
        }

    private fun expect(frame: Frame, name: String): Frame {
        if (frame.type == "error") throw GlassCommandException(frame.meta.optString("code", "ERROR"))
        if (frame.type != "response" || frame.name != name || frame.payload.isNotEmpty()) {
            throw IOException("Expected $name, received ${frame.name}")
        }
        return frame
    }

    private suspend fun readLoop(active: Socket) {
        try {
            val input = active.getInputStream()
            while (!closed) {
                val frame = readFrame(input)
                if (frame.type == "event") {
                    handleEvent(frame)
                } else {
                    if (frame.type == "response" && frame.name == "LISTENING_STARTED") {
                        val sessionId = frame.meta.optString("sessionId")
                        if (sessionId.isNotBlank() && audioBuffer?.sessionId != sessionId) {
                            audioBuffer = AudioBuffer(sessionId, requestedLanguageTag)
                        }
                    }
                    if (frame.type == "response" && frame.name == "LISTENING_STOPPED") audioBuffer = null
                    pending?.takeIf { it.id == frame.id }?.frames?.send(frame)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!closed) eventChannel.trySend(GlassVoiceEvent.Failure(error.message ?: "ESP32 connection lost"))
        } finally {
            disconnect()
        }
    }

    private suspend fun handleEvent(frame: Frame) {
        val meta = frame.meta
        when (frame.name) {
            "WAKE_WORD_DETECTED" -> {
                val sessionId = meta.optString("sessionId")
                if (sessionId.isBlank()) throw IOException("Wake event has no sessionId")
                if (audioBuffer == null) {
                    audioBuffer = AudioBuffer(sessionId, meta.optString("language", "vi-VN"))
                } else if (audioBuffer?.sessionId != sessionId) throw IOException("Overlapping audio sessions")
            }
            "AUDIO_CHUNK" -> {
                val buffer = audioBuffer ?: throw IOException("Audio arrived without wake or listening session")
                if (meta.optString("sessionId") != buffer.sessionId || meta.optInt("seq", -1) != buffer.nextSeq ||
                    meta.optString("encoding") != "PCM_S16LE" || meta.optInt("sampleRate", -1) != 16000 ||
                    meta.optInt("channels", -1) != 1 || frame.payload.isEmpty() || frame.payload.size % 2 != 0 ||
                    buffer.bytes.size() + frame.payload.size > 16000 * 2 * 30) {
                    throw IOException("Invalid audio chunk")
                }
                buffer.bytes.write(frame.payload)
                buffer.nextSeq++
            }
            "AUDIO_END" -> {
                val buffer = audioBuffer ?: throw IOException("Audio ended without a session")
                if (meta.optString("sessionId") != buffer.sessionId || meta.optInt("seq", -1) != buffer.nextSeq ||
                    frame.payload.isNotEmpty() || buffer.bytes.size() == 0) throw IOException("Invalid AUDIO_END")
                audioBuffer = null
                eventChannel.send(GlassVoiceEvent.Utterance(buffer.sessionId, buffer.bytes.toByteArray(), buffer.languageTag))
            }
            "LOCAL_COMMAND_EXECUTED" -> {
                val sessionId = meta.optString("sessionId")
                val command = meta.optString("command")
                val result = meta.optString("result")
                if (sessionId.isBlank() || command.isBlank() || result.isBlank() || frame.payload.isNotEmpty())
                    throw IOException("Invalid local command event")
                val photo = if (meta.has("mediaId")) {
                    val reference = PhotoReference(meta.optString("mediaId"), meta.optInt("size", -1),
                        meta.optInt("width", -1), meta.optInt("height", -1))
                    if (meta.optString("mime") != "image/jpeg" || reference.mediaId.isBlank() ||
                        reference.size !in 1..GlassProtocol.MAX_JPEG_BYTES || reference.width !in 1..4096 || reference.height !in 1..4096)
                        throw IOException("Invalid local photo descriptor")
                    reference
                } else null
                if (sessionId == audioBuffer?.sessionId) audioBuffer = null
                eventChannel.send(GlassVoiceEvent.LocalCommand(sessionId, command, result, photo,
                    meta.optString("confirmation").takeIf { it.isNotBlank() }))
            }
            "MEDIA_AVAILABLE" -> {
                val mediaId = meta.optString("mediaId")
                val mime = meta.optString("mime")
                val size = meta.optInt("size", -1)
                if (mediaId.isBlank() || mime != "video/x-msvideo" || size !in 1..20 * 1024 * 1024 || frame.payload.isNotEmpty()) {
                    throw IOException("Invalid MEDIA_AVAILABLE")
                }
                eventChannel.send(GlassVoiceEvent.MediaAvailable(mediaId, mime, size))
            }
            "DEVICE_ERROR" -> {
                val sessionId = meta.optString("sessionId").takeIf { it.isNotBlank() }
                if (sessionId == audioBuffer?.sessionId) audioBuffer = null
                eventChannel.send(GlassVoiceEvent.DeviceError(meta.optString("code", "Device error"), sessionId))
            }
        }
    }

    private fun readFrame(input: InputStream): Frame {
        val json = try { JSONObject(readLine(input, 1024)) } catch (error: Exception) {
            throw IOException("Invalid V2 header", error)
        }
        val version = json.optInt("v", -1)
        val type = json.optString("type")
        val id = json.optString("id")
        val name = json.optString("name")
        val count = json.optInt("payloadBytes", -1)
        val meta = json.optJSONObject("meta") ?: throw IOException("V2 frame has no meta")
        if (version != 2 || type !in setOf("response", "event", "error") || name.isBlank() ||
            runCatching { UUID.fromString(id) }.isFailure || count !in 0..65536) {
            throw IOException("Invalid V2 frame fields")
        }
        val payload = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(payload, offset, count - offset)
            if (read < 0) throw EOFException("V2 payload ended early")
            offset += read
        }
        return Frame(type, id, name, meta, payload)
    }

    private fun readLine(input: InputStream, maxBytes: Int): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) throw EOFException("ESP32 closed the connection")
            if (next == '\n'.code) break
            if (bytes.size() >= maxBytes) throw IOException("Response header is too long")
            bytes.write(next)
        }
        return bytes.toString(Charsets.UTF_8.name()).trimEnd('\r')
    }
}

/** Firmware may preload the whole reply before END; budget its full duration. */
internal fun playbackCompletionTimeout(pcmBytes: Int): Long {
    require(pcmBytes in 2..960000 && pcmBytes % 2 == 0)
    val durationMs = (pcmBytes * 1000L + 31999) / 32000
    return maxOf(8000L, durationMs + 5000L)
}
