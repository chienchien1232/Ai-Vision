package com.rayban.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rayban.ai.core.common.ui.theme.RayBanTheme
import com.rayban.ai.core.camera.CameraScreen
import com.rayban.ai.feature.navigation.AppNavHost
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RayBanTheme {
                AppNavHost(
                    cameraContent = { onBack -> CameraScreen(onBack = onBack) },
                )
            }
        }
    }
}
