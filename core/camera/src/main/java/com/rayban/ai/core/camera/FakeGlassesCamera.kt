package com.rayban.ai.core.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import com.rayban.ai.domain.model.CameraCapabilities
import com.rayban.ai.domain.model.FrameCaptureError
import com.rayban.ai.domain.model.FrameCaptureException
import com.rayban.ai.domain.model.ImageFormat
import com.rayban.ai.domain.model.ImageFrame
import com.rayban.ai.domain.model.MediaAsset
import com.rayban.ai.domain.model.MediaCaptureError
import com.rayban.ai.domain.model.MediaCaptureException
import com.rayban.ai.domain.model.MediaType
import com.rayban.ai.domain.model.VideoRecordingRequest
import com.rayban.ai.domain.repository.ImageSource
import com.rayban.ai.domain.wearable.WearableCamera
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Stand-in for the future XIAO ESP32S3 Sense camera.
 *
 * Generates a synthetic but fully decodable JPEG frame (the same shape an ESP32
 * JPEG-over-Wi-Fi adapter will produce) and persists photos through the shared
 * [GalleryMediaStore]. Selecting [com.rayban.ai.domain.wearable.DeviceSource.GLASSES]
 * in the session routes every camera use case here with zero changes to the
 * orchestrator, use cases, voice ViewModel or UI logic.
 *
 * Replaced 1:1 by `Esp32WearableCamera` once firmware exists.
 */
@Singleton
class FakeGlassesCamera @Inject constructor(
    private val gallery: GalleryMediaStore,
) : ImageSource, WearableCamera {

    private val _capabilities = MutableStateFlow(
        CameraCapabilities(
            supportsStillCapture = true,
            supportsPersistentPhoto = true,
            supportsVideoRecording = false,
            supportsAudioRecording = false,
            maxVideoDurationMillis = VideoRecordingRequest.DEFAULT_MAX_DURATION_MILLIS,
        ),
    )
    override val capabilities: StateFlow<CameraCapabilities> = _capabilities.asStateFlow()

    override val previewFrames: Flow<ImageFrame> = flow {
        emit(generateFrame())
    }

    override suspend fun captureFrame(): ImageFrame = withContext(Dispatchers.Default) {
        Log.d(TAG, "[Glasses] Frame capture requested")
        generateFrame()
    }

    override suspend fun takePhoto(): MediaAsset {
        val frame = captureFrame()
        val uri = withContext(Dispatchers.IO) {
            gallery.savePhoto(frame.data, frame.timestampMillis)
        }
        Log.d(TAG, "[Glasses] Photo saved uri=$uri")
        return MediaAsset(
            uri = uri.toString(),
            type = MediaType.Photo,
            createdAtMillis = frame.timestampMillis,
        )
    }

    override suspend fun startVideoRecording(request: VideoRecordingRequest): MediaAsset {
        throw MediaCaptureException(
            MediaCaptureError.CameraUnavailable,
            "Glasses video recording is not supported in V1",
        )
    }

    override fun stopVideoRecording() = Unit

    override fun cancelActiveRecording() = Unit

    private fun generateFrame(): ImageFrame {
        val width = 320
        val height = 240
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(18, 24, 38))
            val paint = Paint().apply { style = Paint.Style.FILL }
            paint.color = Color.rgb(64, 120, 200)
            canvas.drawRect(24f, 24f, (width - 24).toFloat(), (height - 24).toFloat(), paint)
            paint.color = Color.rgb(240, 200, 80)
            canvas.drawCircle(width / 2f, height / 2f, 52f, paint)
            paint.color = Color.WHITE
            paint.textSize = 28f
            canvas.drawText("GLASSES", 36f, height - 36f, paint)

            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) {
                throw FrameCaptureException(FrameCaptureError.ProcessingFailed, "Failed to encode glasses frame")
            }
            val bytes = output.toByteArray()
            if (bytes.isEmpty()) {
                throw FrameCaptureException(FrameCaptureError.InvalidFrame, "Glasses frame is empty")
            }
            return ImageFrame(
                data = bytes,
                width = width,
                height = height,
                timestampMillis = System.currentTimeMillis(),
                rotationDegrees = 0,
                format = ImageFormat.JPEG,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val TAG = "FakeGlassesCamera"
    }
}
