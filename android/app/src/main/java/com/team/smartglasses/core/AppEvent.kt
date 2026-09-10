package com.team.smartglasses.core

/** Su kien tu thiet bi / AI / nguoi dung. */
sealed interface AppEvent {
    data class DeviceConnected(val name: String) : AppEvent
    data class DeviceDisconnected(val reason: String) : AppEvent
    data class FrameReceived(val sizeBytes: Int) : AppEvent
    data class Error(val message: String) : AppEvent
}
