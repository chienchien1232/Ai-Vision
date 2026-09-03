package com.rayban.ai.domain.repository

import com.rayban.ai.domain.model.ImageFrame

fun interface ImageSource {
    suspend fun captureFrame(): ImageFrame
}
