package com.team.smartglasses.core

/** Trang thai UI dung chung. */
data class UiState(
    val isDeviceConnected: Boolean = false,
    val deviceName: String = "",
    val lastAnswer: String = "",
    val isBusy: Boolean = false,
    val error: String? = null
)
