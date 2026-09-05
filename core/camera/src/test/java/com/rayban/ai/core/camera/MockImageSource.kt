package com.rayban.ai.core.camera

import android.util.Log
import com.rayban.ai.domain.model.ImageFormat
import com.rayban.ai.domain.model.ImageFrame
import com.rayban.ai.domain.repository.ImageSource

class MockImageSource : ImageSource {

    override suspend fun captureFrame(): ImageFrame {
        Log.d(TAG, "[Camera] Mock source capture requested")
        val width = 640
        val height = 480
        val timestamp = System.currentTimeMillis()

        val header = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE0.toByte(),
            0x00.toByte(), 0x10.toByte(),
            0x4A.toByte(), 0x46.toByte(), 0x49.toByte(), 0x46.toByte(), 0x00.toByte(),
            0x01.toByte(),
        )
        val payload = ByteArray(1024) { (it % 256).toByte() }
        val data = header + payload

        Log.d(TAG, "[Camera] Mock frame generated size=${width}x${height} timestamp=${timestamp}")
        return ImageFrame(
            data = data,
            width = width,
            height = height,
            timestampMillis = timestamp,
            rotationDegrees = 0,
            format = ImageFormat.JPEG,
        )
    }

    companion object {
        private const val TAG = "MockImageSource"
    }
}
