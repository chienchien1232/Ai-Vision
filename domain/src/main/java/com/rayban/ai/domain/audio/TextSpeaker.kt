package com.rayban.ai.domain.audio

import kotlinx.coroutines.flow.Flow

interface TextSpeaker {
    val events: Flow<SpeakerEvent>
    fun speak(text: String)
    fun stop()
    fun release()
}