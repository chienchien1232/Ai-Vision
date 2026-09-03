package com.rayban.ai.data.processing

import com.rayban.ai.domain.model.FrameCaptureError
import com.rayban.ai.domain.model.FrameCaptureException
import com.rayban.ai.domain.model.ImageFrame
import com.rayban.ai.domain.model.ProcessedFrame
import com.rayban.ai.domain.processor.FrameProcessor

class ValidatingFrameProcessor : FrameProcessor {
    override suspend fun process(frame: ImageFrame): ProcessedFrame {
        if (frame.data.isEmpty() || frame.width <= 0 || frame.height <= 0) {
            throw FrameCaptureException(FrameCaptureError.InvalidFrame, "Frame failed validation")
        }
        return ProcessedFrame(
            image = frame,
            processedAtMillis = System.currentTimeMillis(),
        )
    }
}
