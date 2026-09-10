package com.example.ai_vision

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.ai_vision.device.GlassTransport
import com.example.ai_vision.device.protocol.GlassCommand
import com.example.ai_vision.device.protocol.GlassEvent
import com.example.ai_vision.device.protocol.EventName
import com.example.ai_vision.ui.DeviceScreen
import com.example.ai_vision.ui.DeviceState
import com.example.ai_vision.ui.DeviceViewModel
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConnectionTimeoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun timeoutRemainsErrorAndRetryConnects() {
        var attempts = 0
        var disconnects = 0
        val transport = object : GlassTransport {
            override suspend fun connect() {
                attempts++
                if (attempts == 1) delay(6_000)
            }
            override fun disconnect() { disconnects++ }
            override suspend fun send(command: GlassCommand) =
                GlassEvent(command.requestId, EventName.Pong)
        }
        val model = DeviceViewModel(transport)
        compose.setContent { DeviceScreen(model) }
        compose.onNodeWithText("Connect").performClick()
        compose.waitUntil(7_000) { model.deviceState is DeviceState.Error }
        compose.onNodeWithText("Connection timed out").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(1, attempts)
            assertEquals(1, disconnects)
            assertTrue(model.deviceState is DeviceState.Error)
        }
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(2_000) { model.deviceState == DeviceState.Connected }
        compose.runOnIdle {
            assertEquals(2, attempts)
            model.disconnect()
        }
    }
}
