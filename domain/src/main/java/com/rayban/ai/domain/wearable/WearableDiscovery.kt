package com.rayban.ai.domain.wearable

import kotlinx.coroutines.flow.Flow

/**
 * Discovers nearby wearables (BLE scan, mDNS/NSD on Wi-Fi).
 *
 * Implemented later by the ESP32 transport layer. Nothing in the app consumes
 * this yet; it exists so the future discovery UI and connection supervisor have
 * a stable contract to build against.
 */
interface WearableDiscovery {
    /** Currently known targets, updated while scanning. */
    fun targets(): Flow<List<WearableTarget>>

    /** Starts an active scan; safe to call when already scanning. */
    suspend fun startScan()

    /** Stops an active scan; no-op when idle. */
    fun stopScan()
}
