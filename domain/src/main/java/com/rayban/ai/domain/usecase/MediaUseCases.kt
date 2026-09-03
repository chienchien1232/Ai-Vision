package com.rayban.ai.domain.usecase

import com.rayban.ai.domain.model.MediaAsset
import com.rayban.ai.domain.wearable.WearableCamera
import kotlin.coroutines.cancellation.CancellationException

class TakePhotoUseCase(
    private val camera: WearableCamera,
) {
    suspend operator fun invoke(): Result<MediaAsset> = try {
        Result.success(camera.takePhoto())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}

class StartVideoRecordingUseCase(
    private val camera: WearableCamera,
) {
    suspend operator fun invoke(
        maxDurationMillis: Long = DEFAULT_MAX_DURATION_MILLIS,
    ): Result<MediaAsset> = try {
        Result.success(
            camera.startVideoRecording(
                com.rayban.ai.domain.model.VideoRecordingRequest(
                    withAudio = true,
                    maxDurationMillis = maxDurationMillis,
                ),
            ),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    companion object {
        const val DEFAULT_MAX_DURATION_MILLIS =
            com.rayban.ai.domain.model.VideoRecordingRequest.DEFAULT_MAX_DURATION_MILLIS
    }
}

class StopVideoRecordingUseCase(
    private val camera: WearableCamera,
) {
    operator fun invoke() {
        camera.stopVideoRecording()
    }
}