package com.rayban.ai.domain.wearable

import com.rayban.ai.domain.model.CameraCapabilities
import com.rayban.ai.domain.model.ImageFrame
import com.rayban.ai.domain.model.MediaAsset
import com.rayban.ai.domain.model.VideoRecordingRequest
import kotlinx.coroutines.flow.StateFlow

interface WearableCamera {
    val capabilities: StateFlow<CameraCapabilities>

    suspend fun captureFrame(): ImageFrame

    suspend fun takePhoto(): MediaAsset

    suspend fun startVideoRecording(request: VideoRecordingRequest): MediaAsset

    fun stopVideoRecording()

    fun cancelActiveRecording()
}