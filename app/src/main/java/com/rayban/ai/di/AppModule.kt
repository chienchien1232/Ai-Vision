package com.rayban.ai.di

import com.rayban.ai.BuildConfig
import com.rayban.ai.core.audio.ActiveSpeechToText
import com.rayban.ai.core.audio.CloudSpeechToText
import com.rayban.ai.core.audio.PhoneWearableMicrophone
import com.rayban.ai.data.audio.GeminiAudioTranscriber
import com.rayban.ai.domain.audio.AudioTranscriber
import com.rayban.ai.core.audio.FakeGlassesSpeechToText
import com.rayban.ai.core.camera.ActiveImageSource
import com.rayban.ai.core.camera.ActiveWearableCamera
import com.rayban.ai.core.camera.CameraPreviewController
import com.rayban.ai.core.camera.FakeGlassesCamera
import com.rayban.ai.core.camera.PhoneCameraSource
import com.rayban.ai.data.assistant.GeminiAssistantEngine
import com.rayban.ai.data.assistant.MockAssistantEngine
import com.rayban.ai.data.context.InMemoryConversationContext
import com.rayban.ai.data.device.DeviceStatusRepositoryImpl
import com.rayban.ai.data.device.InMemoryActiveWearableSession
import com.rayban.ai.data.processing.BitmapFramePreprocessor
import com.rayban.ai.data.processing.ValidatingFrameProcessor
import com.rayban.ai.data.vision.GeminiVisionEngine
import com.rayban.ai.data.vision.MockVisionEngine
import com.rayban.ai.domain.assistant.AssistantEngine
import com.rayban.ai.domain.audio.SpeechToText
import com.rayban.ai.domain.audio.TextSpeaker
import com.rayban.ai.domain.context.ConversationContext
import com.rayban.ai.domain.orchestrator.AiOrchestrator
import com.rayban.ai.domain.orchestrator.CommandRouter
import com.rayban.ai.domain.orchestrator.RuleBasedCommandRouter
import com.rayban.ai.domain.processor.FramePreprocessor
import com.rayban.ai.domain.processor.FrameProcessor
import com.rayban.ai.domain.repository.DeviceStatusRepository
import com.rayban.ai.domain.repository.ImageSource
import com.rayban.ai.domain.wearable.ActiveWearableSession
import com.rayban.ai.domain.usecase.AnalyzeImageUseCase
import com.rayban.ai.domain.usecase.AskAssistantUseCase
import com.rayban.ai.domain.usecase.CaptureFrameUseCase
import com.rayban.ai.domain.usecase.CapturePreparedFrameUseCase
import com.rayban.ai.domain.usecase.ObserveDeviceStatusUseCase
import com.rayban.ai.domain.usecase.StartVideoRecordingUseCase
import com.rayban.ai.domain.usecase.StopVideoRecordingUseCase
import com.rayban.ai.domain.usecase.TakePhotoUseCase
import com.rayban.ai.domain.vision.VisionEngine
import com.rayban.ai.domain.wearable.WearableCamera
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideActiveWearableSession(): ActiveWearableSession =
        InMemoryActiveWearableSession()

    @Provides
    @Singleton
    fun provideDeviceStatusRepository(
        session: ActiveWearableSession,
    ): DeviceStatusRepository = DeviceStatusRepositoryImpl(session)

    @Provides
    fun provideObserveDeviceStatusUseCase(
        repository: DeviceStatusRepository,
    ): ObserveDeviceStatusUseCase = ObserveDeviceStatusUseCase(repository)

    @Provides
    fun provideFrameProcessor(): FrameProcessor = ValidatingFrameProcessor()

    @Provides
    fun provideFramePreprocessor(): FramePreprocessor = BitmapFramePreprocessor()

    @Provides
    fun provideVisionEngine(): VisionEngine =
        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            MockVisionEngine()
        } else {
            GeminiVisionEngine(apiKey = BuildConfig.GEMINI_API_KEY)
        }

    @Provides
    fun provideAssistantEngine(): AssistantEngine =
        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            MockAssistantEngine()
        } else {
            GeminiAssistantEngine(apiKey = BuildConfig.GEMINI_API_KEY)
        }

    @Provides
    @Singleton
    fun provideConversationContext(): ConversationContext = InMemoryConversationContext()

    @Provides
    fun provideCommandRouter(): CommandRouter = RuleBasedCommandRouter()

    @Provides
    fun provideCaptureFrameUseCase(
        imageSource: ImageSource,
        frameProcessor: FrameProcessor,
    ): CaptureFrameUseCase = CaptureFrameUseCase(imageSource, frameProcessor)

    @Provides
    fun provideCapturePreparedFrameUseCase(
        captureFrameUseCase: CaptureFrameUseCase,
        framePreprocessor: FramePreprocessor,
    ): CapturePreparedFrameUseCase =
        CapturePreparedFrameUseCase(captureFrameUseCase, framePreprocessor)

    @Provides
    fun provideAnalyzeImageUseCase(
        capturePreparedFrameUseCase: CapturePreparedFrameUseCase,
        visionEngine: VisionEngine,
    ): AnalyzeImageUseCase = AnalyzeImageUseCase(capturePreparedFrameUseCase, visionEngine)

    @Provides
    fun provideAskAssistantUseCase(
        capturePreparedFrameUseCase: CapturePreparedFrameUseCase,
        assistantEngine: AssistantEngine,
        conversationContext: ConversationContext,
    ): AskAssistantUseCase =
        AskAssistantUseCase(capturePreparedFrameUseCase, assistantEngine, conversationContext)

    @Provides
    fun provideTakePhotoUseCase(
        camera: WearableCamera,
    ): TakePhotoUseCase = TakePhotoUseCase(camera)

    @Provides
    fun provideStartVideoRecordingUseCase(
        camera: WearableCamera,
    ): StartVideoRecordingUseCase = StartVideoRecordingUseCase(camera)

    @Provides
    fun provideStopVideoRecordingUseCase(
        camera: WearableCamera,
    ): StopVideoRecordingUseCase = StopVideoRecordingUseCase(camera)

    @Provides
    fun provideAiOrchestrator(
        router: CommandRouter,
        askAssistantUseCase: AskAssistantUseCase,
        takePhotoUseCase: TakePhotoUseCase,
        startVideoRecordingUseCase: StartVideoRecordingUseCase,
        stopVideoRecordingUseCase: StopVideoRecordingUseCase,
    ): AiOrchestrator = AiOrchestrator(
        router,
        askAssistantUseCase,
        takePhotoUseCase,
        startVideoRecordingUseCase,
        stopVideoRecordingUseCase,
    )
}

