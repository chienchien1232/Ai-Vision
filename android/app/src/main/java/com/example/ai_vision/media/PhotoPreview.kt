package com.example.ai_vision.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.ai_vision.device.GlassProtocol

/** Validate real JPEG dimensions, not just metadata advertised by the peer. */
internal fun previewSampleSize(width: Int, height: Int): Int {
    require(width in 1..4096 && height in 1..4096 && width.toLong() * height <= 8_000_000) {
        "JPEG dimensions exceed preview limit"
    }
    var sample = 1
    while (maxOf(width, height) / sample > 1024) sample *= 2
    return sample
}

fun decodePhotoPreview(jpeg: ByteArray): Bitmap? {
    if (jpeg.size !in 4..GlassProtocol.MAX_JPEG_BYTES) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
    val sample = try { previewSampleSize(bounds.outWidth, bounds.outHeight) }
        catch (_: IllegalArgumentException) { return null }
    return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    })
}
