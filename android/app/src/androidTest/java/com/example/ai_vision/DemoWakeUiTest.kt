package com.example.ai_vision

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ai_vision.device.GlassesRuntime
import com.example.ai_vision.device.VoicePhase
import com.example.ai_vision.ui.ActionState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DemoWakeUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun demoWakeCanCancelAndRealAndroidMicDiagnosticsAllowsAnotherWake() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        val controller = GlassesRuntime.controller(app)
        val previous = controller.demoMode
        try {
            ui.runOnUiThread { controller.disconnect(); controller.demoMode = true }
            ui.onNodeWithText("Connect").performScrollTo().performClick()
            ui.onNodeWithText("WAKE AI — mic Android (Demo)").performScrollTo().performClick()
            ui.runOnIdle { assertTrue(controller.actionState is ActionState.Running) }
            ui.onNodeWithText("Cancel action").performScrollTo().performClick()
            ui.waitUntil(5000) { controller.actionState !is ActionState.Running }
            ui.runOnIdle { assertEquals(ActionState.Idle, controller.actionState); assertEquals(VoicePhase.IDLE, controller.voicePhase) }

            ui.onNodeWithText("Mic diagnostics (8s, no STT)").performScrollTo().performClick()
            ui.waitUntil(15_000) { controller.actionState !is ActionState.Running }
            ui.runOnIdle {
                assertTrue("${controller.actionState}", controller.actionState is ActionState.Success)
                assertTrue(controller.micStatus.startsWith("Mic test:"))
                assertEquals(VoicePhase.IDLE, controller.voicePhase)
            }
            ui.onNodeWithText("WAKE AI — mic Android (Demo)").performScrollTo().performClick()
            ui.onNodeWithText("Disconnect").performScrollTo().performClick()
            ui.runOnIdle { assertEquals(ActionState.Idle, controller.actionState); assertEquals(VoicePhase.IDLE, controller.voicePhase) }
        } finally {
            ui.runOnUiThread { controller.disconnect(); controller.demoMode = previous }
        }
    }
}
