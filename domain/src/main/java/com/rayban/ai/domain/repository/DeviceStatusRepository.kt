package com.rayban.ai.domain.repository

import com.rayban.ai.domain.model.DeviceConnectionState
import kotlinx.coroutines.flow.Flow

interface DeviceStatusRepository {
    val connectionState: Flow<DeviceConnectionState>
}
