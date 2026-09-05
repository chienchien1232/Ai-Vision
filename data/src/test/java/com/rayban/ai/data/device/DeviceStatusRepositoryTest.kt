package com.rayban.ai.data.device

import com.rayban.ai.domain.model.DeviceConnectionState
import com.rayban.ai.domain.wearable.DeviceSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceStatusRepositoryTest {

    @Test
    fun `defaults to phone camera`() = runTest {
        val session = InMemoryActiveWearableSession()
        val repository = DeviceStatusRepositoryImpl(session)

        assertEquals(
            DeviceConnectionState.Connected("Phone camera"),
            repository.connectionState.first(),
        )
    }

    @Test
    fun `glasses camera selection reports glasses device`() = runTest {
        val session = InMemoryActiveWearableSession()
        val repository = DeviceStatusRepositoryImpl(session)

        session.setCameraSource(DeviceSource.GLASSES)

        assertEquals(
            DeviceConnectionState.Connected("AI Smart Glasses"),
            repository.connectionState.first(),
        )
    }

    @Test
    fun `glasses mic selection reports glasses device`() = runTest {
        val session = InMemoryActiveWearableSession()
        val repository = DeviceStatusRepositoryImpl(session)

        session.setMicSource(DeviceSource.GLASSES)

        assertEquals(
            DeviceConnectionState.Connected("AI Smart Glasses"),
            repository.connectionState.first(),
        )
    }
}
