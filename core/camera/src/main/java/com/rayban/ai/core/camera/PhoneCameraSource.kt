package com.rayban.ai.core.camera

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class PhoneCameraSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appScope: CoroutineScope,
    private val gallery: GalleryMediaStore,
) : ImageSource, CameraPreviewController, WearableCamera {

    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var preview: Preview? = null
    private var closed = false
    private val captureMutex = Mutex()

    private val _capabilities = MutableStateFlow(
        CameraCapabilities(
            supportsStillCapture = true,
            supportsPersistentPhoto = true,
            supportsVideoRecording = false,
            supportsAudioRecording = hasRecordAudioPermission(),
            maxVideoDurationMillis = VideoRecordingRequest.DEFAULT_MAX_DURATION_MILLIS,
        ),
    )
    override val capabilities: StateFlow<CameraCapabilities> = _capabilities.asStateFlow()

    private var activeRecording: Recording? = null
    private var recordingTimeoutJob: Job? = null
    private val recordingMutex = Mutex()

    override fun bind(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        closed = false
        Log.d(TAG, "[Camera] Source binding preview")
        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener({
            if (closed) {
                Log.d(TAG, "[Camera] Bind cancelled - source closed")
                return@addListener
            }
            try {
                val provider = providerFuture.get()
                val previewUseCase = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val captureUseCase = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val videoUseCase = buildVideoCapture()

                detachBoundUseCases(provider)
                try {
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        previewUseCase,
                        captureUseCase,
                        videoUseCase,
                    )
                    videoCapture = videoUseCase
                    _capabilities.value = _capabilities.value.copy(supportsVideoRecording = true)
                } catch (e: Exception) {
                    Log.w(TAG, "[Camera] Video binding failed, falling back to preview+photo", e)
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        previewUseCase,
                        captureUseCase,
                    )
                    videoCapture = null
                    _capabilities.value = _capabilities.value.copy(supportsVideoRecording = false)
                }
                cameraProvider = provider
                preview = previewUseCase
                imageCapture = captureUseCase
                Log.d(TAG, "[Camera] Source initialized")
            } catch (e: Exception) {
                Log.e(TAG, "[Camera] Source initialization failed", e)
                imageCapture = null
                videoCapture = null
            }
        }, mainExecutor)
    }

    override fun unbind() {
        closed = true
        try {
            detachBoundUseCases(cameraProvider)
            Log.d(TAG, "[Camera] Source released")
        } catch (e: Exception) {
            Log.e(TAG, "[Camera] Source release failed", e)
        } finally {
            cameraProvider = null
            imageCapture = null
            videoCapture = null
            preview = null
        }
    }

    private fun detachBoundUseCases(provider: ProcessCameraProvider?) {
        val boundPreview = preview
        val boundCapture = imageCapture
        val boundVideo = videoCapture
        if (provider == null || (boundPreview == null && boundCapture == null && boundVideo == null)) {
            return
        }
        val useCases = listOfNotNull(boundPreview, boundCapture, boundVideo).toTypedArray()
        try {
            provider.unbind(*useCases)
        } catch (e: Exception) {
            Log.w(TAG, "[Camera] Unbind of owned use cases failed", e)
        }
    }

    private fun buildVideoCapture(): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.HD,
                    androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                ),
            )
            .build()
        return VideoCapture.withOutput(recorder)
    }

    // ------------------------------------------------------------------
    // ImageSource: in-memory frame for AI analysis
    // ------------------------------------------------------------------

    override suspend fun captureFrame(): ImageFrame = captureMutex.withLock {
        val capture = imageCapture
            ?: throw FrameCaptureException(
                FrameCaptureError.CameraUnavailable,
                "Camera is not ready",
            )

        if (closed) {
            throw FrameCaptureException(
                FrameCaptureError.SourceDisconnected,
                "Camera source is closed",
            )
        }

        Log.d(TAG, "[Camera] Frame capture requested")

        val file = withContext(Dispatchers.IO) {
            captureToFile(capture)
        } ?: throw FrameCaptureException(
            FrameCaptureError.CaptureFailed,
            "Failed to capture image",
        )

        return@withLock withContext(Dispatchers.Default) {
            try {
                val bytes = file.readBytes()
                if (bytes.isEmpty()) {
                    throw FrameCaptureException(
                        FrameCaptureError.InvalidFrame,
                        "Captured frame is empty",
                    )
                }

                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                val width = options.outWidth
                val height = options.outHeight

                if (width <= 0 || height <= 0) {
                    throw FrameCaptureException(
                        FrameCaptureError.InvalidFrame,
                        "Invalid frame dimensions",
                    )
                }

                val rotation = when (capture.targetRotation) {
                    Surface.ROTATION_90 -> 90
                    Surface.ROTATION_180 -> 180
                    Surface.ROTATION_270 -> 270
                    else -> 0
                }

                val timestamp = System.currentTimeMillis()
                Log.d(TAG, "[Camera] Frame captured size=${width}x${height} rotation=${rotation} timestamp=${timestamp} bytes=${bytes.size}")

                ImageFrame(
                    data = bytes,
                    width = width,
                    height = height,
                    timestampMillis = timestamp,
                    rotationDegrees = rotation,
                    format = ImageFormat.JPEG,
                )
            } catch (e: FrameCaptureException) {
                throw e
            } catch (e: Exception) {
                throw FrameCaptureException(
                    FrameCaptureError.ProcessingFailed,
                    "Failed to process frame",
                    e,
                )
            } finally {
                try {
                    if (file.exists()) file.delete()
                } catch (_: Exception) {
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // WearableCamera: persistent photo
    // ------------------------------------------------------------------

    override suspend fun takePhoto(): MediaAsset = captureMutex.withLock {
        val capture = imageCapture
            ?: throw MediaCaptureException(MediaCaptureError.CameraUnavailable, "Camera is not ready")
        if (closed) {
            throw MediaCaptureException(MediaCaptureError.CameraUnavailable, "Camera source is closed")
        }

        val timestamp = System.currentTimeMillis()
        Log.d(TAG, "[Camera] Photo capture requested")

        val file = withContext(Dispatchers.IO) { captureToFile(capture) }
            ?: throw MediaCaptureException(MediaCaptureError.CaptureFailed, "Failed to capture photo")

        try {
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            val uri = withContext(Dispatchers.IO) { gallery.savePhoto(bytes, timestamp) }
            Log.d(TAG, "[Camera] Photo saved uri=$uri")
            MediaAsset(
                uri = uri.toString(),
                type = MediaType.Photo,
                createdAtMillis = timestamp,
            )
        } finally {
            try {
                file.delete()
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------
    // WearableCamera: video (suspends until finalized)
    // ------------------------------------------------------------------

    @android.annotation.SuppressLint("MissingPermission")
    override suspend fun startVideoRecording(request: VideoRecordingRequest): MediaAsset {
        val videoCapture = videoCapture
            ?: throw MediaCaptureException(MediaCaptureError.CameraUnavailable, "Video capture is not ready")
        if (closed) {
            throw MediaCaptureException(MediaCaptureError.CameraUnavailable, "Camera source is closed")
        }
        if (request.withAudio && !hasRecordAudioPermission()) {
            throw MediaCaptureException(MediaCaptureError.AudioUnavailable, "Microphone permission missing")
        }

        return recordingMutex.withLock {
            if (activeRecording != null) {
                throw MediaCaptureException(MediaCaptureError.AlreadyRecording, "Already recording")
            }

            val timestamp = System.currentTimeMillis()
            val output = MediaStoreOutputOptions.Builder(
                appContext.contentResolver,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            )
                .setContentValues(
                    ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "glass_video_$timestamp.mp4")
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Video.Media.RELATIVE_PATH, VIDEO_RELATIVE_PATH)
                            put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                    },
                )
                .build()

            val finalizeSignal = kotlinx.coroutines.CompletableDeferred<VideoRecordEvent.Finalize>()

            val pending = videoCapture.output
                .prepareRecording(appContext, output)
                .apply {
                    if (request.withAudio) {
                        try {
                            withAudioEnabled()
                        } catch (e: Exception) {
                            throw MediaCaptureException(MediaCaptureError.AudioUnavailable, "Audio unavailable", e)
                        }
                    }
                }

            val recording = pending.start(mainExecutor) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    finalizeSignal.complete(event)
                }
            }
            activeRecording = recording
            Log.d(TAG, "[Camera] Recording started max=${request.maxDurationMillis}ms audio=${request.withAudio}")

            recordingTimeoutJob = appScope.launch {
                delay(request.maxDurationMillis)
                Log.d(TAG, "[Camera] Recording auto-stopped by max duration")
                try {
                    recording.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "[Camera] Auto-stop failed", e)
                }
            }

            try {
                val finalize = finalizeSignal.await()
                if (finalize.hasError()) {
                    throw MediaCaptureException(
                        MediaCaptureError.FinalizeFailed,
                        "Recording failed code=${finalize.error}",
                    )
                }
                val uri = finalize.outputResults.outputUri
                Log.d(TAG, "[Camera] Recording finalized uri=$uri")
                MediaAsset(
                    uri = uri.toString(),
                    type = MediaType.Video,
                    createdAtMillis = timestamp,
                    durationMillis = finalize.recordingStats.recordedDurationNanos / 1_000_000,
                )
            } finally {
                recordingTimeoutJob?.cancel()
                recordingTimeoutJob = null
                activeRecording = null
            }
        }
    }

    override fun stopVideoRecording() {
        Log.d(TAG, "[Camera] Stop recording requested")
        try {
            activeRecording?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "[Camera] Stop recording failed", e)
        }
    }

    override fun cancelActiveRecording() {
        Log.d(TAG, "[Camera] Cancel recording requested")
        try {
            activeRecording?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "[Camera] Cancel recording failed", e)
        }
    }

    // ------------------------------------------------------------------
    // Shared capture helpers
    // ------------------------------------------------------------------

    private suspend fun captureToFile(capture: ImageCapture): File? =
        suspendCancellableCoroutine { continuation ->
            val file = try {
                File.createTempFile("vision_capture_", ".jpg", appContext.cacheDir)
            } catch (e: Exception) {
                Log.e(TAG, "[Camera] Failed to create temp file", e)
                if (continuation.isActive) continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val output = ImageCapture.OutputFileOptions.Builder(file).build()

            capture.takePicture(
                output,
                mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        Log.d(TAG, "[Camera] Frame captured to file")
                        if (continuation.isActive) {
                            continuation.resume(file)
                        } else {
                            try {
                                file.delete()
                            } catch (_: Exception) {
                            }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "[Camera] Capture failed", exception)
                        try {
                            file.delete()
                        } catch (_: Exception) {
                        }
                        if (continuation.isActive) continuation.resume(null)
                    }
                },
            )

            continuation.invokeOnCancellation {
                try {
                    file.delete()
                } catch (_: Exception) {
                }
            }
        }

    private fun hasRecordAudioPermission(): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        android.Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "PhoneCameraSource"
        private const val VIDEO_RELATIVE_PATH = "Movies/AI Smart Glasses"
    }
}