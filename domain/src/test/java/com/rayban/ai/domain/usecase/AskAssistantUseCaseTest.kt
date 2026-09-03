package com.rayban.ai.domain.usecase

import com.rayban.ai.domain.assistant.AssistantEngine
import com.rayban.ai.domain.context.ConversationContext
import com.rayban.ai.domain.model.AssistantContextSnapshot
import com.rayban.ai.domain.model.AssistantRequest
import com.rayban.ai.domain.model.AssistantResponse
import com.rayban.ai.domain.model.AssistantTask
import com.rayban.ai.domain.model.ConversationTurn
import com.rayban.ai.domain.model.FrameCaptureError
import com.rayban.ai.domain.model.FrameCaptureException
import com.rayban.ai.domain.model.FramePolicy
import com.rayban.ai.domain.model.ImageFormat
import com.rayban.ai.domain.model.ImageFrame
import com.rayban.ai.domain.model.ProcessedFrame
import com.rayban.ai.domain.model.VisionError
import com.rayban.ai.domain.model.VisionException
import com.rayban.ai.domain.processor.FramePreprocessor
import com.rayban.ai.domain.processor.FrameProcessor
import com.rayban.ai.domain.repository.ImageSource
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskAssistantUseCaseTest {

    private companion object {
        const val FIXED_NOW = 1_000L
        const val STALE_NOW = 60_000L
    }

    @Test
    fun `first ask captures a frame and records a turn`() = runTest {
        val source = CountingSource(sampleFrame())
        val engine = RecordingAssistantEngine()
        val context = FakeConversationContext()
        val useCase = useCase(source, engine, context)

        val result = useCase("What is this?")

        assertTrue(result.isSuccess)
        assertEquals(1, source.captureCount)
        assertEquals(1, context.turns.size)
        assertEquals(0, engine.lastRequest!!.recentTurns.size)
        assertEquals(1, result.getOrThrow().turns.size)
    }

    @Test
    fun `follow-up reuses the cached frame without capturing again`() = runTest {
        val source = CountingSource(sampleFrame())
        val engine = RecordingAssistantEngine()
        val context = FakeConversationContext()
        val useCase = useCase(source, engine, context)

        useCase("What is this?")
        val second = useCase("What model is it?")

        assertTrue(second.isSuccess)
        assertEquals(1, source.captureCount)
        assertEquals(2, engine.requests.size)
        assertEquals(
            engine.requests[0].frame,
            engine.lastRequest!!.frame,
        )
        assertEquals(1, engine.lastRequest!!.recentTurns.size)
        assertEquals("What is this?", engine.lastRequest!!.recentTurns[0].question)
    }

    @Test
    fun `stale cached frame is recaptured after ttl`() = runTest {
        val source = CountingSource(sampleFrame())
        val engine = RecordingAssistantEngine()
        val context = FakeConversationContext()
        val useCase = useCase(source, engine, context, nowMillis = { STALE_NOW })

        useCase("What is this?")
        useCase("What model is it?")

        assertEquals(2, source.captureCount)
    }

    @Test
    fun `fresh policy recaptures on every visual command`() = runTest {
        val source = CountingSource(sampleFrame())
        val engine = RecordingAssistantEngine()
        val context = FakeConversationContext()
        val useCase = useCase(source, engine, context)

        useCase("Describe the scene", AssistantTask.DescribeScene, FramePolicy.Fresh)
        useCase("Describe the scene again", AssistantTask.DescribeScene, FramePolicy.Fresh)

        assertEquals(2, source.captureCount)
    }

    @Test
    fun `read text task caches the transcription as ocr`() = runTest {
        val source = CountingSource(sampleFrame())
        val engine = RecordingAssistantEngine()
        val context = FakeConversationContext()
        val useCase = useCase(source, engine, context)

        val result = useCase("Read the text", AssistantTask.ReadText, FramePolicy.Fresh)

        assertTrue(result.isSuccess)
        assertEquals("answer: Read the text", context.snapshot().latestOcrText)
    }

    @Test
    fun `history passed to engine preserves successful turn order`() = runTest {
        val source = CountingSource(sampleFrame())
        val engine = RecordingAssistantEngine()
        val context = FakeConversationContext()
        val useCase = useCase(source, engine, context)

        useCase("What is this?")
        useCase("What color is it?")
        useCase("What brand?")

        assertEquals(3, engine.requests.size)
        val history = engine.lastRequest!!.recentTurns
        assertEquals(listOf("What is this?", "What color is it?"), history.map { it.question })
        assertEquals(listOf("answer: What is this?", "answer: What color is it?"), history.map { it.answer })
        assertEquals(3, context.turns.size)
    }

    @Test
    fun `ai failure keeps the frame but records no turn`() = runTest {
        val source = CountingSource(sampleFrame())
        val engine = FlakyAssistantEngine()
        val context = FakeConversationContext()
        val useCase = useCase(source, engine, context)

        val first = useCase("What is this?")
        assertTrue(first.isFailure)
        assertEquals(0, context.turns.size)
        assertTrue(context.snapshot().latestFrame != null)

        val second = useCase("What is this?")
        assertTrue(second.isSuccess)
        assertEquals(1, source.captureCount)
        assertEquals(0, engine.lastRequest!!.recentTurns.size)
        assertEquals(1, context.turns.size)
    }

    @Test
    fun `capture failure propagates and caches nothing`() = runTest {
        val source = FailingSource()
        val engine = RecordingAssistantEngine()
        val context = FakeConversationContext()
        val useCase = useCase(source, engine, context)

        val result = useCase("What is this?")

        assertTrue(result.isFailure)
        assertEquals(FrameCaptureError.CaptureFailed, (result.exceptionOrNull() as FrameCaptureException).error)
        assertNull(context.snapshot().latestFrame)
        assertEquals(0, engine.requests.size)
    }

    @Test
    fun `blank question fails before capture or engine call`() = runTest {
        val source = CountingSource(sampleFrame())
        val engine = RecordingAssistantEngine()
        val useCase = useCase(source, engine, FakeConversationContext())

        val result = useCase("   ")

        assertTrue(result.isFailure)
        assertEquals(0, source.captureCount)
        assertEquals(0, engine.requests.size)
    }

    @Test
    fun `change scene invalidates the frame and isolates turns`() = runTest {
        val source = CountingSource(sampleFrame())
        val engine = RecordingAssistantEngine()
        val context = FakeConversationContext()
        val useCase = useCase(source, engine, context)

        useCase("What is this?")
        useCase.changeScene()
        val third = useCase("What color is it?")

        assertTrue(third.isSuccess)
        assertEquals(2, source.captureCount)
        assertEquals(0, engine.lastRequest!!.recentTurns.size)
        assertEquals(1, context.snapshot().recentTurns.size)
        assertEquals("What color is it?", context.snapshot().recentTurns[0].question)
    }

    @Test
    fun `new conversation clears frame and turns`() = runTest {
        val source = CountingSource(sampleFrame())
        val engine = RecordingAssistantEngine()
        val context = FakeConversationContext()
        val useCase = useCase(source, engine, context)

        useCase("What is this?")
        useCase.newConversation()
        val second = useCase("What is that?")

        assertTrue(second.isSuccess)
        assertEquals(2, source.captureCount)
        assertEquals(0, engine.lastRequest!!.recentTurns.size)
        assertEquals(1, context.turns.size)
    }

    @Test
    fun `cancellation propagates and does not record a turn`() = runTest {
        val source = CountingSource(sampleFrame())
        val engine = CancellingAssistantEngine()
        val context = FakeConversationContext()
        val useCase = useCase(source, engine, context)

        try {
            useCase("What is this?")
            org.junit.Assert.fail("Expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
        assertEquals(0, context.turns.size)
    }

    @Test
    fun `concurrent asks serialize and share a single capture`() = runTest {
        val source = CountingSource(sampleFrame())
        val engine = SlowAssistantEngine()
        val context = FakeConversationContext()
        val useCase = useCase(source, engine, context)

        val results = listOf(
            async { useCase("What is this?") },
            async { useCase("What model?") },
        ).awaitAll()

        assertTrue(results.all { it.isSuccess })
        assertEquals(1, source.captureCount)
        assertEquals(2, context.turns.size)
        assertEquals(2, engine.requests.size)
    }

    private fun useCase(
        source: ImageSource,
        engine: AssistantEngine,
        context: ConversationContext,
        nowMillis: () -> Long = { FIXED_NOW },
    ): AskAssistantUseCase {
        val prepare = CapturePreparedFrameUseCase(
            CaptureFrameUseCase(source, FrameProcessor { ProcessedFrame(it, it.timestampMillis + 1) }),
            FramePreprocessor { it },
        )
        return AskAssistantUseCase(prepare, engine, context, nowMillis)
    }

    private fun sampleFrame() = ImageFrame(
        data = ByteArray(16) { it.toByte() },
        width = 640,
        height = 480,
        timestampMillis = 1000L,
        rotationDegrees = 0,
        format = ImageFormat.JPEG,
    )

    private class CountingSource(private val frame: ImageFrame) : ImageSource {
        var captureCount = 0
        override suspend fun captureFrame(): ImageFrame {
            captureCount++
            return frame
        }
    }

    private class FailingSource : ImageSource {
        override suspend fun captureFrame(): ImageFrame {
            throw FrameCaptureException(FrameCaptureError.CaptureFailed, "no camera")
        }
    }

    private class RecordingAssistantEngine : AssistantEngine {
        val requests = mutableListOf<AssistantRequest>()
        val lastRequest: AssistantRequest? get() = requests.lastOrNull()

        override suspend fun respond(request: AssistantRequest): AssistantResponse {
            requests += request
            return AssistantResponse("answer: ${request.question}", 10L)
        }
    }

    private class FlakyAssistantEngine : AssistantEngine {
        var calls = 0
        val requests = mutableListOf<AssistantRequest>()
        val lastRequest: AssistantRequest? get() = requests.lastOrNull()

        override suspend fun respond(request: AssistantRequest): AssistantResponse {
            calls++
            requests += request
            if (calls == 1) {
                throw VisionException(VisionError.NetworkError, "no network")
            }
            return AssistantResponse("answer: ${request.question}", 10L)
        }
    }

    private class CancellingAssistantEngine : AssistantEngine {
        override suspend fun respond(request: AssistantRequest): AssistantResponse {
            throw CancellationException("cancelled")
        }
    }

    private class SlowAssistantEngine : AssistantEngine {
        val requests = mutableListOf<AssistantRequest>()
        override suspend fun respond(request: AssistantRequest): AssistantResponse {
            requests += request
            delay(20)
            return AssistantResponse("answer: ${request.question}", 20L)
        }
    }

    private class FakeConversationContext : ConversationContext {
        private var sceneId = 0L
        private var frame: ImageFrame? = null
        private var ocrText: String? = null
        val turns = mutableListOf<ConversationTurn>()

        override suspend fun snapshot(): AssistantContextSnapshot = AssistantContextSnapshot(
            sceneId = sceneId,
            latestFrame = frame,
            latestOcrText = ocrText,
            recentTurns = turns.filter { it.sceneId == sceneId },
        )

        override suspend fun cacheFrame(frame: ImageFrame) {
            this.frame = frame
            ocrText = null
        }

        override suspend fun cacheOcrText(text: String?) {
            ocrText = text
        }

        override suspend fun recordTurn(question: String, answer: String, timestampMillis: Long) {
            turns += ConversationTurn(question, answer, sceneId, timestampMillis)
        }

        override suspend fun changeScene() {
            sceneId += 1
            frame = null
            ocrText = null
        }

        override suspend fun clear() {
            sceneId += 1
            frame = null
            ocrText = null
            turns.clear()
        }
    }
}