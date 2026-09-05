package com.rayban.ai.data.device

import com.rayban.ai.domain.model.DeviceConnectionState
import com.rayban.ai.domain.repository.DeviceStatusRepository
import com.rayban.ai.domain.wearable.ActiveWearableSession
import com.rayban.ai.domain.wearable.DeviceSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Reports which capture source the assistant is currently using.
 *
 * V1: reflects the [ActiveWearableSession] selection (phone hardware vs glasses).
 * When the ESP32 transport lands, the connection supervisor will drive richer
 * states (Connecting/Failed) from here without touching consumers.
 */
class DeviceStatusRepositoryImpl(
    private val session: ActiveWearableSession,
) : DeviceStatusRepository {

    override val connectionState: Flow<DeviceConnectionState> = combine(
        session.cameraSource,
        session.micSource,
    ) { camera, mic ->
        if (camera == DeviceSource.GLASSES || mic == DeviceSource.GLASSES) {
            DeviceConnectionState.Connected(GLASSES_DEVICE_NAME)
        } else {
            DeviceConnectionState.Connected(PHONE_DEVICE_NAME)
        }
    }

    private companion object {
        const val PHONE_DEVICE_NAME = "Phone camera"
        const val GLASSES_DEVICE_NAME = "AI Smart Glasses"
    }
}
