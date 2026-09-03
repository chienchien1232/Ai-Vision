package com.rayban.ai.domain.usecase

import com.rayban.ai.domain.model.FrameCaptureError
import com.rayban.ai.domain.model.FrameCaptureException
import com.rayban.ai.domain.model.ImageFormat
import com.rayban.ai.domain.model.ImageFrame
import com.rayban.ai.domain.model.ProcessedFrame
import com.rayban.ai.domain.processor.FrameProcessor
import com.rayban.ai.domain.repository.ImageSource
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureFrameUseCaseTest {

    @Test
    fun `capture succeeds and returns processed frame`() = runTest {
        val frame = sampleFrame()
        val source = FakeImageSource(frame = frame)
        val processor = FakeProcessor()
        val useCase = CaptureFrameUseCase(source, processor)

        val result = useCase()

        assertTrue(result.isSuccess)
        val processed = result.getOrThrow()
        assertEquals(frame, processed.image)
        assertTrue(processed.processedAtMillis >= frame.timestampMillis)
    }

    @Test
    fun `capture failure propagates source error`() = runTest {
        val source = FakeImageSource(error = FrameCaptureError.CaptureFailed)
        val processor = FakeProcessor()
        val useCase = CaptureFrameUseCase(source, processor)

        val result = useCase()

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull() as FrameCaptureException
        assertEquals(FrameCaptureError.CaptureFailed, exception.error)
    }

    @Test
    fun `processing failure is propagated`() = runTest {
        val frame = sampleFrame()
        val source = FakeImageSource(frame = frame)
        val processor = FrameProcessor { throw FrameCaptureException(FrameCaptureError.InvalidFrame) }
        val useCase = CaptureFrameUseCase(source, processor)

        val result = useCase()

        assertTrue(result.isFailure)
        assertEquals(
            FrameCaptureError.InvalidFrame,
            (result.exceptionOrNull() as FrameCaptureException).error,
        )
    }

    @Test
    fun `cancellation from source propagates instead of returning failure`() = runTest {
        val source = ImageSource { throw CancellationException("cancelled") }
        val useCase = CaptureFrameUseCase(source) { frame -> ProcessedFrame(frame, 1L) }

        try {
            useCase()
            org.junit.Assert.fail("Expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
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

    private class FakeImageSource(
        private val frame: ImageFrame? = null,
        private val error: FrameCaptureError? = null,
    ) : ImageSource {
        override suspend fun captureFrame(): ImageFrame {
            error?.let { throw FrameCaptureException(it) }
            return frame ?: error("frame required")
        }
    }

    private class FakeProcessor : FrameProcessor {
        override suspend fun process(frame: ImageFrame): ProcessedFrame =
            ProcessedFrame(frame, frame.timestampMillis + 1)
    }
}
