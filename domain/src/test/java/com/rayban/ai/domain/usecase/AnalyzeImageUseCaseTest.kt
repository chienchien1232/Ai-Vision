package com.rayban.ai.domain.usecase

import com.rayban.ai.domain.model.FrameCaptureError
import com.rayban.ai.domain.model.FrameCaptureException
import com.rayban.ai.domain.model.ImageFormat
import com.rayban.ai.domain.model.ImageFrame
import com.rayban.ai.domain.model.ProcessedFrame
import com.rayban.ai.domain.model.VisionError
import com.rayban.ai.domain.model.VisionException
import com.rayban.ai.domain.model.VisionResult
import com.rayban.ai.domain.processor.FramePreprocessor
import com.rayban.ai.domain.processor.FrameProcessor
import com.rayban.ai.domain.repository.ImageSource
import com.rayban.ai.domain.vision.VisionEngine
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzeImageUseCaseTest {

    @Test
    fun `capture and analyze succeed and return frame plus vision result`() = runTest {
        val frame = sampleFrame()
        val source = FakeImageSource(frame = frame)
        val visionEngine = RecordingVisionEngine()
        val useCase = useCase(source = source, visionEngine = visionEngine)

        val result = useCase("What am I looking at?")

        assertTrue(result.isSuccess)
        val analysis = result.getOrThrow()
        assertEquals(frame, analysis.frame)
        assertEquals("This image contains a laptop and desk.", analysis.vision.description)
        assertEquals(frame, visionEngine.lastFrame)
        assertEquals("What am I looking at?", visionEngine.lastQuestion)
    }

    @Test
    fun `preprocessed frame is sent to vision and returned to caller`() = runTest {
        val frame = sampleFrame()
        val source = FakeImageSource(frame = frame)
        val visionEngine = RecordingVisionEngine()
        val preprocessor = RecordingPreprocessor()
        val useCase = AnalyzeImageUseCase(
            capturePreparedFrameUseCase = CapturePreparedFrameUseCase(
                CaptureFrameUseCase(source, FakeProcessor()),
                preprocessor,
            ),
            visionEngine = visionEngine,
        )

        val result = useCase("What am I looking at?")

        assertTrue(result.isSuccess)
        val analysis = result.getOrThrow()
        assertEquals(frame, preprocessor.lastFrame)
        val prepared = preprocessor.prepared
        assertEquals(prepared, visionEngine.lastFrame)
        assertEquals(prepared, analysis.frame)
    }

    @Test
    fun `capture failure is propagated`() = runTest {
        val source = FakeImageSource(error = FrameCaptureError.CaptureFailed)
        val useCase = useCase(source = source)

        val result = useCase("What am I looking at?")

        assertTrue(result.isFailure)
        assertEquals(
            FrameCaptureError.CaptureFailed,
            (result.exceptionOrNull() as FrameCaptureException).error,
        )
    }

    @Test
    fun `vision failure is propagated`() = runTest {
        val source = FakeImageSource(frame = sampleFrame())
        val useCase = useCase(source = source, visionEngine = FailingVisionEngine())

        val result = useCase("Read the text in front of me")

        assertTrue(result.isFailure)
        assertEquals(
            VisionError.Timeout,
            (result.exceptionOrNull() as VisionException).error,
        )
    }

    @Test
    fun `preprocessing failure is propagated`() = runTest {
        val source = FakeImageSource(frame = sampleFrame())
        val useCase = AnalyzeImageUseCase(
            capturePreparedFrameUseCase = CapturePreparedFrameUseCase(
                CaptureFrameUseCase(source, FakeProcessor()),
                FramePreprocessor {
                    throw FrameCaptureException(FrameCaptureError.InvalidFrame, "undecodable")
                },
            ),
            visionEngine = FakeVisionEngine(),
        )

        val result = useCase("What is this?")

        assertTrue(result.isFailure)
        assertEquals(
            FrameCaptureError.InvalidFrame,
            (result.exceptionOrNull() as FrameCaptureException).error,
        )
    }

    @Test
    fun `invalid frame fails capture before calling vision`() = runTest {
        val source = FakeImageSource(frame = sampleFrame())
        val processor = FrameProcessor {
            throw FrameCaptureException(FrameCaptureError.InvalidFrame, "invalid")
        }
        val useCase = AnalyzeImageUseCase(
            capturePreparedFrameUseCase = CapturePreparedFrameUseCase(
                CaptureFrameUseCase(source, processor),
                FramePreprocessor { it },
            ),
            visionEngine = FakeVisionEngine(),
        )

        val result = useCase("What is this?")

        assertTrue(result.isFailure)
        assertEquals(
            FrameCaptureError.InvalidFrame,
            (result.exceptionOrNull() as FrameCaptureException).error,
        )
    }

    @Test
    fun `cancellation during vision propagates instead of returning failure`() = runTest {
        val source = FakeImageSource(frame = sampleFrame())
        val useCase = useCase(source = source, visionEngine = CancellingVisionEngine())

        try {
            useCase("What is this?")
            org.junit.Assert.fail("Expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
    }

    private fun useCase(
        source: ImageSource,
        visionEngine: VisionEngine = FakeVisionEngine(),
        framePreprocessor: FramePreprocessor = FramePreprocessor { it },
    ): AnalyzeImageUseCase = AnalyzeImageUseCase(
        capturePreparedFrameUseCase = CapturePreparedFrameUseCase(
            CaptureFrameUseCase(source, FakeProcessor()),
            framePreprocessor,
        ),
        visionEngine = visionEngine,
    )

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

    private class RecordingVisionEngine : VisionEngine {
        var lastFrame: ImageFrame? = null
        var lastQuestion: String? = null

        override suspend fun analyze(frame: ImageFrame, question: String): VisionResult {
            lastFrame = frame
            lastQuestion = question
            return VisionResult(
                description = "This image contains a laptop and desk.",
                processingTimeMillis = 10L,
            )
        }
    }

    private class RecordingPreprocessor : FramePreprocessor {
        var lastFrame: ImageFrame? = null
        lateinit var prepared: ImageFrame

        override suspend fun prepare(frame: ImageFrame): ImageFrame {
            lastFrame = frame
            prepared = frame.copy(width = frame.width / 2, height = frame.height / 2)
            return prepared
        }
    }

    private class FakeVisionEngine : VisionEngine {
        override suspend fun analyze(frame: ImageFrame, question: String): VisionResult =
            VisionResult(
                description = "This image contains a laptop and desk.",
                processingTimeMillis = 10L,
            )
    }

    private class FailingVisionEngine : VisionEngine {
        override suspend fun analyze(frame: ImageFrame, question: String): VisionResult {
            throw VisionException(VisionError.Timeout, "timed out")
        }
    }

    private class CancellingVisionEngine : VisionEngine {
        override suspend fun analyze(frame: ImageFrame, question: String): VisionResult {
            throw CancellationException("cancelled")
        }
    }
}