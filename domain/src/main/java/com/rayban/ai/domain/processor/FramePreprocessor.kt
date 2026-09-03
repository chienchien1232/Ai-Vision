package com.rayban.ai.domain.processor

import com.rayban.ai.domain.model.ImageFrame

fun interface FramePreprocessor {
    suspend fun prepare(frame: ImageFrame): ImageFrame
}