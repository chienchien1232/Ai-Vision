package com.rayban.ai.data.processing

import com.rayban.ai.domain.model.FrameCaptureError
import com.rayban.ai.domain.model.FrameCaptureException
import com.rayban.ai.domain.model.ImageFormat
import com.rayban.ai.domain.model.ImageFrame
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatingFrameProcessorTest {

    private val processor = ValidatingFrameProcessor()

    @Test
    fun `valid frame passes validation`() = runTest {
        val frame = ImageFrame(
            data = ByteArray(32) { 1 },
            width = 640,
            height = 480,
            timestampMillis = 1000L,
            rotationDegrees = 0,
            format = ImageFormat.JPEG,
        )

        val result = processor.process(frame)

        assertEquals(frame, result.image)
        assertTrue(result.processedAtMillis >= 1000L)
    }

    @Test
    fun `empty data is rejected`() = runTest {
        val frame = ImageFrame(
            data = ByteArray(0),
            width = 640,
            height = 480,
            timestampMillis = 1000L,
            rotationDegrees = 0,
            format = ImageFormat.JPEG,
        )

        try {
            processor.process(frame)
            assertTrue("Expected FrameCaptureException", false)
        } catch (e: FrameCaptureException) {
            assertEquals(FrameCaptureError.InvalidFrame, e.error)
        }
    }

    @Test
    fun `zero dimensions are rejected`() = runTest {
        val frame = ImageFrame(
            data = ByteArray(16) { 1 },
            width = 0,
            height = 0,
            timestampMillis = 1000L,
            rotationDegrees = 0,
            format = ImageFormat.JPEG,
        )

        try {
            processor.process(frame)
            assertTrue("Expected FrameCaptureException", false)
        } catch (e: FrameCaptureException) {
            assertEquals(FrameCaptureError.InvalidFrame, e.error)
        }
    }
}
