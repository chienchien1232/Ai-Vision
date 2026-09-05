package com.rayban.ai.domain.wearable

/**
 * Where camera/microphone data comes from.
 *
 * PHONE is the built-in Android hardware (CameraX + SpeechRecognizer).
 * GLASSES is the XIAO ESP32S3 Sense wearable (JPEG frames + PCM audio
 * transported over Wi-Fi, control/button events over BLE).
 *
 * Camera and microphone sources are selected independently so V1 can keep
 * the phone mic while the camera already comes from the glasses (or vice versa).
 */
enum class DeviceSource {
    PHONE,
    GLASSES,
}
