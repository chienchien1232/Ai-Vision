package com.rayban.ai.data.device

import com.rayban.ai.domain.wearable.ActiveWearableSession
import com.rayban.ai.domain.wearable.DeviceSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory holder of the active camera/mic source. Defaults to the phone so the
 * app behaves exactly as before until the user (or an ESP32 connection supervisor)
 * switches a source to [DeviceSource.GLASSES].
 */
class InMemoryActiveWearableSession : ActiveWearableSession {

    private val _cameraSource = MutableStateFlow(DeviceSource.PHONE)
    override val cameraSource: StateFlow<DeviceSource> = _cameraSource.asStateFlow()

    private val _micSource = MutableStateFlow(DeviceSource.PHONE)
    override val micSource: StateFlow<DeviceSource> = _micSource.asStateFlow()

    override fun setCameraSource(source: DeviceSource) {
        _cameraSource.value = source
    }

    override fun setMicSource(source: DeviceSource) {
        _micSource.value = source
    }
}
