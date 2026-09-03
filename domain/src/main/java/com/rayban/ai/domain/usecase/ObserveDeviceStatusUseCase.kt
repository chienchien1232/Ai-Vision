package com.rayban.ai.domain.usecase

import com.rayban.ai.domain.model.DeviceConnectionState
import com.rayban.ai.domain.repository.DeviceStatusRepository
import kotlinx.coroutines.flow.Flow

class ObserveDeviceStatusUseCase(
    private val repository: DeviceStatusRepository,
) {
    operator fun invoke(): Flow<DeviceConnectionState> = repository.connectionState
}
