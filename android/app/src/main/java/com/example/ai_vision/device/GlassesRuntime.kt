package com.example.ai_vision.device

import android.app.Application
import com.example.ai_vision.ui.DeviceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** One process-wide owner: activity recreation never destroys the TCP/mic session. */
object GlassesRuntime {
    private var instance: DeviceController? = null
    @Synchronized fun controller(application: Application): DeviceController = instance ?: DeviceController(
        application, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    ).also { instance = it }
}
