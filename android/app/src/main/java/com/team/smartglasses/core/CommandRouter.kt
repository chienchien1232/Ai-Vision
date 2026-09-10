package com.team.smartglasses.core

/** Dinh tuyen lenh song ngu Viet/Anh. Nguoi 3. */
class CommandRouter {
    fun route(text: String): Command {
        val t = text.trim().lowercase()
        return when {
            t.contains("chụp") || t.contains("take a photo") || t.contains("take photo") -> Command.TakePhoto
            t.contains("quay") || t.contains("record") -> Command.StartRecording
            t.contains("dừng") || t.contains("stop") -> Command.StopRecording
            t.contains("mô tả") || t.contains("describe") -> Command.DescribeScene
            t.contains("nhắc lại") || t.contains("repeat") -> Command.RepeatLast
            else -> Command.Ask(text)
        }
    }
}
