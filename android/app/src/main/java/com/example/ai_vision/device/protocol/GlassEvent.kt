package com.example.ai_vision.device.protocol

enum class EventName {
    Pong,Status

}

data class GlassEvent(val requestId: String, val name: EventName,val status: GlassStatus?= null)
