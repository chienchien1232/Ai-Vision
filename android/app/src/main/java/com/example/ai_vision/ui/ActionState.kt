package com.example.ai_vision.ui

sealed class ActionState {
    data object Idle : ActionState()
    data object Running : ActionState()
    data class Success(val message: String) : ActionState()
    data class Error(val message: String) : ActionState()
}
