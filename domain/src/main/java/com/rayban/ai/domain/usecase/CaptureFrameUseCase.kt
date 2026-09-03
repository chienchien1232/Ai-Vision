package com.rayban.ai.domain.usecase

import com.rayban.ai.domain.model.ProcessedFrame
import com.rayban.ai.domain.processor.FrameProcessor
import com.rayban.ai.domain.repository.ImageSource
import kotlin.coroutines.cancellation.CancellationException

class CaptureFrameUseCase(
    private val imageSource: ImageSource,
    private val frameProcessor: FrameProcessor,
) {
    suspend operator fun invoke(): Result<ProcessedFrame> = try {
        Result.success(frameProcessor.process(imageSource.captureFrame()))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}