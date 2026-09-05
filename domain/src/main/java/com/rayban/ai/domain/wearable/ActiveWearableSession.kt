package com.rayban.ai.domain.wearable

import kotlinx.coroutines.flow.StateFlow

/**
 * Runtime selector for the active camera/microphone implementations.
 *
 * The DI graph always binds the delegating adapters ([com.rayban.ai.domain.repository.ImageSource],
 * [WearableCamera], [com.rayban.ai.domain.audio.SpeechToText]) which read these flows and forward
 * to the phone or glasses implementation. Swapping devices therefore never touches
 * the orchestrator, use cases, voice ViewModel or UI logic.
 */
interface ActiveWearableSession {
    val cameraSource: StateFlow<DeviceSource>
    val micSource: StateFlow<DeviceSource>

    fun setCameraSource(source: DeviceSource)
    fun setMicSource(source: DeviceSource)
}
