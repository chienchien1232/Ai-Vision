package com.rayban.ai.core.camera

import android.util.Log
import com.rayban.ai.domain.model.ImageFrame
import com.rayban.ai.domain.repository.ImageSource
import com.rayban.ai.domain.wearable.ActiveWearableSession
import com.rayban.ai.domain.wearable.DeviceSource

/**
 * [ImageSource] bound in DI. Forwards AI frame captures to the phone camera or
 * the glasses camera depending on [ActiveWearableSession.cameraSource].
 *
 * Constructed explicitly in the app DI module (no @Inject here on purpose: the
 * constructor takes interface types so unit tests can pass fakes, while Hilt
 * wires the real phone/glasses implementations).
 */
class ActiveImageSource(
    private val session: ActiveWearableSession,
    private val phone: ImageSource,
    private val glasses: ImageSource,
) : ImageSource {

    override suspend fun captureFrame(): ImageFrame {
        val source = session.cameraSource.value
        Log.d(TAG, "[Camera] captureFrame via $source")
        return active().captureFrame()
    }

    private fun active(): ImageSource = when (session.cameraSource.value) {
        DeviceSource.GLASSES -> glasses
        DeviceSource.PHONE -> phone
    }

    private companion object {
        const val TAG = "ActiveImageSource"
    }
}
