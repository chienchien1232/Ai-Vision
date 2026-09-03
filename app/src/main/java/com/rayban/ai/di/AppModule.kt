package com.rayban.ai.di

import com.rayban.ai.BuildConfig
import com.rayban.ai.core.audio.AndroidSpeechToText
import com.rayban.ai.core.audio.AndroidTextSpeaker
import com.rayban.ai.core.camera.PhoneCameraSource
import com.rayban.ai.data.assistant.GeminiAssistantEngine
import com.rayban.ai.data.assistant.MockAssistantEngine
import com.rayban.ai.data.context.InMemoryConversationContext
import com.rayban.ai.data.device.DeviceStatusRepositoryImpl
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
import dagger.Binds
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
    fun provideDeviceStatusRepository(): DeviceStatusRepository =
        DeviceStatusRepositoryImpl()

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
abstract class AudioModule {
    @Binds
    @Singleton
    abstract fun bindSpeechToText(
        impl: AndroidSpeechToText,
    ): SpeechToText

    @Binds
    @Singleton
    abstract fun bindTextSpeaker(
        impl: AndroidTextSpeaker,
    ): TextSpeaker
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CameraModule {
    @Binds
    @Singleton
    abstract fun bindImageSource(
        impl: PhoneCameraSource,
    ): ImageSource

    @Binds
    @Singleton
    abstract fun bindWearableCamera(
        impl: PhoneCameraSource,
    ): WearableCamera

    @Binds
    abstract fun bindPreviewController(
        impl: PhoneCameraSource,
    ): com.rayban.ai.core.camera.CameraPreviewController
}