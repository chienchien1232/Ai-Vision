package com.team.smartglasses.device

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Hop dong thiet bi chung cho BLE / Wi-Fi / Fake. */
interface DeviceRepository {
    fun deviceState(): Flow<String>
    suspend fun connect(address: String): Boolean
    suspend fun disconnect()
    suspend fun sendCommand(bytes: ByteArray): Boolean
}

class BleDeviceSource : DeviceRepository {
    override fun deviceState(): Flow<String> = flow { emit("disconnected") }
    override suspend fun connect(address: String): Boolean = false // TODO Nguoi 3 + Nguoi 1 protocol
    override suspend fun disconnect() {}
    override suspend fun sendCommand(bytes: ByteArray): Boolean = false
}

class WifiDeviceSource : DeviceRepository {
    override fun deviceState(): Flow<String> = flow { emit("disconnected") }
    override suspend fun connect(address: String): Boolean = false // TODO o moc AI
    override suspend fun disconnect() {}
    override suspend fun sendCommand(bytes: ByteArray): Boolean = false
}

class FakeDeviceSource : DeviceRepository {
    override fun deviceState(): Flow<String> = flow { emit("fake-connected") }
    override suspend fun connect(address: String) = true
    override suspend fun disconnect() {}
    override suspend fun sendCommand(bytes: ByteArray) = true
}
