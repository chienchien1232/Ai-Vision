package com.example.ai_vision.device
import com.example.ai_vision.device.protocol.CommandName
import com.example.ai_vision.device.protocol.EventName
import com.example.ai_vision.device.protocol.GlassCommand
import com.example.ai_vision.device.protocol.GlassEvent
import com.example.ai_vision.device.protocol.GlassStatus
import kotlinx.coroutines.delay
class FakeGlassTransport : GlassTransport {

    override suspend fun connect(){
        if(isConnected){
            return
        }
        delay(1000)
        isConnected=true
    }

    override fun disconnect() {
        isConnected=false

    }

    override suspend fun send(command: GlassCommand): GlassEvent {
        if(!isConnected){
            throw IllegalStateException("Device is not connected")
        }
        delay(1000)
        return when(command.name) {
            CommandName.PING -> GlassEvent(
                requestId = command.requestId,
                name = EventName.Pong
            )
            CommandName.GET_STATUS -> GlassEvent(
                requestId = command.requestId,
                name=EventName.Status,
                status = GlassStatus(
                    deviceName = "FakeGlass",
                    firmwareVersion = "fake-1.0"
                )
            )

        }
    }
    private var isConnected = false
}
