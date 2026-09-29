package com.example.ai_vision

import com.example.ai_vision.device.GlassCommandException
import com.example.ai_vision.device.GlassEndpoint
import com.example.ai_vision.device.GlassVoiceEvent
import com.example.ai_vision.device.V2GlassTransport
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionRecoveryTest {
    @Test fun transportDeadlineIsAnIoErrorNotUserCancellation() = fixture("PHOTO", { input, _ ->
        request(input, "PING")
        assertEquals(-1, input.read())
    }) { transport ->
        try { transport.ping(); fail("Silent peer did not time out") }
        catch (_: java.io.IOException) { }
    }

    @Test fun manualConfigurationWorksWithoutWakeModel() = fixture("AUDIO_IN", { input, output ->
        val configure = JSONObject(line(input))
        assertEquals("SET_VOICE_CONFIG", configure.getString("name"))
        assertEquals("", configure.getJSONObject("meta").getString("wakeWord"))
        send(output, "response", configure.getString("id"), "VOICE_CONFIGURED")
        pong(input, output)
    }) { transport -> transport.configureVoice("vi-VN", ""); assertEquals("PONG", transport.ping()) }

    @Test fun listeningResponseReturnsTheDeviceSessionId() = fixture("AUDIO_IN", { input, output ->
        send(output, "response", request(input, "START_LISTENING"), "LISTENING_STARTED", JSONObject().put("sessionId", "device-session"))
        pong(input, output)
    }) { transport -> assertEquals("device-session", transport.startListening("vi-VN")); assertEquals("PONG", transport.ping()) }

    @Test fun localPhotoEventPreservesSessionAndDescriptor() = fixture("AUDIO_IN,PHOTO", { input, output ->
        send(output, "event", UUID.randomUUID().toString(), "LOCAL_COMMAND_EXECUTED", JSONObject()
            .put("sessionId", "device-session").put("command", "TAKE_PHOTO").put("result", "SUCCESS")
            .put("mediaId", "photo-1").put("mime", "image/jpeg").put("size", 4).put("width", 320).put("height", 240))
        pong(input, output)
    }) { transport ->
        val event = withTimeout(3000) { transport.voiceEvents.first() } as GlassVoiceEvent.LocalCommand
        assertEquals("device-session", event.sessionId); assertEquals("photo-1", event.photo?.mediaId)
        assertEquals("PONG", transport.ping())
    }

    @Test fun hiEspConfigurationUsesTheTrainedModelAndCannotInventVietnameseWake() = fixture("AUDIO_IN,WAKE_EVENT", { input, output ->
        val configure = JSONObject(line(input))
        assertEquals("SET_VOICE_CONFIG", configure.getString("name"))
        assertEquals("Hi ESP", configure.getJSONObject("meta").getString("wakeWord"))
        assertEquals("vi-VN", configure.getJSONObject("meta").getString("language"))
        send(output, "response", configure.getString("id"), "VOICE_CONFIGURED")
        pong(input, output)
    }) { transport ->
        assertTrue(transport.supportsWake())
        transport.configureVoice("vi-VN", "Hi ESP")
        try { transport.configureVoice("vi-VN", "Kính ơi"); fail("Untrained wake word accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals("PONG", transport.ping())
    }

    @Test fun missingWakeCapabilityDoesNotSendConfigurationOrBreakPing() = fixture("AUDIO_IN", { input, output ->
        pong(input, output)
    }) { transport ->
        assertFalse(transport.supportsWake())
        try { transport.configureVoice("vi-VN", "Hi ESP"); fail("Missing wake capability accepted") }
        catch (_: IllegalStateException) { }
        assertEquals("PONG", transport.ping())
    }

    @Test fun wakePcmArrivesWithoutASecondManualListeningRequest() = fixture("AUDIO_IN,WAKE_EVENT", { input, output ->
        val session = "00d8e840-7e5d-4125-8903-10d2f9a90001"
        send(output, "event", UUID.randomUUID().toString(), "WAKE_WORD_DETECTED",
            JSONObject().put("sessionId", session).put("language", "vi-VN").put("wakeWord", "Hi ESP"))
        send(output, "event", UUID.randomUUID().toString(), "AUDIO_CHUNK", JSONObject().put("sessionId", session)
            .put("seq", 0).put("encoding", "PCM_S16LE").put("sampleRate", 16000).put("channels", 1), byteArrayOf(1, 0, 2, 0))
        send(output, "event", UUID.randomUUID().toString(), "AUDIO_END", JSONObject().put("sessionId", session).put("seq", 1))
        pong(input, output)
    }) { transport ->
        val utterance = withTimeout(3000) { transport.voiceEvents.first() } as GlassVoiceEvent.Utterance
        assertEquals("vi-VN", utterance.languageTag)
        assertArrayEquals(byteArrayOf(1, 0, 2, 0), utterance.pcm)
        assertEquals("PONG", transport.ping())
    }

    @Test fun cancelledVideoStartCancelsRecordingEvenWhenStartedAckArrivesLate() = runBlocking {
        val startArrived = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        fixture("VIDEO,AUDIO_IN", { input, output ->
            val start = request(input, "START_VIDEO")
            startArrived.countDown()
            assertTrue(cancelled.await(3, TimeUnit.SECONDS))
            send(output, "response", start, "VIDEO_STARTED", JSONObject().put("recordingId", "rec-1"))
            val cancel = JSONObject(line(input))
            assertEquals("CANCEL", cancel.getString("name"))
            assertEquals(start, cancel.getJSONObject("meta").getString("targetId"))
            send(output, "response", cancel.getString("id"), "CANCELLED")
            pong(input, output)
        }) { transport ->
            val job = launch { transport.startVideo() }
            assertTrue(withContext(Dispatchers.IO) { startArrived.await(3, TimeUnit.SECONDS) })
            job.cancel()
            cancelled.countDown()
            job.join()
            assertEquals("PONG", transport.ping())
        }
    }

    @Test fun busyCommandDoesNotPoisonFollowingPing() = fixture("PHOTO", { input, output ->
        val photo = request(input, "TAKE_PHOTO")
        send(output, "error", photo, "ERROR", JSONObject().put("code", "CAMERA_BUSY"))
        pong(input, output)
    }) { transport ->
        try { transport.capture(); fail("Expected camera busy") }
        catch (error: GlassCommandException) { assertEquals("CAMERA_BUSY", error.code) }
        assertEquals("PONG", transport.ping())
    }

    @Test fun deviceErrorIsRecoverableWithoutClosingSocket() = fixture("AUDIO_IN", { input, output ->
        val listen = request(input, "START_LISTENING")
        val session = UUID.randomUUID().toString()
        send(output, "response", listen, "LISTENING_STARTED", JSONObject().put("sessionId", session))
        send(output, "event", UUID.randomUUID().toString(), "DEVICE_ERROR", JSONObject().put("sessionId", session).put("code", "MIC_NO_DATA"))
        pong(input, output)
    }) { transport ->
        transport.startListening("vi-VN")
        val event = withTimeout(3000) { transport.voiceEvents.first() } as GlassVoiceEvent.DeviceError
        assertEquals("MIC_NO_DATA", event.code)
        assertEquals("PONG", transport.ping())
    }

    @Test fun autoFinishedVideoIsReportedWithoutDisconnect() = fixture("VIDEO", { input, output ->
        send(output, "event", UUID.randomUUID().toString(), "MEDIA_AVAILABLE", JSONObject()
            .put("mediaId", "video-1").put("mime", "video/x-msvideo").put("size", 1024))
        pong(input, output)
    }) { transport ->
        val event = withTimeout(3000) { transport.voiceEvents.first() } as GlassVoiceEvent.MediaAvailable
        assertEquals("video-1", event.mediaId)
        assertEquals("PONG", transport.ping())
    }

    @Test fun cancellationDrainsChunkAckThenCancelsTheOriginalPlayback() = runBlocking {
        val chunkArrived = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        fixture("AUDIO_IN,AUDIO_OUT", { input, output ->
            val play = request(input, "PLAY_AUDIO")
            val session = UUID.randomUUID().toString()
            send(output, "response", play, "AUDIO_READY", JSONObject().put("sessionId", session))
            val chunk = JSONObject(line(input))
            assertEquals("AUDIO_OUT_CHUNK", chunk.getString("name"))
            val count = chunk.getInt("payloadBytes")
            assertEquals(count, input.readNBytes(count).size)
            chunkArrived.countDown()
            assertTrue(cancelled.await(3, TimeUnit.SECONDS))
            send(output, "response", chunk.getString("id"), "AUDIO_OUT_ACK", JSONObject().put("sessionId", session).put("seq", 0))
            val cancel = JSONObject(line(input))
            assertEquals("CANCEL", cancel.getString("name"))
            assertEquals(play, cancel.getJSONObject("meta").getString("targetId"))
            send(output, "response", cancel.getString("id"), "CANCELLED")
            pong(input, output)
        }) { transport ->
            val job = launch { transport.playAudio(ByteArray(20000)) }
            assertTrue(withContext(Dispatchers.IO) { chunkArrived.await(3, TimeUnit.SECONDS) })
            job.cancel()
            cancelled.countDown()
            job.join()
            assertEquals("PONG", transport.ping())
        }
    }

    private fun fixture(capabilities: String, serve: (InputStream, OutputStream) -> Unit, test: suspend (V2GlassTransport) -> Unit) = runBlocking {
        ServerSocket(0).use { server ->
            val error = AtomicReference<Throwable?>()
            val worker = thread(isDaemon = true) {
                try { server.accept().use { client ->
                    client.soTimeout = 5000
                    val input = client.getInputStream()
                    val output = client.getOutputStream()
                    assertEquals("HELLO|2", line(input))
                    output.write("HELLO|2|$capabilities\n".toByteArray()); output.flush()
                    serve(input, output)
                } } catch (failure: Throwable) { error.set(failure) }
            }
            val transport = V2GlassTransport()
            try { transport.connect(GlassEndpoint("127.0.0.1", server.localPort)); test(transport) }
            finally { transport.disconnect(); worker.join(5500) }
            error.get()?.let { throw it }
            assertFalse("Server worker did not finish", worker.isAlive)
        }
    }
    private fun request(input: InputStream, name: String): String = JSONObject(line(input)).also { assertEquals(name, it.getString("name")) }.getString("id")
    private fun pong(input: InputStream, output: OutputStream) = send(output, "response", request(input, "PING"), "PONG")
    private fun line(input: InputStream): String = buildString {
        while (true) { val byte = input.read(); check(byte >= 0); if (byte == 10) break; append(byte.toChar()) }
    }
    private fun send(output: OutputStream, type: String, id: String, name: String, meta: JSONObject = JSONObject(), payload: ByteArray = byteArrayOf()) {
        output.write((JSONObject().put("v", 2).put("type", type).put("id", id).put("name", name)
            .put("payloadBytes", payload.size).put("meta", meta).toString() + "\n").toByteArray())
        output.write(payload); output.flush()
    }
}
