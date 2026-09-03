package com.rayban.ai.data.vision

import com.rayban.ai.domain.model.ImageFormat
import com.rayban.ai.domain.model.ImageFrame
import com.rayban.ai.domain.model.VisionError
import com.rayban.ai.domain.model.VisionException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MockVisionEngineTest {

    @Test
    fun `valid frame returns mock description with objects and confidence`() = runTest {
        val engine = MockVisionEngine()

        val result = engine.analyze(sampleFrame(), "What am I looking at?")

        assertEquals("This image contains a laptop and desk.", result.description)
        assertEquals(listOf("laptop", "desk"), result.objects.map { it.label })
        assertTrue(result.objects.all { it.confidence in 0f..1f })
        assertEquals(0.92f, result.confidence ?: 0f, 0.001f)
        assertTrue(result.detectedText.isEmpty())
        assertTrue(result.processingTimeMillis >= 0)
    }

    @Test
    fun `invalid frame throws InvalidImage`() = runTest {
        val engine = MockVisionEngine()
        val frame = ImageFrame(
            data = ByteArray(0),
            width = 0,
            height = 0,
            timestampMillis = 1L,
            rotationDegrees = 0,
            format = ImageFormat.JPEG,
        )

        try {
            engine.analyze(frame, "What am I looking at?")
            fail("Expected VisionException for invalid frame")
        } catch (e: VisionException) {
            assertEquals(VisionError.InvalidImage, e.error)
        }
    }

    private fun sampleFrame() = ImageFrame(
        data = ByteArray(16) { it.toByte() },
        width = 640,
        height = 480,
        timestampMillis = 1000L,
        rotationDegrees = 0,
        format = ImageFormat.JPEG,
    )
}