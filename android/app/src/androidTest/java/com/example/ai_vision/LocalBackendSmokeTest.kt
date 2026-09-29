package com.example.ai_vision

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ai_vision.ai.AiBackendConfig
import com.example.ai_vision.ai.AiQuestion
import com.example.ai_vision.ai.BackendAiClient
import com.example.ai_vision.core.SettingsStore
import com.example.ai_vision.core.importDebugBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Opt-in live check: never spends API quota in ordinary connected tests. */
@RunWith(AndroidJUnit4::class)
class LocalBackendSmokeTest {
    @Test fun phoneAnswersThroughConfiguredBackend() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("localBackendSmoke") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        importDebugBackend(context)
        val settings = SettingsStore(context).load()
        assertTrue(settings.backendUrl == "http://127.0.0.1:8080" && settings.backendToken.isNotBlank())
        val answer = BackendAiClient().answer(AiBackendConfig(settings.backendUrl, settings.backendToken),
            AiQuestion(UUID.randomUUID().toString(), "Vì sao bầu trời có màu xanh? Trả lời một câu tiếng Việt.", null))
        assertTrue(answer.isNotBlank())
    }
}
