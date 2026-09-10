package com.example.ai_vision.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel


@Composable
fun DeviceScreen(
    viewModel: DeviceViewModel = viewModel()
) {
    val deviceState = viewModel.deviceState
    val actionState = viewModel.actionState
    val statusText=when(deviceState){
        is DeviceState.Connected -> "Connected"
        is DeviceState.Connecting -> "Connecting..."
        is DeviceState.Disconnected -> "Disconnected"
        is DeviceState.Error -> deviceState.message
    }
    val actionText = when (actionState) {
        ActionState.Idle -> "No command executed"
        ActionState.Running -> "Waiting for PONG..."
        is ActionState.Success -> actionState.message
        is ActionState.Error -> "Error: ${actionState.message}"
    }
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Ai-Vision")
        Text(text = "Simulated device — no glasses connected")
        Text(text = statusText)
        Button(
            onClick = { viewModel.connect() },
            enabled = deviceState == DeviceState.Disconnected ||
                deviceState is DeviceState.Error
        ) {
            Text(text = if (deviceState is DeviceState.Error) "Retry" else "Connect")
        }
        Button(
            onClick = { viewModel.ping() },
            enabled = deviceState == DeviceState.Connected &&
                actionState != ActionState.Running
        ) {
            Text(text = "PING")
        }
        Button(
            onClick = { viewModel.disconnect() },
            enabled = deviceState == DeviceState.Connected
        ) {
            Text(text = "Disconnect")
        }
        Text(text = actionText)
    }
}
