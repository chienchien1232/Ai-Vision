package com.rayban.ai.core.voice

import com.rayban.ai.domain.assistant.AssistantEngine
import com.rayban.ai.domain.audio.SpeakerEvent
import com.rayban.ai.domain.audio.SpeechError
import com.rayban.ai.domain.audio.SpeechEvent
import com.rayban.ai.domain.audio.SpeechToText
import com.rayban.ai.domain.audio.TextSpeaker
import com.rayban.ai.domain.context.ConversationContext
import com.rayban.ai.domain.model.AssistantContextSnapshot
import com.rayban.ai.domain.model.AssistantRequest
import com.rayban.ai.domain.model.AssistantResponse
import com.rayban.ai.domain.model.CameraCapabilities
import com.rayban.ai.domain.model.ConversationTurn
import com.rayban.ai.domain.model.ImageFormat
import com.rayban.ai.domain.model.ImageFrame
import com.rayban.ai.domain.model.MediaAsset
import com.rayban.ai.domain.model.MediaType
import com.rayban.ai.domain.model.ProcessedFrame
import com.rayban.ai.domain.model.VideoRecordingRequest
import com.rayban.ai.domain.model.VisionError
import com.rayban.ai.domain.model.VisionException
import com.rayban.ai.domain.orchestrator.AiOrchestrator
import com.rayban.ai.domain.orchestrator.RuleBasedCommandRouter
import com.rayban.ai.domain.processor.FramePreprocessor
import com.rayban.ai.domain.processor.FrameProcessor
import com.rayban.ai.domain.repository.ImageSource
import com.rayban.ai.domain.usecase.AskAssistantUseCase
import com.rayban.ai.domain.usecase.CaptureFrameUseCase
import com.rayban.ai.domain.usecase.CapturePreparedFrameUseCase
import com.rayban.ai.domain.usecase.StartVideoRecordingUseCase
import com.rayban.ai.domain.usecase.StopVideoRecordingUseCase
import com.rayban.ai.domain.usecase.TakePhotoUseCase
import com.rayban.ai.domain.wearable.WearableCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class VoiceAssistantViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `mic click starts listening`() = runTest {
        val speech = FakeSpeechToText()
        val viewModel = buildViewModel(speechToText = speech)

        viewModel.onMicClick()

        assertEquals(AssistantPhase.Listening, viewModel.uiState.value.phase)
        assertEquals(1, speech.startCount)
    }

    @Test
    fun `final result analyzes and speaks answer`() = runTest {
        val speech = FakeSpeechToText()
        val speaker = FakeTextSpeaker()
        val engine = RecordingAssistantEngine()
        val viewModel = buildViewModel(speechToText = speech, textSpeaker = speaker, assistantEngine = engine)

        viewModel.onMicClick()
        speech.emit(SpeechEvent.FinalResult("What am I looking at?"))

        assertEquals(AssistantPhase.Speaking, viewModel.uiState.value.phase)
        assertEquals(listOf("This image contains a laptop and desk."), speaker.spokenTexts)
        assertEquals(
            com.rayban.ai.domain.model.AssistantTask.DescribeScene,
            engine.lastTask,
        )
    }

    @Test
    fun `tts finished transitions to idle with recorded turn`() = runTest {
        val speech = FakeSpeechToText()
        val speaker = FakeTextSpeaker()
        val viewModel = buildViewModel(speechToText = speech, textSpeaker = speaker)

        viewModel.onMicClick()
        speech.emit(SpeechEvent.FinalResult("What am I looking at?"))
        speaker.emit(SpeakerEvent.Finished)

        val state = viewModel.uiState.value
        assertEquals(AssistantPhase.Idle, state.phase)
        assertEquals(1, state.turns.size)
        assertEquals("What am I looking at?", state.turns[0].question)
        assertEquals("This image contains a laptop and desk.", state.turns[0].answer)
    }

    @Test
    fun `tts error keeps the text answer`() = runTest {
        val speech = FakeSpeechToText()
        val speaker = FakeTextSpeaker()
        val viewModel = buildViewModel(speechToText = speech, textSpeaker = speaker)

        viewModel.onMicClick()
        speech.emit(SpeechEvent.FinalResult("What am I looking at?"))
        speaker.emit(SpeakerEvent.Error("tts broke"))

        val state = viewModel.uiState.value
        assertEquals(AssistantPhase.Idle, state.phase)
        assertEquals("This image contains a laptop and desk.", state.turns[0].answer)
    }

    @Test
    fun `blank final result shows NoMatch error`() = runTest {
        val speech = FakeSpeechToText()
        val viewModel = buildViewModel(speechToText = speech)

        viewModel.onMicClick()
        speech.emit(SpeechEvent.FinalResult("   "))

        val state = viewModel.uiState.value
        assertEquals(AssistantPhase.Idle, state.phase)
        assertEquals(VoiceError.NoMatch, state.error)
    }

    @Test
    fun `speech no match error surfaces in state`() = runTest {
        val speech = FakeSpeechToText()
        val viewModel = buildViewModel(speechToText = speech)

        viewModel.onMicClick()
        speech.emit(SpeechEvent.Error(SpeechError.NoMatch))

        assertEquals(VoiceError.NoMatch, viewModel.uiState.value.error)
    }

    @Test
    fun `mic click while listening cancels and returns to idle`() = runTest {
        val speech = FakeSpeechToText()
        val viewModel = buildViewModel(speechToText = speech)

        viewModel.onMicClick()
        assertEquals(AssistantPhase.Listening, viewModel.uiState.value.phase)
        viewModel.onMicClick()

        assertEquals(AssistantPhase.Idle, viewModel.uiState.value.phase)
        assertEquals(1, speech.cancelCount)
    }

    @Test
    fun `mic click while processing cancels analysis`() = runTest {
        val speech = FakeSpeechToText()
        val viewModel = buildViewModel(
            speechToText = speech,
            imageSource = NeverCompletingSource(),
        )

        viewModel.onMicClick()
        speech.emit(SpeechEvent.FinalResult("What is this?"))
        assertEquals(AssistantPhase.Processing, viewModel.uiState.value.phase)

        viewModel.onMicClick()

        assertEquals(AssistantPhase.Idle, viewModel.uiState.value.phase)
    }

    @Test
    fun `ai failure maps to error state`() = runTest {
        val speech = FakeSpeechToText()
        val viewModel = buildViewModel(
            speechToText = speech,
            assistantEngine = FailingAssistantEngine(),
        )

        viewModel.onMicClick()
        speech.emit(SpeechEvent.FinalResult("What is this?"))

        val state = viewModel.uiState.value
        assertEquals(AssistantPhase.Idle, state.phase)
        assertEquals(VoiceError.Network, state.error)
    }

    @Test
    fun `mic click while speaking stops tts and keeps turn`() = runTest {
        val speech = FakeSpeechToText()
        val speaker = FakeTextSpeaker()
        val viewModel = buildViewModel(speechToText = speech, textSpeaker = speaker)

        viewModel.onMicClick()
        speech.emit(SpeechEvent.FinalResult("What is this?"))
        assertEquals(AssistantPhase.Speaking, viewModel.uiState.value.phase)

        viewModel.onMicClick()

        assertEquals(1, speaker.stopCount)
        val state = viewModel.uiState.value
        assertEquals(AssistantPhase.Idle, state.phase)
        assertEquals("This image contains a laptop and desk.", state.turns[0].answer)
    }

    @Test
    fun `typed ask records a turn and speaks answer`() = runTest {
        val speaker = FakeTextSpeaker()
        val viewModel = buildViewModel(textSpeaker = speaker)

        viewModel.askText("What color is the bottle?")

        assertEquals(AssistantPhase.Speaking, viewModel.uiState.value.phase)
        assertEquals(1, viewModel.uiState.value.turns.size)
        assertEquals("What color is the bottle?", viewModel.uiState.value.turns[0].question)
        assertEquals(listOf("This image contains a laptop and desk."), speaker.spokenTexts)
    }

    @Test
    fun `blank typed ask shows NoMatch error`() = runTest {
        val viewModel = buildViewModel()

        viewModel.askText("   ")

        assertEquals(VoiceError.NoMatch, viewModel.uiState.value.error)
        assertEquals(0, viewModel.uiState.value.turns.size)
    }

    @Test
    fun `retry after analysis failure reuses last question`() = runTest {
        val engine = FlakyAssistantEngine()
        val speech = FakeSpeechToText()
        val viewModel = buildViewModel(
            speechToText = speech,
            assistantEngine = engine,
        )

        viewModel.onMicClick()
        speech.emit(SpeechEvent.FinalResult("What is this?"))
        assertEquals(VoiceError.Network, viewModel.uiState.value.error)

        viewModel.retry()

        assertEquals(AssistantPhase.Speaking, viewModel.uiState.value.phase)
        assertEquals(2, engine.calls)
    }

    @Test
    fun `new conversation clears turns and cancels`() = runTest {
        val speech = FakeSpeechToText()
        val viewModel = buildViewModel(speechToText = speech)

        viewModel.askText("What is this?")
        assertTrue(viewModel.uiState.value.turns.isNotEmpty())

        viewModel.onNewConversation()

        val state = viewModel.uiState.value
        assertEquals(AssistantPhase.Idle, state.phase)
        assertTrue(state.turns.isEmpty())
        assertEquals(1, speech.cancelCount)
    }

    @Test
    fun `late final result after cancel is ignored`() = runTest {
        val source = CountingSource(sampleFrame())
        val speech = FakeSpeechToText()
        val viewModel = buildViewModel(speechToText = speech, imageSource = source)

        viewModel.onMicClick()
        viewModel.onMicClick()
        speech.emit(SpeechEvent.FinalResult("What is this?"))

        assertEquals(AssistantPhase.Idle, viewModel.uiState.value.phase)
        assertEquals(0, source.captureCount)
        assertTrue(viewModel.uiState.value.turns.isEmpty())
    }

    @Test
    fun `spoken stop while processing cancels the command`() = runTest {
        val speech = FakeSpeechToText()
        val viewModel = buildViewModel(
            speechToText = speech,
            imageSource = NeverCompletingSource(),
        )

        viewModel.onMicClick()
        speech.emit(SpeechEvent.FinalResult("What is this?"))
        assertEquals(AssistantPhase.Processing, viewModel.uiState.value.phase)

        viewModel.askText("Dừng lại")

        assertEquals(AssistantPhase.Idle, viewModel.uiState.value.phase)
    }

    @Test
    fun `photo command saves without calling the ai engine`() = runTest {
        val camera = FakeWearableCamera()
        val engine = RecordingAssistantEngine()
        val speaker = FakeTextSpeaker()
        val viewModel = buildViewModel(
            textSpeaker = speaker,
            assistantEngine = engine,
            camera = camera,
        )

        viewModel.askText("Take a photo")

        assertEquals(AssistantPhase.Idle, viewModel.uiState.value.phase)
        assertEquals(1, camera.photoCount)
        assertEquals(0, engine.calls)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `photo command shows media message and speaks confirmation`() = runTest {
        val camera = FakeWearableCamera()
        val speaker = FakeTextSpeaker()
        val viewModel = buildViewModel(textSpeaker = speaker, camera = camera)

        viewModel.askText("Take a photo")

        assertEquals(AssistantPhase.Idle, viewModel.uiState.value.phase)
        assertNotNull(viewModel.uiState.value.mediaMessage)
        assertEquals(MediaType.Photo, viewModel.uiState.value.mediaMessage?.type)
        assertEquals(listOf(VoiceAssistantViewModel.PHOTO_SAVED_CONFIRMATION), speaker.spokenTexts)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `video command records until stopped early then saves`() = runTest {
        val camera = FakeWearableCamera()
        val speaker = FakeTextSpeaker()
        val viewModel = buildViewModel(textSpeaker = speaker, camera = camera)

        viewModel.askText("Record a video")
        assertEquals(AssistantPhase.Recording, viewModel.uiState.value.phase)
        assertTrue(viewModel.uiState.value.recordingStartedAtMillis != null)

        viewModel.onMicClick()

        assertEquals(1, camera.stopCount)
        assertEquals(AssistantPhase.Idle, viewModel.uiState.value.phase)
        assertEquals(MediaType.Video, viewModel.uiState.value.mediaMessage?.type)
        assertEquals(listOf(VoiceAssistantViewModel.VIDEO_SAVED_CONFIRMATION), speaker.spokenTexts)
    }

    @Test
    fun `typed command while busy shows busy error`() = runTest {
        val viewModel = buildViewModel(imageSource = NeverCompletingSource())

        viewModel.askText("What is this?")
        assertEquals(AssistantPhase.Processing, viewModel.uiState.value.phase)

        viewModel.askText("Take a photo")

        assertEquals(VoiceError.Busy, viewModel.uiState.value.error)
        assertEquals(AssistantPhase.Processing, viewModel.uiState.value.phase)
    }

    @Test
    fun `repeat with no history shows no previous answer error`() = runTest {
        val viewModel = buildViewModel()

        viewModel.askText("Repeat that")

        assertEquals(VoiceError.NoPreviousAnswer, viewModel.uiState.value.error)
    }

    @Test
    fun `repeat after answer speaks without capture or engine call`() = runTest {
        val source = CountingSource(sampleFrame())
        val engine = RecordingAssistantEngine()
        val speaker = FakeTextSpeaker()
        val viewModel = buildViewModel(
            textSpeaker = speaker,
            imageSource = source,
            assistantEngine = engine,
        )

        viewModel.askText("What is this?")
        assertEquals(AssistantPhase.Speaking, viewModel.uiState.value.phase)
        assertEquals(1, engine.calls)
        speaker.emit(SpeakerEvent.Finished)
        assertEquals(AssistantPhase.Idle, viewModel.uiState.value.phase)

        viewModel.askText("Nói lại")

        assertEquals(AssistantPhase.Speaking, viewModel.uiState.value.phase)
        assertEquals(1, engine.calls)
        assertEquals(1, source.captureCount)
        assertEquals(2, speaker.spokenTexts.size)
        assertEquals("This image contains a laptop and desk.", speaker.spokenTexts[1])
    }

    @Test
    fun `ambiguous command shows ambiguous error`() = runTest {
        val viewModel = buildViewModel()

        viewModel.askText("Take a photo and describe the scene")

        assertEquals(VoiceError.AmbiguousCommand, viewModel.uiState.value.error)
    }

    @Test
    fun `unsupported action shows unsupported error`() = runTest {
        val viewModel = buildViewModel()

        viewModel.askText("Send a message to mom")

        assertEquals(VoiceError.UnsupportedAction, viewModel.uiState.value.error)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun buildViewModel(
        speechToText: FakeSpeechToText = FakeSpeechToText(),
        textSpeaker: FakeTextSpeaker = FakeTextSpeaker(),
        imageSource: ImageSource = FakeImageSource(sampleFrame()),
        assistantEngine: AssistantEngine = RecordingAssistantEngine(),
        camera: FakeWearableCamera = FakeWearableCamera(),
    ): VoiceAssistantViewModel {
        val prepare = CapturePreparedFrameUseCase(
            captureFrameUseCase = CaptureFrameUseCase(
                imageSource,
                FrameProcessor { ProcessedFrame(it, it.timestampMillis + 1) },
            ),
            framePreprocessor = FramePreprocessor { it },
        )
        val ask = AskAssistantUseCase(
            capturePreparedFrameUseCase = prepare,
            assistantEngine = assistantEngine,
            conversationContext = FakeConversationContext(),
            nowMillis = { 1_000L },
        )
        val orchestrator = AiOrchestrator(
            router = RuleBasedCommandRouter(),
            askAssistantUseCase = ask,
            takePhotoUseCase = TakePhotoUseCase(camera),
            startVideoRecordingUseCase = StartVideoRecordingUseCase(camera),
            stopVideoRecordingUseCase = StopVideoRecordingUseCase(camera),
        )
        return VoiceAssistantViewModel(
            speechToText = speechToText,
            textSpeaker = textSpeaker,
            orchestrator = orchestrator,
        )
    }

    private fun sampleFrame() = ImageFrame(
        data = ByteArray(16) { it.toByte() },
        width = 320,
        height = 240,
        timestampMillis = 1000L,
        rotationDegrees = 0,
        format = ImageFormat.JPEG,
    )

    private class FakeSpeechToText : SpeechToText {
        private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 16)
        override val events: Flow<SpeechEvent> = _events

        var startCount = 0
        var stopCount = 0
        var cancelCount = 0

        override fun startListening() {
            startCount++
        }

        override fun stopListening() {
            stopCount++
        }

        override fun cancel() {
            cancelCount++
        }

        fun emit(event: SpeechEvent) {
            _events.tryEmit(event)
        }
    }

    private class FakeTextSpeaker : TextSpeaker {
        private val _events = MutableSharedFlow<SpeakerEvent>(extraBufferCapacity = 16)
        override val events: Flow<SpeakerEvent> = _events

        val spokenTexts = mutableListOf<String>()
        var stopCount = 0
        var released = false

        override fun speak(text: String) {
            spokenTexts += text
        }

        override fun stop() {
            stopCount++
        }

        override fun release() {
            released = true
        }

        fun emit(event: SpeakerEvent) {
            _events.tryEmit(event)
        }
    }

    private class CountingSource(private val frame: ImageFrame) : ImageSource {
        var captureCount = 0
        override suspend fun captureFrame(): ImageFrame {
            captureCount++
            return frame
        }
    }

    private class FakeImageSource(private val frame: ImageFrame) : ImageSource {
        override suspend fun captureFrame(): ImageFrame = frame
    }

    private class NeverCompletingSource : ImageSource {
        override suspend fun captureFrame(): ImageFrame {
            kotlinx.coroutines.delay(Long.MAX_VALUE)
            error("unreachable")
        }
    }

    private class RecordingAssistantEngine : AssistantEngine {
        var calls = 0
        var lastTask: com.rayban.ai.domain.model.AssistantTask? = null

        override suspend fun respond(request: AssistantRequest): AssistantResponse {
            calls++
            lastTask = request.task
            return AssistantResponse(
                answer = "This image contains a laptop and desk.",
                processingTimeMillis = 10L,
            )
        }
    }

    private class FailingAssistantEngine : AssistantEngine {
        override suspend fun respond(request: AssistantRequest): AssistantResponse {
            throw VisionException(VisionError.NetworkError, "no network")
        }
    }

    private class FlakyAssistantEngine : AssistantEngine {
        var calls = 0
        override suspend fun respond(request: AssistantRequest): AssistantResponse {
            calls++
            if (calls == 1) {
                throw VisionException(VisionError.NetworkError, "no network")
            }
            return AssistantResponse(
                answer = "This image contains a laptop and desk.",
                processingTimeMillis = 10L,
            )
        }
    }

    private class FakeWearableCamera : WearableCamera {
        private val _capabilities = MutableStateFlow(CameraCapabilities())
        override val capabilities: StateFlow<CameraCapabilities> = _capabilities.asStateFlow()

        var photoCount = 0
        var stopCount = 0
        var videoStarted = false
        private val stopSignal = kotlinx.coroutines.CompletableDeferred<Unit>()

        override suspend fun captureFrame(): ImageFrame = ImageFrame(
            data = ByteArray(16) { it.toByte() },
            width = 320,
            height = 240,
            timestampMillis = 1000L,
            rotationDegrees = 0,
            format = ImageFormat.JPEG,
        )

        override suspend fun takePhoto(): MediaAsset {
            photoCount++
            return MediaAsset(
                uri = "content://media/photo",
                type = MediaType.Photo,
                createdAtMillis = 1_000L,
            )
        }

        override suspend fun startVideoRecording(request: VideoRecordingRequest): MediaAsset {
            videoStarted = true
            stopSignal.await()
            return MediaAsset(
                uri = "content://media/video",
                type = MediaType.Video,
                createdAtMillis = 1_000L,
                durationMillis = 5_000L,
            )
        }

        override fun stopVideoRecording() {
            stopCount++
            stopSignal.complete(Unit)
        }

        override fun cancelActiveRecording() {
            stopVideoRecording()
        }
    }

    private class FakeConversationContext : ConversationContext {
        private var sceneId = 0L
        private var frame: ImageFrame? = null
        private var ocrText: String? = null
        private val turns = mutableListOf<ConversationTurn>()

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