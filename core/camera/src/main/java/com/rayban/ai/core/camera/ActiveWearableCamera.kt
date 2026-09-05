package com.rayban.ai.core.camera

import android.util.Log
import com.rayban.ai.domain.model.CameraCapabilities
import com.rayban.ai.domain.model.ImageFrame
import com.rayban.ai.domain.model.MediaAsset
import com.rayban.ai.domain.model.VideoRecordingRequest
import com.rayban.ai.domain.wearable.ActiveWearableSession
import com.rayban.ai.domain.wearable.DeviceSource
import com.rayban.ai.domain.wearable.WearableCamera
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * [WearableCamera] bound in DI. Forwards photo/video/capture calls to the phone
 * camera or the glasses camera depending on [ActiveWearableSession.cameraSource].
 *
 * Constructed explicitly in the app DI module (see [ActiveImageSource] for why
 * there is no @Inject annotation here).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveWearableCamera(
    private val session: ActiveWearableSession,
    private val phone: WearableCamera,
    private val glasses: WearableCamera,
    appScope: CoroutineScope,
) : WearableCamera {

    private val recordingOwner = AtomicReference<WearableCamera?>(null)

    override val capabilities: StateFlow<CameraCapabilities> = combine(
        session.cameraSource,
        phone.capabilities,
        glasses.capabilities,
    ) { source, phoneCapabilities, glassesCapabilities ->
        if (source == DeviceSource.GLASSES) glassesCapabilities else phoneCapabilities
    }.stateIn(appScope, SharingStarted.Eagerly, phone.capabilities.value)

    override val previewFrames: Flow<ImageFrame> = session.cameraSource.flatMapLatest { source ->
        active(source).previewFrames
    }

    override suspend fun captureFrame(): ImageFrame {
        val source = session.cameraSource.value
        Log.d(TAG, "[Camera] captureFrame via $source")
        return active(source).captureFrame()
    }

    override suspend fun takePhoto(): MediaAsset {
        val source = session.cameraSource.value
        Log.d(TAG, "[Camera] takePhoto via $source")
        return active(source).takePhoto()
    }

    override suspend fun startVideoRecording(request: VideoRecordingRequest): MediaAsset {
        val source = session.cameraSource.value
        Log.d(TAG, "[Camera] startVideoRecording via $source")
        val owner = active(source)
        check(recordingOwner.compareAndSet(null, owner)) { "A recording is already active" }
        return try {
            owner.startVideoRecording(request)
        } finally {
            recordingOwner.compareAndSet(owner, null)
        }
    }

    override fun stopVideoRecording() {
        recordingOwner.get()?.stopVideoRecording()
    }

    override fun cancelActiveRecording() {
        recordingOwner.get()?.cancelActiveRecording()
    }

    private fun active(source: DeviceSource): WearableCamera = when (source) {
        DeviceSource.GLASSES -> glasses
        DeviceSource.PHONE -> phone
    }

    private companion object {
        const val TAG = "ActiveWearableCamera"
    }
}
