package com.example.ai_vision.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class HotspotCredentials(val ssid: String, val password: String)

/** Owns the local-only hotspot reservation for one connection session. */
class LocalHotspot(context: Context) {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    suspend fun start(): HotspotCredentials = suspendCancellableCoroutine { continuation ->
        checkPermission()
        wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(newReservation: WifiManager.LocalOnlyHotspotReservation) {
                if (!continuation.isActive) {
                    newReservation.close()
                    return
                }
                reservation = newReservation
                try {
                    val credentials = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val config = newReservation.softApConfiguration
                        HotspotCredentials(config.ssid.orEmpty(), config.passphrase.orEmpty())
                    } else {
                        @Suppress("DEPRECATION")
                        val config = newReservation.wifiConfiguration
                            ?: error("Hotspot credentials unavailable")
                        HotspotCredentials(config.SSID, config.preSharedKey.orEmpty())
                    }
                    require(credentials.ssid.isNotBlank() && credentials.password.isNotBlank()) {
                        "Hotspot credentials unavailable"
                    }
                    continuation.resume(credentials)
                } catch (error: Exception) {
                    close()
                    continuation.resumeWithException(error)
                }
            }

            override fun onFailed(reason: Int) {
                if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("Cannot start hotspot ($reason)"))
                }
            }

            override fun onStopped() {
                reservation = null
            }
        }, Handler(Looper.getMainLooper()))

        continuation.invokeOnCancellation { close() }
    }

    fun close() {
        reservation?.close()
        reservation = null
    }

    private fun checkPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (appContext.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Missing $permission")
        }
    }
}
