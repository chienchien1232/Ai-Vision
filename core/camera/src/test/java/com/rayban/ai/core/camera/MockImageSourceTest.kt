package com.rayban.ai.core.camera

import com.rayban.ai.domain.model.ImageFormat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockImageSourceTest {

    @Test
    fun `mock source returns valid frame`() = runTest {
        val source = MockImageSource()

        val frame = source.captureFrame()

        assertTrue(frame.data.isNotEmpty())
        assertTrue(frame.width > 0)
        assertTrue(frame.height > 0)
        assertTrue(frame.timestampMillis > 0)
        assertEquals(ImageFormat.JPEG, frame.format)
        assertEquals(0, frame.rotationDegrees)
    }

    @Test
    fun `mock source returns consistent dimensions`() = runTest {
        val source = MockImageSource()

        val first = source.captureFrame()
        val second = source.captureFrame()

        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        assertEquals(first.format, second.format)
        assertTrue(second.timestampMillis >= first.timestampMillis)
    }
}
