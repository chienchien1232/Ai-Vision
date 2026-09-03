package com.rayban.ai.domain.audio

sealed interface SpeakerEvent {
    data object Ready : SpeakerEvent
    data object Started : SpeakerEvent
    data object Finished : SpeakerEvent
    data class Error(val message: String?) : SpeakerEvent
}