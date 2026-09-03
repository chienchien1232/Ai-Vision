package com.rayban.ai.domain.audio

import kotlinx.coroutines.flow.Flow

interface SpeechToText {
    val events: Flow<SpeechEvent>
    fun startListening()
    fun stopListening()
    fun cancel()
}