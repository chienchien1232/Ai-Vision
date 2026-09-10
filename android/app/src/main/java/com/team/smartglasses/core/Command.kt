package com.team.smartglasses.core

/** Lenh dieu khien tu UI / Voice / Firmware. */
sealed interface Command {
    data object DescribeScene : Command
    data object TakePhoto : Command
    data object StartRecording : Command
    data object StopRecording : Command
    data class Ask(val question: String) : Command
    data object RepeatLast : Command
}
