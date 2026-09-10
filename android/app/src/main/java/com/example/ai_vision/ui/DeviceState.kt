package com.example.ai_vision.ui



sealed class DeviceState{
    data object Disconnected:DeviceState()
    data object Connected:DeviceState()
    data object Connecting:DeviceState()
    data class Error(val message: String): DeviceState()
}
