package com.rayban.ai.data.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import com.rayban.ai.domain.model.FrameCaptureError
import com.rayban.ai.domain.model.FrameCaptureException
import com.rayban.ai.domain.model.ImageFormat
import com.rayban.ai.domain.model.ImageFrame
import com.rayban.ai.domain.processor.FramePreprocessor
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BitmapFramePreprocessor(
    private val maxDimension: Int = DEFAULT_MAX_DIMENSION,
    private val jpegQuality: Int = DEFAULT_JPEG_QUALITY,
) : FramePreprocessor {

    override suspend fun prepare(frame: ImageFrame): ImageFrame = withContext(Dispatchers.Default) {
        if (frame.data.isEmpty()) {
            throw FrameCaptureException(
                FrameCaptureError.InvalidFrame,
                "Frame is empty",
            )
        }
        val start = System.currentTimeMillis()
        val source = BitmapFactory.decodeByteArray(frame.data, 0, frame.data.size)
            ?: throw FrameCaptureException(
                FrameCaptureError.InvalidFrame,
                "Frame could not be decoded",
            )
        val bitmaps = mutableListOf(source)
        try {
            var bitmap = source
            val rotation = frame.rotationDegrees % 360
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(
                    source,
                    0,
                    0,
                    source.width,
                    source.height,
                    matrix,
                    true,
                )
                bitmaps += rotated
                bitmap = rotated
            }

            val longest = maxOf(bitmap.width, bitmap.height)
            if (longest > maxDimension) {
                val scale = maxDimension.toFloat() / longest
                val resized = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
                bitmaps += resized
                bitmap = resized
            }

            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)
            val result = ImageFrame(
                data = output.toByteArray(),
                width = bitmap.width,
                height = bitmap.height,
                timestampMillis = frame.timestampMillis,
                rotationDegrees = 0,
                format = ImageFormat.JPEG,
            )
            Log.d(
                TAG,
                "[Preprocess] ${source.width}x${source.height} (${frame.data.size / 1024}KB, " +
                    "rot=$rotation) -> ${result.width}x${result.height} (${result.data.size / 1024}KB) " +
                    "in ${System.currentTimeMillis() - start}ms",
            )
            result
        } finally {
            bitmaps.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    private companion object {
        const val TAG = "BitmapFramePreprocessor"
        const val DEFAULT_MAX_DIMENSION = 1280
        const val DEFAULT_JPEG_QUALITY = 80
    }
}