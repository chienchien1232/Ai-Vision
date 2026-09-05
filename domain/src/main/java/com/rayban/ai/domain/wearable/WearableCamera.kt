package com.rayban.ai.domain.wearable

import com.rayban.ai.domain.model.CameraCapabilities
import com.rayban.ai.domain.model.ImageFrame
import com.rayban.ai.domain.model.MediaAsset
import com.rayban.ai.domain.model.VideoRecordingRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

interface WearableCamera {
    val capabilities: StateFlow<CameraCapabilities>

    /**
     * Continuous preview frames from the device (used by remote cameras such as
     * the ESP32 glasses streaming JPEG over Wi-Fi). Phone cameras render through
     * their own preview surface and leave this empty.
     */
    val previewFrames: Flow<ImageFrame>
        get() = emptyFlow()

    suspend fun captureFrame(): ImageFrame

    suspend fun takePhoto(): MediaAsset

    suspend fun startVideoRecording(request: VideoRecordingRequest): MediaAsset

    fun stopVideoRecording()

    fun cancelActiveRecording()
}