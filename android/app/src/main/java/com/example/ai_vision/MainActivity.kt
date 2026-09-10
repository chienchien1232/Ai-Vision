package com.example.ai_vision

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.ai_vision.ui.DeviceScreen
import com.example.ai_vision.ui.theme.AiVisionTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {

            AiVisionTheme() {

                DeviceScreen()
            }
        }
    }
}