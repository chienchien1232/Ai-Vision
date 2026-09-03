package com.rayban.ai.domain.vision

import com.rayban.ai.domain.model.ImageFrame
import com.rayban.ai.domain.model.VisionResult

interface VisionEngine {
    suspend fun analyze(
        frame: ImageFrame,
        question: String,
    ): VisionResult
}