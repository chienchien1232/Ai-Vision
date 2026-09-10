package com.example.ai_vision

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class DeviceFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(text))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun connectPingDisconnectAndReconnect() {
        compose.onNodeWithText("Disconnected").assertIsDisplayed()
        compose.onNodeWithText("PING").assertIsNotEnabled()
        compose.onNodeWithText("Disconnect").assertIsNotEnabled()
        compose.onNodeWithText("Connect").performClick()
        compose.onNodeWithText("Connecting...").assertIsDisplayed()
        compose.onNodeWithText("Connect").assertIsNotEnabled()
        waitForText("Connected")
        compose.onNodeWithText("PING").performClick()
        compose.onNodeWithText("PING").assertIsNotEnabled()
        waitForText("PONG")
        compose.onNodeWithText("Disconnect").performClick()
        compose.onNodeWithText("No command executed").assertIsDisplayed()
        compose.onNodeWithText("Connect").performClick()
        waitForText("Connected")
        compose.onNodeWithText("PING").assertIsEnabled()
    }

    @Test
    fun disconnectCancelsPendingPing() {
        compose.onNodeWithText("Connect").performClick()
        waitForText("Connected")
        compose.onNodeWithText("PING").performClick()
        compose.onNodeWithText("Disconnect").performClick()
        // Wait beyond the simulated reply to catch a stale result after disconnect.
        Thread.sleep(1_500)
        compose.onNodeWithText("Disconnected").assertIsDisplayed()
        compose.onNodeWithText("No command executed").assertIsDisplayed()
        compose.onNodeWithText("PING").assertIsNotEnabled()
    }
}
