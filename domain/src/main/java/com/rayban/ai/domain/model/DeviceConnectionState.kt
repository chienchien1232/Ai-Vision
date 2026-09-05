package com.rayban.ai.domain.model

sealed interface DeviceConnectionState {
    data object Disconnected : DeviceConnectionState

    data object Connecting : DeviceConnectionState

    data class Connected(val deviceName: String) : DeviceConnectionState

    data class Failed(val reason: String? = null) : DeviceConnectionState
}
