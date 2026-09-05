package com.rayban.ai.domain.audio

sealed interface SpeakerEvent {
    data object Ready : SpeakerEvent
    data object Synthesizing : SpeakerEvent
    data class SynthesisFailed(val error: SpeechError) : SpeakerEvent
    data object Started : SpeakerEvent
    data object Finished : SpeakerEvent
    data object LanguageUnavailable : SpeakerEvent
    data class Error(val message: String?) : SpeakerEvent
}