@Module
@InstallIn(SingletonComponent::class)
object AudioModule {
    @Provides
    @Singleton
    fun provideAudioTranscriber(): AudioTranscriber = GeminiAudioTranscriber(
        apiKey = BuildConfig.GEMINI_API_KEY,
        model = BuildConfig.GEMINI_STT_MODEL,
    )

    @Provides
    @Singleton
    fun provideCloudSpeechToText(
        microphone: PhoneWearableMicrophone,
        transcriber: AudioTranscriber,
    ): CloudSpeechToText = CloudSpeechToText(
        microphone, transcriber, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )
    /**
     * The mic pipeline always talks to this delegate; it forwards to the phone
     * recognizer or the glasses transcription backend based on the session.
     */
    @Provides
    @Singleton
    fun provideSpeechToText(
        session: ActiveWearableSession,
        phone: CloudSpeechToText,
        glasses: FakeGlassesSpeechToText,
    ): SpeechToText = ActiveSpeechToText(session, phone, glasses)

    @Provides
    @Singleton
    fun provideTextSpeaker(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
    ): TextSpeaker = com.rayban.ai.core.audio.CloudTextSpeaker(
        com.rayban.ai.data.audio.GeminiSpeechSynthesizer(BuildConfig.GEMINI_API_KEY,
            BuildConfig.GEMINI_TTS_MODEL, BuildConfig.GEMINI_TTS_VOICE),
        com.rayban.ai.core.audio.AndroidSpeechAudioPlayer(context),
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )
}

@Module
@InstallIn(SingletonComponent::class)
object CameraModule {
    /**
     * The vision pipeline always talks to these delegates; they forward to the
     * phone camera or the glasses camera based on the session. Swapping in the
     * real ESP32 implementations later only changes these three methods.
     */
    @Provides
    @Singleton
    fun provideImageSource(
        session: ActiveWearableSession,
        phone: PhoneCameraSource,
        glasses: FakeGlassesCamera,
    ): ImageSource = ActiveImageSource(session, phone, glasses)

    @Provides
    @Singleton
    fun provideWearableCamera(
        session: ActiveWearableSession,
        phone: PhoneCameraSource,
        glasses: FakeGlassesCamera,
        appScope: CoroutineScope,
    ): WearableCamera = ActiveWearableCamera(session, phone, glasses, appScope)

    @Provides
    fun providePreviewController(
        impl: PhoneCameraSource,
    ): CameraPreviewController = impl
}
