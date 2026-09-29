package com.example.ai_vision

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import com.example.ai_vision.ui.DeviceScreen
import com.example.ai_vision.ui.theme.AiVisionTheme
import com.example.ai_vision.core.importDebugBackend

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importDebugBackend(this)
        setContent {
            val darkTheme = isSystemInDarkTheme()
            SideEffect {
                val bars = WindowCompat.getInsetsController(window, window.decorView)
                bars.isAppearanceLightStatusBars = !darkTheme
                bars.isAppearanceLightNavigationBars = !darkTheme
            }
            AiVisionTheme {
                DeviceScreen()
            }
        }
    }
}
