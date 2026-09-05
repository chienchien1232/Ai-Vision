package com.rayban.ai.core.camera

import com.rayban.ai.domain.model.CameraCapabilities
import com.rayban.ai.domain.model.ImageFormat
import com.rayban.ai.domain.model.ImageFrame
import com.rayban.ai.domain.model.MediaAsset
import com.rayban.ai.domain.model.MediaType
import com.rayban.ai.domain.model.VideoRecordingRequest
import com.rayban.ai.domain.repository.ImageSource
import com.rayban.ai.domain.wearable.ActiveWearableSession
import com.rayban.ai.domain.wearable.DeviceSource
import com.rayban.ai.domain.wearable.WearableCamera
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves the delegating adapters forward to the phone or glasses implementation
 * based on [ActiveWearableSession], so swapping devices never touches the
 * orchestrator, use cases or voice pipeline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveDeviceDelegationTest {

    private val appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        appScope.cancel()
    }

    @Test
    fun `image source defaults to phone`() = runTest {
        val session = TestSession()
        val phone = CountingImageSource()
        val glasses = CountingImageSource()
        val active = ActiveImageSource(session, phone, glasses)

        active.captureFrame()

        assertEquals(1, phone.captureCount)
        assertEquals(0, glasses.captureCount)
    }

    @Test
    fun `image source follows glasses selection`() = runTest {
        val session = TestSession()
        val phone = CountingImageSource("phone")
        val glasses = CountingImageSource("glasses")
        val active = ActiveImageSource(session, phone, glasses)

        session.setCameraSource(DeviceSource.GLASSES)
        val frame = active.captureFrame()

        assertEquals(0, phone.captureCount)
        assertEquals(1, glasses.captureCount)
        assertEquals("glasses", String(frame.data))
    }

    @Test
    fun `wearable camera takePhoto follows active source`() = runTest {
        val session = TestSession()
        val phone = StubWearableCamera("phone")
        val glasses = StubWearableCamera("glasses")
        val active = ActiveWearableCamera(session, phone, glasses, appScope)

        assertEquals("phone-photo", active.takePhoto().uri)

        session.setCameraSource(DeviceSource.GLASSES)
        assertEquals("glasses-photo", active.takePhoto().uri)
        assertEquals(1, phone.photoCount)
        assertEquals(1, glasses.photoCount)
    }

    @Test
    fun `wearable camera capabilities follow active source`() = runTest {
        val session = TestSession()
        val phone = StubWearableCamera(
            "phone",
            CameraCapabilities(supportsVideoRecording = true),
        )
        val glasses = StubWearableCamera(
            "glasses",
            CameraCapabilities(supportsVideoRecording = false),
        )
        val active = ActiveWearableCamera(session, phone, glasses, appScope)

        assertEquals(true, active.capabilities.value.supportsVideoRecording)

        session.setCameraSource(DeviceSource.GLASSES)
        assertEquals(false, active.capabilities.first { !it.supportsVideoRecording }.supportsVideoRecording)
    }

    @Test
    fun `wearable camera preview frames come from active source`() = runTest {
        val session = TestSession()
        val phone = StubWearableCamera("phone")
        val glasses = StubWearableCamera("glasses")
        val active = ActiveWearableCamera(session, phone, glasses, appScope)

        session.setCameraSource(DeviceSource.GLASSES)
        val frame = active.previewFrames.first()

        assertEquals("glasses", String(frame.data))
    }

    @Test
    fun `switching back to phone restores phone routing`() = runTest {
        val session = TestSession()
        val phone = CountingImageSource()
        val glasses = CountingImageSource()
        val active = ActiveImageSource(session, phone, glasses)

        session.setCameraSource(DeviceSource.GLASSES)
        session.setCameraSource(DeviceSource.PHONE)
        active.captureFrame()

        assertEquals(1, phone.captureCount)
        assertEquals(0, glasses.captureCount)
    }

    @Test
    fun `recording stop and cancel stay with owner until completion`() = runTest {
        val session = TestSession()
        val completion = CompletableDeferred<Unit>()
        val phone = StubWearableCamera("phone", recordingCompletion = completion)
        val glasses = StubWearableCamera("glasses")
        val active = ActiveWearableCamera(session, phone, glasses, appScope)
        val recording = async { active.startVideoRecording(VideoRecordingRequest()) }
        runCurrent()

        session.setCameraSource(DeviceSource.GLASSES)
        active.stopVideoRecording()
        active.cancelActiveRecording()
        assertEquals(1, phone.stopCount)
        assertEquals(1, phone.cancelCount)
        assertEquals(0, glasses.stopCount)
        assertEquals(0, glasses.cancelCount)

        completion.complete(Unit)
        assertEquals("phone-video", recording.await().uri)
        active.stopVideoRecording()
        active.cancelActiveRecording()
        assertEquals(1, phone.stopCount)
        assertEquals(1, phone.cancelCount)
        assertEquals(0, glasses.stopCount)
        assertEquals(0, glasses.cancelCount)
        assertEquals("glasses-video", active.startVideoRecording(VideoRecordingRequest()).uri)
    }

    @Test
    fun `overlapping recording is rejected without replacing owner`() = runTest {
        val session = TestSession()
        val phone = StubWearableCamera("phone", recordingCompletion = CompletableDeferred())
        val glasses = StubWearableCamera("glasses")
        val active = ActiveWearableCamera(session, phone, glasses, appScope)
        val recording = async { active.startVideoRecording(VideoRecordingRequest()) }
        runCurrent()
        session.setCameraSource(DeviceSource.GLASSES)

        val failure = runCatching { active.startVideoRecording(VideoRecordingRequest()) }.exceptionOrNull()
        assertEquals(true, failure is IllegalStateException)
        active.stopVideoRecording()
        assertEquals(1, phone.stopCount)
        assertEquals(0, glasses.recordingCount)

        recording.cancelAndJoin()
        assertEquals("glasses-video", active.startVideoRecording(VideoRecordingRequest()).uri)
    }

    @Test
    fun `failed recording releases owner`() = runTest {
        val session = TestSession()
        val completion = CompletableDeferred<Unit>()
        completion.completeExceptionally(IllegalStateException("recording failed"))
        val phone = StubWearableCamera("phone", recordingCompletion = completion)
        val glasses = StubWearableCamera("glasses")
        val active = ActiveWearableCamera(session, phone, glasses, appScope)

        val failure = runCatching { active.startVideoRecording(VideoRecordingRequest()) }.exceptionOrNull()
        assertEquals("recording failed", failure?.message)
        active.stopVideoRecording()
        assertEquals(0, phone.stopCount)
        session.setCameraSource(DeviceSource.GLASSES)
        assertEquals("glasses-video", active.startVideoRecording(VideoRecordingRequest()).uri)
    }

    private class TestSession : ActiveWearableSession {
        private val _camera = MutableStateFlow(DeviceSource.PHONE)
        override val cameraSource: StateFlow<DeviceSource> = _camera.asStateFlow()
        private val _mic = MutableStateFlow(DeviceSource.PHONE)
        override val micSource: StateFlow<DeviceSource> = _mic.asStateFlow()

        override fun setCameraSource(source: DeviceSource) {
            _camera.value = source
        }

        override fun setMicSource(source: DeviceSource) {
            _mic.value = source
        }
    }

    private class CountingImageSource(private val tag: String = "phone") : ImageSource {
        var captureCount = 0
        override suspend fun captureFrame(): ImageFrame {
            captureCount++
            return sampleFrame(tag)
        }
    }

    private class StubWearableCamera(
        private val tag: String,
        capabilities: CameraCapabilities = CameraCapabilities(),
        private val recordingCompletion: CompletableDeferred<Unit>? = null,
    ) : WearableCamera {
        private val _capabilities = MutableStateFlow(capabilities)
        override val capabilities: StateFlow<CameraCapabilities> = _capabilities.asStateFlow()
        override val previewFrames: Flow<ImageFrame> = flowOf(sampleFrame(tag))

        var photoCount = 0
        var recordingCount = 0
        var stopCount = 0
        var cancelCount = 0

        override suspend fun captureFrame(): ImageFrame = sampleFrame(tag)

        override suspend fun takePhoto(): MediaAsset {
            photoCount++
            return MediaAsset(
                uri = "$tag-photo",
                type = MediaType.Photo,
                createdAtMillis = 1_000L,
            )
        }

        override suspend fun startVideoRecording(request: VideoRecordingRequest): MediaAsset {
            recordingCount++
            recordingCompletion?.await()
            return MediaAsset(
                uri = "$tag-video",
                type = MediaType.Video,
                createdAtMillis = 1_000L,
            )
        }

        override fun stopVideoRecording() {
            stopCount++
        }

        override fun cancelActiveRecording() {
            cancelCount++
        }
    }

    private companion object {
        fun sampleFrame(tag: String) = ImageFrame(
            data = tag.toByteArray(),
            width = 32,
            height = 24,
            timestampMillis = 1_000L,
            rotationDegrees = 0,
            format = ImageFormat.JPEG,
        )
    }
}
