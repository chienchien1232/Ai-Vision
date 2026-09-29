package com.example.ai_vision

import com.example.ai_vision.device.GlassEndpoint
import com.example.ai_vision.device.GlassVoiceEvent
import com.example.ai_vision.device.V2GlassTransport
import com.example.ai_vision.device.playbackCompletionTimeout
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class V2GlassTransportTest {
    @Test
    fun completionDeadlineAllowsFullThirtySecondPreloadedReply() {
        assertEquals(8000L, playbackCompletionTimeout(2))
        assertEquals(35000L, playbackCompletionTimeout(960000))
        assertEquals(15000L, playbackCompletionTimeout(320000))
    }

    @Test
    fun callerUiStallDoesNotPausePcmTransfer() {
        val pcm = ByteArray(96000) { (it % 251).toByte() }
        val firstChunk = CountDownLatch(1)
        val callerBlocked = CountDownLatch(1)
        val releaseCaller = CountDownLatch(1)
        val transferComplete = CountDownLatch(1)
        val workerError = AtomicReference<Throwable?>()
        val callerExecutor = Executors.newSingleThreadExecutor()
        val caller = callerExecutor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + caller)
        val transport = V2GlassTransport()
        ServerSocket(0).use { server ->
            val worker = thread {
                try {
                    server.accept().use { client ->
                        client.soTimeout = 5000
                        val input = client.getInputStream()
                        val output = client.getOutputStream()
                        assertEquals("HELLO|2", readLine(input))
                        output.write("HELLO|2|AUDIO_OUT\n".toByteArray())
                        output.flush()
                        val start = JSONObject(readLine(input))
                        assertEquals("PLAY_AUDIO", start.getString("name"))
                        assertEquals(pcm.size, start.getJSONObject("meta").getInt("totalBytes"))
                        val sessionId = UUID.randomUUID().toString()
                        sendFrame(output, "response", start.getString("id"), "AUDIO_READY",
                            JSONObject().put("sessionId", sessionId))
                        val received = ByteArrayOutputStream()
                        var seq = 0
                        while (received.size() < pcm.size) {
                            val header = JSONObject(readLine(input))
                            assertEquals("AUDIO_OUT_CHUNK", header.getString("name"))
                            assertEquals(seq, header.getJSONObject("meta").getInt("seq"))
                            val bytes = ByteArray(header.getInt("payloadBytes"))
                            var offset = 0
                            while (offset < bytes.size) {
                                val count = input.read(bytes, offset, bytes.size - offset)
                                check(count > 0)
                                offset += count
                            }
                            received.write(bytes)
                            if (seq == 0) {
                                firstChunk.countDown()
                                check(callerBlocked.await(3, TimeUnit.SECONDS))
                            }
                            sendFrame(output, "response", header.getString("id"), "AUDIO_OUT_ACK",
                                JSONObject().put("sessionId", sessionId).put("seq", seq++))
                        }
                        assertTrue(pcm.contentEquals(received.toByteArray()))
                        val end = JSONObject(readLine(input))
                        assertEquals("AUDIO_OUT_END", end.getString("name"))
                        assertEquals(seq, end.getJSONObject("meta").getInt("seq"))
                        sendFrame(output, "response", end.getString("id"), "AUDIO_PLAYED",
                            JSONObject().put("sessionId", sessionId))
                        transferComplete.countDown()
                    }
                } catch (error: Throwable) { workerError.set(error) }
            }
            var progressedWithoutCaller = false
            try {
                runBlocking(caller) { transport.connect(GlassEndpoint("127.0.0.1", server.localPort)) }
                val playback = scope.launch { transport.playAudio(pcm) }
                assertTrue(firstChunk.await(3, TimeUnit.SECONDS))
                callerExecutor.execute {
                    callerBlocked.countDown()
                    releaseCaller.await(5, TimeUnit.SECONDS)
                }
                progressedWithoutCaller = transferComplete.await(2, TimeUnit.SECONDS)
                releaseCaller.countDown()
                runBlocking { withTimeout(3000) { playback.join() } }
            } finally {
                releaseCaller.countDown()
                transport.disconnect()
                worker.join(3000)
                scope.cancel()
                caller.close()
            }
            workerError.get()?.let { throw it }
            assertTrue("PCM transfer waited for the blocked caller/UI dispatcher", progressedWithoutCaller)
        }
    }

    @Test
    fun receivesBufferedEightSecondMicRecordingBeforeTranscription() {
        runBlocking {
            val pcm = ByteArray(16000 * 2 * 8) { (it % 251).toByte() }
            val sessionId = UUID.randomUUID().toString()
            ServerSocket(0).use { server ->
                val workerError = AtomicReference<Throwable?>()
                val worker = thread {
                    try {
                        server.accept().use { client ->
                            val input = client.getInputStream()
                            val output = client.getOutputStream()
                            assertEquals("HELLO|2", readLine(input))
                            output.write("HELLO|2|AUDIO_IN\n".toByteArray())
                            output.flush()
                            val start = JSONObject(readLine(input))
                            assertEquals("START_LISTENING", start.getString("name"))
                            sendFrame(output, "response", start.getString("id"), "LISTENING_STARTED",
                                JSONObject().put("sessionId", sessionId))
                            var offset = 0
                            var seq = 0
                            while (offset < pcm.size) {
                                val count = minOf(16384, pcm.size - offset)
                                sendFrame(output, "event", UUID.randomUUID().toString(), "AUDIO_CHUNK",
                                    JSONObject().put("sessionId", sessionId).put("seq", seq)
                                        .put("encoding", "PCM_S16LE").put("sampleRate", 16000)
                                        .put("channels", 1), pcm.copyOfRange(offset, offset + count))
                                offset += count
                                seq++
                            }
                            sendFrame(output, "event", UUID.randomUUID().toString(), "AUDIO_END",
                                JSONObject().put("sessionId", sessionId).put("seq", seq))
                        }
                    } catch (error: Throwable) {
                        workerError.set(error)
                    }
                }
                val transport = V2GlassTransport()
                try {
                    transport.connect(GlassEndpoint("127.0.0.1", server.localPort))
                    transport.startListening("vi-VN")
                    val utterance = withTimeout(10_000) {
                        transport.voiceEvents.first()
                    } as GlassVoiceEvent.Utterance
                    assertEquals(sessionId, utterance.sessionId)
                    assertTrue(pcm.contentEquals(utterance.pcm))
                } finally {
                    transport.disconnect()
                    worker.join(3_000)
                }
                workerError.get()?.let { throw it }
            }
        }
    }

    @Test
    fun rejectsOutOfOrderAudioInsteadOfSendingItToStt() {
        runBlocking {
            ServerSocket(0).use { server ->
                val sessionId = UUID.randomUUID().toString()
                val worker = thread {
                    server.accept().use { client ->
                        val input = client.getInputStream()
                        val output = client.getOutputStream()
                        assertEquals("HELLO|2", readLine(input))
                        output.write("HELLO|2|AUDIO_IN,WAKE_EVENT\n".toByteArray())
                        output.flush()
                        sendFrame(output, "event", UUID.randomUUID().toString(), "WAKE_WORD_DETECTED",
                            JSONObject().put("sessionId", sessionId).put("language", "vi-VN"))
                        sendFrame(output, "event", UUID.randomUUID().toString(), "AUDIO_CHUNK",
                            JSONObject().put("sessionId", sessionId).put("seq", 1).put("encoding", "PCM_S16LE")
                                .put("sampleRate", 16000).put("channels", 1), byteArrayOf(1, 0))
                    }
                }
                val transport = V2GlassTransport()
                try {
                    transport.connect(GlassEndpoint("127.0.0.1", server.localPort))
                    val event = withTimeout(3_000) { transport.voiceEvents.first() } as GlassVoiceEvent.Failure
                    assertEquals("Invalid audio chunk", event.message)
                } finally {
                    transport.disconnect()
                    worker.join(3_000)
                }
            }
        }
    }

    @Test
    fun receivesGlassesPcmThenFetchesFreshJpeg() {
        runBlocking {
        val pcm = byteArrayOf(1, 0, 2, 0)
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
        val sessionId = UUID.randomUUID().toString()
        ServerSocket(0).use { server ->
            val workerError = AtomicReference<Throwable?>()
            val worker = thread {
                try {
                    server.accept().use { client ->
                        val input = client.getInputStream()
                        val output = client.getOutputStream()
                        assertEquals("HELLO|2", readLine(input))
                        output.write("HELLO|2|PHOTO,AUDIO_IN,WAKE_EVENT\n".toByteArray())
                        output.flush()

                        val listening = JSONObject(readLine(input))
                        assertEquals("START_LISTENING", listening.getString("name"))
                        sendFrame(output, "response", listening.getString("id"), "LISTENING_STARTED", JSONObject().put("sessionId", sessionId))
                        sendFrame(output, "event", UUID.randomUUID().toString(), "AUDIO_CHUNK",
                            JSONObject().put("sessionId", sessionId).put("seq", 0).put("encoding", "PCM_S16LE")
                                .put("sampleRate", 16000).put("channels", 1), pcm)
                        sendFrame(output, "event", UUID.randomUUID().toString(), "AUDIO_END",
                            JSONObject().put("sessionId", sessionId).put("seq", 1))

                        val capture = JSONObject(readLine(input))
                        assertEquals("TAKE_PHOTO", capture.getString("name"))
                        sendFrame(output, "response", capture.getString("id"), "PHOTO_CAPTURED",
                            JSONObject().put("mediaId", "photo-1").put("mime", "image/jpeg")
                                .put("size", jpeg.size).put("width", 320).put("height", 240))
                        val media = JSONObject(readLine(input))
                        assertEquals("GET_MEDIA", media.getString("name"))
                        assertEquals("photo-1", media.getJSONObject("meta").getString("mediaId"))
                        sendFrame(output, "response", media.getString("id"), "MEDIA_CHUNK",
                            JSONObject().put("mediaId", "photo-1").put("seq", 0).put("offset", 0).put("totalSize", jpeg.size), jpeg)
                        sendFrame(output, "response", media.getString("id"), "MEDIA_END", JSONObject().put("mediaId", "photo-1"))
                    }
                } catch (error: Throwable) {
                    workerError.set(error)
                }
            }
            val transport = V2GlassTransport()
            try {
                transport.connect(GlassEndpoint("127.0.0.1", server.localPort))
                transport.startListening("vi-VN")
                val utterance = withTimeout(3_000) { transport.voiceEvents.first() } as GlassVoiceEvent.Utterance
                assertEquals(sessionId, utterance.sessionId)
                assertTrue(pcm.contentEquals(utterance.pcm))
                val photo = transport.capture()
                assertEquals(320, photo.width)
                assertEquals(240, photo.height)
                assertTrue(jpeg.contentEquals(photo.jpeg))
            } finally {
                transport.disconnect()
                worker.join(3_000)
            }
            workerError.get()?.let { throw it }
        }
        }
    }

    @Test
    fun streamsAnswerPcmToGlassesSpeaker() {
        runBlocking {
            // 9000 bytes forces two chunks (8192 + 808) through the ACK loop.
            val pcm = ByteArray(9000) { (it % 256).toByte() }
            ServerSocket(0).use { server ->
                val received = ByteArrayOutputStream()
                val workerError = AtomicReference<Throwable?>()
                val worker = thread {
                    try {
                        server.accept().use { client ->
                            val input = client.getInputStream()
                            val output = client.getOutputStream()
                            assertEquals("HELLO|2", readLine(input))
                            output.write("HELLO|2|PHOTO,AUDIO_IN,AUDIO_OUT\n".toByteArray())
                            output.flush()
                            val play = JSONObject(readLine(input))
                            assertEquals("PLAY_AUDIO", play.getString("name"))
                            assertEquals(0, play.getInt("payloadBytes"))
                            val playMeta = play.getJSONObject("meta")
                            assertEquals("PCM_S16LE", playMeta.getString("encoding"))
                            assertEquals(16000, playMeta.getInt("sampleRate"))
                            assertEquals(1, playMeta.getInt("channels"))
                            assertEquals(pcm.size, playMeta.getInt("totalBytes"))
                            val sessionId = UUID.randomUUID().toString()
                            sendFrame(output, "response", play.getString("id"), "AUDIO_READY",
                                JSONObject().put("sessionId", sessionId))
                            var seq = 0
                            var remaining = pcm.size
                            while (remaining > 0) {
                                val head = JSONObject(readLine(input))
                                assertEquals("AUDIO_OUT_CHUNK", head.getString("name"))
                                assertEquals(sessionId, head.getJSONObject("meta").getString("sessionId"))
                                assertEquals(seq, head.getJSONObject("meta").getInt("seq"))
                                val count = head.getInt("payloadBytes")
                                assertTrue(count in 1..8192)
                                val buffer = ByteArray(count)
                                var offset = 0
                                while (offset < count) {
                                    val read = input.read(buffer, offset, count - offset)
                                    if (read < 0) error("PCM payload ended early")
                                    offset += read
                                }
                                received.write(buffer)
                                sendFrame(output, "response", head.getString("id"), "AUDIO_OUT_ACK",
                                    JSONObject().put("sessionId", sessionId).put("seq", seq))
                                seq++
                                remaining -= count
                            }
                            assertEquals(2, seq)
                            val end = JSONObject(readLine(input))
                            assertEquals("AUDIO_OUT_END", end.getString("name"))
                            assertEquals(seq, end.getJSONObject("meta").getInt("seq"))
                            sendFrame(output, "response", end.getString("id"), "AUDIO_PLAYED",
                                JSONObject().put("sessionId", sessionId))
                        }
                    } catch (error: Throwable) {
                        workerError.set(error)
                    }
                }
                val transport = V2GlassTransport()
                try {
                    transport.connect(GlassEndpoint("127.0.0.1", server.localPort))
                    assertTrue(transport.supportsAudioOut())
                    transport.playAudio(pcm)
                    assertTrue(pcm.contentEquals(received.toByteArray()))
                } finally {
                    transport.disconnect()
                    worker.join(3_000)
                }
                workerError.get()?.let { throw it }
            }
        }
    }

    @Test
    fun refusesPlaybackWithoutSpeakerCapability() {        runBlocking {
            ServerSocket(0).use { server ->
                val worker = thread {
                    server.accept().use { client ->
                        val input = client.getInputStream()
                        val output = client.getOutputStream()
                        assertEquals("HELLO|2", readLine(input))
                        output.write("HELLO|2|PHOTO,AUDIO_IN\n".toByteArray())
                        output.flush()
                    }
                }
                val transport = V2GlassTransport()
                try {
                    transport.connect(GlassEndpoint("127.0.0.1", server.localPort))
                    assertEquals(false, transport.supportsAudioOut())
                    try {
                        transport.playAudio(byteArrayOf(0, 1))
                        fail("Expected AUDIO_OUT check to fail")
                    } catch (error: IllegalStateException) {
                        assertEquals("Firmware V2 does not support AUDIO_OUT", error.message)
                    }
                } finally {
                    transport.disconnect()
                    worker.join(3_000)
                }
            }
        }
    }

    @Test
    fun recordsVideoThenDownloadsAvi() {
        runBlocking {
            val avi = ByteArray(5000) { (it % 251).toByte() }
            ServerSocket(0).use { server ->
                val workerError = AtomicReference<Throwable?>()
                val worker = thread {
                    try {
                        server.accept().use { client ->
                            val input = client.getInputStream()
                            val output = client.getOutputStream()
                            assertEquals("HELLO|2", readLine(input))
                            output.write("HELLO|2|PHOTO,AUDIO_IN,AUDIO_OUT,VIDEO\n".toByteArray())
                            output.flush()
                            val start = JSONObject(readLine(input))
                            assertEquals("START_VIDEO", start.getString("name"))
                            sendFrame(output, "response", start.getString("id"), "VIDEO_STARTED",
                                JSONObject().put("recordingId", "rec-1"))
                            val stop = JSONObject(readLine(input))
                            assertEquals("STOP_VIDEO", stop.getString("name"))
                            sendFrame(output, "response", stop.getString("id"), "VIDEO_STOPPED",
                                JSONObject().put("recordingId", "rec-1").put("mediaId", "video-1")
                                    .put("mime", "video/x-msvideo").put("size", avi.size))
                            val media = JSONObject(readLine(input))
                            assertEquals("GET_MEDIA", media.getString("name"))
                            assertEquals("video-1", media.getJSONObject("meta").getString("mediaId"))
                            var offset = 0
                            var seq = 0
                            while (offset < avi.size) {
                                val count = minOf(4096, avi.size - offset)
                                sendFrame(output, "response", media.getString("id"), "MEDIA_CHUNK",
                                    JSONObject().put("mediaId", "video-1").put("seq", seq)
                                        .put("offset", offset).put("totalSize", avi.size),
                                    avi.copyOfRange(offset, offset + count))
                                offset += count
                                seq++
                            }
                            sendFrame(output, "response", media.getString("id"), "MEDIA_END",
                                JSONObject().put("mediaId", "video-1"))
                        }
                    } catch (error: Throwable) {
                        workerError.set(error)
                    }
                }
                val transport = V2GlassTransport()
                try {
                    transport.connect(GlassEndpoint("127.0.0.1", server.localPort))
                    assertTrue(transport.supportsVideo())
                    assertEquals("rec-1", transport.startVideo())
                    val video = transport.stopVideo()
                    assertEquals("video-1", video.mediaId)
                    assertEquals(avi.size, video.size)
                    assertTrue(avi.contentEquals(transport.downloadMedia(video.mediaId, video.size)))
                } finally {
                    transport.disconnect()
                    worker.join(3_000)
                }
                workerError.get()?.let { throw it }
            }
        }
    }

    @Test
    fun refusesVideoWithoutCapability() {
        runBlocking {
            ServerSocket(0).use { server ->
                val worker = thread {
                    server.accept().use { client ->
                        val input = client.getInputStream()
                        val output = client.getOutputStream()
                        assertEquals("HELLO|2", readLine(input))
                        output.write("HELLO|2|PHOTO,AUDIO_IN,AUDIO_OUT\n".toByteArray())
                        output.flush()
                    }
                }
                val transport = V2GlassTransport()
                try {
                    transport.connect(GlassEndpoint("127.0.0.1", server.localPort))
                    assertEquals(false, transport.supportsVideo())
                    try {
                        transport.startVideo()
                        fail("Expected VIDEO check to fail")
                    } catch (error: IllegalStateException) {
                        assertEquals("Firmware V2 does not support VIDEO", error.message)
                    }
                } finally {
                    transport.disconnect()
                    worker.join(3_000)
                }
            }
        }
    }

    private fun readLine(input: InputStream): String {        val bytes = ArrayList<Byte>()
        while (true) {
            val byte = input.read()
            if (byte < 0) error("Client closed early")
            if (byte == '\n'.code) return bytes.toByteArray().toString(Charsets.UTF_8)
            bytes.add(byte.toByte())
        }
    }

    private fun sendFrame(output: OutputStream, type: String, id: String, name: String, meta: JSONObject, payload: ByteArray = byteArrayOf()) {
        val header = JSONObject().put("v", 2).put("type", type).put("id", id).put("name", name)
            .put("payloadBytes", payload.size).put("meta", meta)
        val bytes = (header.toString() + "\n").toByteArray(Charsets.UTF_8)
        output.write(bytes, 0, bytes.size / 2)
        output.flush()
        output.write(bytes, bytes.size / 2, bytes.size - bytes.size / 2)
        output.write(payload)
        output.flush()
    }
}
