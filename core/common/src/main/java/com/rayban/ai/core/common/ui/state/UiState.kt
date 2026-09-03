package com.rayban.ai.core.common.ui.state

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>

    data class Content<T>(val value: T) : UiState<T>

    data class Error(val message: String) : UiState<Nothing>
}
