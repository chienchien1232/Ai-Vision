package com.rayban.ai.domain.usecase

import com.rayban.ai.domain.model.ImageFrame
import com.rayban.ai.domain.model.ProcessedFrame
import com.rayban.ai.domain.processor.FramePreprocessor
import kotlin.coroutines.cancellation.CancellationException

class CapturePreparedFrameUseCase(
    private val captureFrameUseCase: CaptureFrameUseCase,
    private val framePreprocessor: FramePreprocessor,
) {
    suspend operator fun invoke(): Result<ImageFrame> {
        val frameResult = captureFrameUseCase()
        val processed: ProcessedFrame = frameResult.getOrElse { return Result.failure(it) }
        return try {
            Result.success(framePreprocessor.prepare(processed.image))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}