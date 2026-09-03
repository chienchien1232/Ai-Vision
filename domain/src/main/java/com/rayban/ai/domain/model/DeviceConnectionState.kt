package com.rayban.ai.domain.model

sealed interface DeviceConnectionState {
    data object Disconnected : DeviceConnectionState

    data class Connected(val deviceName: String) : DeviceConnectionState
}
