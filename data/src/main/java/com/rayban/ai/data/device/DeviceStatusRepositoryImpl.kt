package com.rayban.ai.data.device

import com.rayban.ai.domain.model.DeviceConnectionState
import com.rayban.ai.domain.repository.DeviceStatusRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class DeviceStatusRepositoryImpl : DeviceStatusRepository {
    private val state = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Disconnected)

    override val connectionState: Flow<DeviceConnectionState> = state.asStateFlow()
}
