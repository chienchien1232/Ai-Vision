package com.example.ai_vision.device.protocol

enum class CommandName {
    PING,GET_STATUS
}
data class GlassCommand(val requestId: String, val name: CommandName){

}