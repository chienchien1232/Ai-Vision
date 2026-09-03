package com.rayban.ai.domain.usecase

import com.rayban.ai.domain.model.ImageFrame
import com.rayban.ai.domain.model.VisionResult

data class AnalyzeImageResult(
    val frame: ImageFrame,
    val vision: VisionResult,
)