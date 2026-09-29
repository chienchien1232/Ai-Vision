package com.example.ai_vision

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ai_vision.core.SavedSettings
import com.example.ai_vision.core.SettingsStore
import com.example.ai_vision.ui.DeviceState
import com.example.ai_vision.ui.DeviceViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeLifecycleTest {
    @Test fun clearingScreenViewModelDoesNotDisconnectRuntime() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val store = ViewModelStore()
            val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app)
            val first = ViewModelProvider(store, factory)[DeviceViewModel::class.java].controller
            try {
                first.demoMode = true
                first.connect()
                store.clear()
                val second = ViewModelProvider(store, factory)[DeviceViewModel::class.java].controller
                assertSame(first, second)
                assertTrue(second.deviceState is DeviceState.Connected)
            } finally { first.disconnect(); first.demoMode = false; store.clear() }
        }
    }
    @Test fun settingsRoundTripDoesNotStorePlaintextToken() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SettingsStore(context)
        val previous = store.load()
        try {
            val saved = SavedSettings("https://example.invalid", "unit-test-not-a-real-token", "en-US", false)
            store.save(saved)
            assertEquals(saved, store.load())
            val raw = context.getSharedPreferences("private_ai_settings", 0).getString("token", "").orEmpty()
            assertFalse(raw.contains(saved.backendToken))
            assertTrue(raw.contains(':'))
        } finally { store.save(previous) }
    }
}
