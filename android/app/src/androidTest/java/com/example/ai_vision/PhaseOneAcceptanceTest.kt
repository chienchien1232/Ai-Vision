package com.example.ai_vision

import android.app.Application
import android.net.ConnectivityManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ai_vision.device.GlassesRuntime
import com.example.ai_vision.ui.ActionState
import com.example.ai_vision.ui.DeviceController
import com.example.ai_vision.ui.DeviceState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real UI, bundled Vietnamese TTS and AudioTrack; the camera remains synthetic Demo input. */
@RunWith(AndroidJUnit4::class)
class PhaseOneAcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var controller: DeviceController
    private var restore: (() -> Unit)? = null

    @Before fun configureOfflineDemo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        if (InstrumentationRegistry.getArguments().getString("requireOffline") == "1") {
            assertNull("Disable emulator Wi-Fi/mobile data for offline acceptance",
                app.getSystemService(ConnectivityManager::class.java).activeNetwork)
        }
        ui.runOnUiThread {
            controller = GlassesRuntime.controller(app)
            val demo = controller.demoMode
            val cloud = controller.cloudEnabled
            val language = controller.languageTag
            val url = controller.backendUrl
            val token = controller.backendToken
            val input = controller.inputText
            restore = {
                controller.disconnect()
                controller.demoMode = demo
                controller.cloudEnabled = cloud
                controller.languageTag = language
                controller.backendUrl = url
                controller.backendToken = token
                controller.inputText = input
            }
            controller.disconnect()
            controller.demoMode = true
            controller.cloudEnabled = false
            controller.languageTag = "vi-VN"
            controller.backendUrl = ""
            controller.backendToken = ""
        }
    }

    @After fun restoreRuntime() {
        ui.runOnUiThread { restore?.invoke() }
    }

    private fun submit(text: String) {
        ui.onNodeWithText("Command or question").performScrollTo().performTextReplacement(text)
        ui.onNodeWithText("Send").performScrollTo().performClick()
        awaitSpokenResult()
    }

    private fun awaitSpokenResult() {
        ui.waitUntil(timeoutMillis = 30_000) { controller.actionState !is ActionState.Running }
        ui.runOnIdle {
            assertTrue("${controller.actionState}", controller.actionState is ActionState.Success)
            assertEquals("Đã phát tiếng", controller.audioStatus)
        }
    }

    @Test fun offlineUiCapturesAnswersTimeCalculatesAndReplaysWithRealVietnameseAudio() {
        ui.onNodeWithText("Connect").performScrollTo().performClick()
        ui.runOnIdle { assertTrue(controller.deviceState is DeviceState.Connected) }

        submit("Chụp ảnh")
        val photo = ui.runOnIdle {
            assertNotNull(controller.savedPhotoUri)
            assertFalse(controller.canSavePhoto)
            requireNotNull(controller.image)
        }
        submit("Bây giờ là mấy giờ")
        ui.runOnIdle { assertTrue(controller.answerText.startsWith("Bây giờ là ")) }
        submit("Hai cộng ba")
        ui.runOnIdle { assertEquals("5", controller.answerText) }

        ui.onNodeWithText("Phát lại phản hồi gần nhất").performScrollTo().performClick()
        ui.waitUntil(timeoutMillis = 15_000) { controller.actionState !is ActionState.Running }
        ui.runOnIdle {
            assertTrue(controller.actionState is ActionState.Success)
            assertEquals("Đã phát lại", controller.audioStatus)
            assertSame(photo, controller.image)
        }

        submit("Đừng chụp ảnh")
        ui.runOnIdle { assertSame(photo, controller.image) }
        submit("Xem giúp tôi")
        ui.runOnIdle {
            assertTrue(controller.answerText.contains("đọc chữ hay mô tả cảnh"))
            assertSame(photo, controller.image)
            assertFalse(controller.cloudEnabled)
        }
    }
}
