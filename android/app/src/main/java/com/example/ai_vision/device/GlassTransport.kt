package com.example.ai_vision.device

import com.example.ai_vision.device.protocol.GlassCommand
import com.example.ai_vision.device.protocol.GlassEvent

interface GlassTransport {
    suspend fun connect(): Unit
    fun disconnect(): Unit
    suspend fun send(command: GlassCommand): GlassEvent
}