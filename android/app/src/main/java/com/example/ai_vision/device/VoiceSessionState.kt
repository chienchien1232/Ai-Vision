package com.example.ai_vision.device

enum class VoicePhase { IDLE, STARTING, LISTENING, TRANSCRIBING, ROUTING, WAITING_AI, SYNTHESIZING, PLAYING, CANCELLING, CLEANING_UP }

/** App-owned microphone lease. Unsolicited and completed-session events never start work. */
class VoiceSessionState {
    var phase: VoicePhase = VoicePhase.IDLE
        private set
    var sessionId: String? = null
        private set
    val waitingForInput: Boolean get() = phase == VoicePhase.STARTING || phase == VoicePhase.LISTENING

    fun start(): Boolean {
        if (phase != VoicePhase.IDLE) return false
        phase = VoicePhase.STARTING
        sessionId = null
        return true
    }

    fun bind(id: String) {
        check(phase == VoicePhase.STARTING && id.isNotBlank())
        sessionId = id
        phase = VoicePhase.LISTENING
    }

    fun accepts(id: String): Boolean = phase == VoicePhase.LISTENING && sessionId == id
    fun move(next: VoicePhase) { check(next != VoicePhase.STARTING); phase = next }
    fun clear() { sessionId = null; phase = VoicePhase.IDLE }
}
