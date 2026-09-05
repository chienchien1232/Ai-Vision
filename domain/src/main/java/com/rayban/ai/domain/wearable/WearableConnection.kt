package com.rayban.ai.domain.wearable

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface WearableConnection {
    val state: StateFlow<WearableConnectionState>
    val events: Flow<WearableEvent>

    suspend fun connect(target: WearableTarget)
    suspend fun disconnect()
    suspend fun execute(command: WearableCommand): WearableCommandResult
}

data class WearableTarget(
    val address: String,
    val name: String,
    val transport: WearableTransport = WearableTransport.WIFI,
)

enum class WearableTransport {
    BLE,
    WIFI,
    USB,
}

sealed interface WearableConnectionState {
    data object Disconnected : WearableConnectionState
    data object Connecting : WearableConnectionState
    data class Connected(
        val deviceName: String,
        val transport: WearableTransport,
    ) : WearableConnectionState
    data class Failed(val reason: String) : WearableConnectionState
}

sealed interface WearableEvent {
    data object LinkLost : WearableEvent
    data class DeviceLog(val message: String) : WearableEvent

    /**
     * Physical button on the glasses was pressed (e.g. shutter / assistant key).
     * The app maps this to a voice-pipeline entry point such as "describe the scene".
     */
    data class ButtonPressed(
        val buttonId: Int,
        val pressedAtMillis: Long,
    ) : WearableEvent
}