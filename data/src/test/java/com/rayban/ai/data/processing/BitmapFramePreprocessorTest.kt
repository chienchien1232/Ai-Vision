package com.rayban.ai.data.processing

import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import com.rayban.ai.domain.model.FrameCaptureError
import com.rayban.ai.domain.model.FrameCaptureException
import com.rayban.ai.domain.model.ImageFormat
import com.rayban.ai.domain.model.ImageFrame
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BitmapFramePreprocessorTest {

    @Test
    fun `large frame is resized to max dimension and re-encoded`() = runTest {
        val jpeg = encodeJpeg(2400, 1800)
        val frame = ImageFrame(
            data = jpeg,
            width = 2400,
            height = 1800,
            timestampMillis = 1L,
            rotationDegrees = 0,
            format = ImageFormat.JPEG,
        )

        val result = BitmapFramePreprocessor().prepare(frame)

        assertEquals(1280, maxOf(result.width, result.height))
        assertTrue(result.width <= 1280)
        assertTrue(result.height <= 1280)
        assertEquals(0, result.rotationDegrees)
        assertEquals(frame.timestampMillis, result.timestampMillis)
        assertTrue(result.data.size < frame.data.size)
    }

    @Test
    fun `small frame is not upscaled and keeps dimensions`() = runTest {
        val jpeg = encodeJpeg(320, 240)
        val frame = ImageFrame(
            data = jpeg,
            width = 320,
            height = 240,
            timestampMillis = 1L,
            rotationDegrees = 0,
            format = ImageFormat.JPEG,
        )

        val result = BitmapFramePreprocessor().prepare(frame)

        assertEquals(320, result.width)
        assertEquals(240, result.height)
    }

    @Test
    fun `rotation is baked into the output frame`() = runTest {
        val jpeg = encodeJpeg(100, 200)
        val frame = ImageFrame(
            data = jpeg,
            width = 100,
            height = 200,
            timestampMillis = 1L,
            rotationDegrees = 90,
            format = ImageFormat.JPEG,
        )

        val result = BitmapFramePreprocessor(maxDimension = 2048).prepare(frame)

        assertEquals(200, result.width)
        assertEquals(100, result.height)
        assertEquals(0, result.rotationDegrees)
    }

    @Test
    fun `empty frame throws InvalidFrame`() = runTest {
        val frame = ImageFrame(
            data = ByteArray(0),
            width = 8,
            height = 8,
            timestampMillis = 1L,
            rotationDegrees = 0,
            format = ImageFormat.JPEG,
        )

        try {
            BitmapFramePreprocessor().prepare(frame)
            fail("Expected FrameCaptureException")
        } catch (e: FrameCaptureException) {
            assertEquals(FrameCaptureError.InvalidFrame, e.error)
        }
    }

    private fun encodeJpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        bitmap.compress(CompressFormat.JPEG, 95, output)
        bitmap.recycle()
        return output.toByteArray()
    }
}