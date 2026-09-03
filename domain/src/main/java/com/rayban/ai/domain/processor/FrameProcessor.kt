package com.rayban.ai.domain.processor

import com.rayban.ai.domain.model.ImageFrame
import com.rayban.ai.domain.model.ProcessedFrame

fun interface FrameProcessor {
    suspend fun process(frame: ImageFrame): ProcessedFrame
}
