package com.example.ai_vision.device

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** In-app workflow demo. Its image is synthetic and is never presented as a glass photo. */
class FakeGlassSession : GlassSession {
    private var connected = true

    override suspend fun ping(): String {
        check(connected) { "Demo device is disconnected" }
        delay(150)
        return "PONG (demo)"
    }

    override suspend fun getStatus(): GlassStatus {
        check(connected) { "Demo device is disconnected" }
        delay(150)
        return GlassStatus(wifiConnected = true, rssi = -45)
    }

    override suspend fun capture(): CapturedImage = withContext(Dispatchers.Default) {
        check(connected) { "Demo device is disconnected" }
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 30f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        canvas.drawText("AI-VISION DEMO", 30f, 100f, paint)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 18f
        canvas.drawText("Synthetic image", 30f, 145f, paint)
        canvas.drawText("Not from glasses", 30f, 175f, paint)
        val bytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output))
            output.toByteArray()
        }
        bitmap.recycle()
        CapturedImage(bytes, 320, 240)
    }

    override fun disconnect() {
        connected = false
    }
}
