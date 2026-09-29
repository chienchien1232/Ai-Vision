package com.example.ai_vision.device

import java.util.Base64
import java.util.UUID

data class GlassStatus(
    val wifiConnected: Boolean,
    val rssi: Int? = null,
    val cameraReady: Boolean? = null,
    val micReady: Boolean? = null,
    val speakerReady: Boolean? = null
)
/** BLE provisioning and shared media limits; TCP data frames use V2. */
object GlassProtocol {
    const val BLE_NAME = "Ai-Vision Glasses"
    const val TCP_PORT = 5000
    const val MAX_JPEG_BYTES = 5 * 1024 * 1024

    val BLE_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val BLE_WRITE_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val BLE_NOTIFY_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    val BLE_CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun wifiCredentialsLine(ssid: String, password: String): ByteArray {
        require(ssid.isNotBlank() && password.isNotBlank()) { "Wi-Fi credentials are empty" }
        val encoder = Base64.getUrlEncoder()
        val encodedSsid = encoder.encodeToString(ssid.toByteArray(Charsets.UTF_8))
        val encodedPassword = encoder.encodeToString(password.toByteArray(Charsets.UTF_8))
        return "WIFI|$encodedSsid|$encodedPassword\n".toByteArray(Charsets.UTF_8)
    }

    fun parseWifiConnected(raw: String): GlassEndpoint? {
        val fields = raw.trimEnd('\r', '\n').split('|')
        if (fields.size != 3 || fields[0] != "WIFI_CONNECTED") return null
        val octets = fields[1].split('.')
        if (octets.size != 4 || octets.any { (it.toIntOrNull() ?: -1) !in 0..255 }) return null
        if (fields[2].toIntOrNull() != TCP_PORT) return null
        return GlassEndpoint(fields[1], TCP_PORT)
    }

}
