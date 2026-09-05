package com.rayban.ai.core.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.lifecycle.ViewModel
import com.rayban.ai.domain.wearable.ActiveWearableSession
import com.rayban.ai.domain.wearable.DeviceSource
import com.rayban.ai.domain.wearable.WearableCamera
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

@HiltViewModel
class PreviewViewModel @Inject constructor(
    val controller: CameraPreviewController,
    private val session: ActiveWearableSession,
    private val wearableCamera: WearableCamera,
) : ViewModel() {

    val cameraSource: StateFlow<DeviceSource> = session.cameraSource
    val micSource: StateFlow<DeviceSource> = session.micSource
    val glassesFrames: Flow<Bitmap?> = wearableCamera.previewFrames
        .conflate()
        .map { frame ->
            try {
                val decoded = BitmapFactory.decodeByteArray(frame.data, 0, frame.data.size)
                if (decoded == null || frame.rotationDegrees % 360 == 0) {
                    decoded
                } else {
                    Bitmap.createBitmap(
                        decoded, 0, 0, decoded.width, decoded.height,
                        Matrix().apply { postRotate(frame.rotationDegrees.toFloat()) },
                        true,
                    )
                }
            } catch (_: Exception) {
                null
            }
        }
        .flowOn(Dispatchers.Default)
        .conflate()

    fun selectCameraSource(source: DeviceSource) {
        session.setCameraSource(source)
    }

    fun selectMicSource(source: DeviceSource) {
        session.setMicSource(source)
    }
}
