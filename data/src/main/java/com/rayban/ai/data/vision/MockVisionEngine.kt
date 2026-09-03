package com.rayban.ai.data.vision

import com.rayban.ai.domain.model.DetectedObject
import com.rayban.ai.domain.model.ImageFrame
import com.rayban.ai.domain.model.VisionError
import com.rayban.ai.domain.model.VisionException
import com.rayban.ai.domain.model.VisionResult
import com.rayban.ai.domain.vision.VisionEngine
import kotlinx.coroutines.delay

class MockVisionEngine : VisionEngine {
    override suspend fun analyze(
        frame: ImageFrame,
        question: String,
    ): VisionResult {
        if (frame.data.isEmpty() || frame.width <= 0 || frame.height <= 0) {
            throw VisionException(
                error = VisionError.InvalidImage,
                message = "Frame failed vision validation",
            )
        }
        val start = System.currentTimeMillis()
        delay(SIMULATED_LATENCY_MILLIS)
        return VisionResult(
            description = "This image contains a laptop and desk.",
            objects = listOf(
                DetectedObject(label = "laptop", confidence = 0.94f),
                DetectedObject(label = "desk", confidence = 0.91f),
            ),
            confidence = 0.92f,
            processingTimeMillis = System.currentTimeMillis() - start,
        )
    }

    private companion object {
        const val SIMULATED_LATENCY_MILLIS = 800L
    }
}