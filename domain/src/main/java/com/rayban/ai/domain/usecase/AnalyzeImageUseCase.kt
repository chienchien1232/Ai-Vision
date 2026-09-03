package com.rayban.ai.domain.usecase

import com.rayban.ai.domain.vision.VisionEngine
import kotlin.coroutines.cancellation.CancellationException

class AnalyzeImageUseCase(
    private val capturePreparedFrameUseCase: CapturePreparedFrameUseCase,
    private val visionEngine: VisionEngine,
) {
    suspend operator fun invoke(question: String): Result<AnalyzeImageResult> {
        val frameResult = capturePreparedFrameUseCase()
        val prepared = frameResult.getOrElse { return Result.failure(it) }
        return try {
            Result.success(
                AnalyzeImageResult(
                    frame = prepared,
                    vision = visionEngine.analyze(prepared, question),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}