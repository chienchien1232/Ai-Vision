package com.rayban.ai.domain.audio

sealed interface SpeechEvent {
    data object Ready : SpeechEvent
    data object Listening : SpeechEvent
    data class PartialResult(val text: String) : SpeechEvent
    data class FinalResult(val text: String) : SpeechEvent
    data class Error(val error: SpeechError) : SpeechEvent
    data object Cancelled : SpeechEvent
}