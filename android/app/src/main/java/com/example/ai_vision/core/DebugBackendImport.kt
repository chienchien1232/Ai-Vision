package com.example.ai_vision.core

import android.content.Context
import android.util.Log
import com.example.ai_vision.BuildConfig
import java.io.File
import org.json.JSONObject

/** One-shot private-file provisioning via run-as. No exported token-bearing intent. */
internal fun importDebugBackend(context: Context) {
    if (!BuildConfig.DEBUG) return
    val file = File(context.filesDir, "dev-backend.json")
    if (!file.exists()) return
    try {
        check(file.length() in 1..4096)
        val config = JSONObject(file.readText())
        val url = config.getString("backendUrl")
        val token = config.getString("backendToken")
        check(url == "http://127.0.0.1:8080")
        check(token.matches(Regex("[A-Za-z0-9+/=_-]{32,128}")))
        val store = SettingsStore(context)
        store.save(store.load().copy(backendUrl = url, backendToken = token, cloudEnabled = true))
        Log.i("AiVisionConfig", "Local backend configured; token encrypted")
    } catch (_: Exception) {
        Log.w("AiVisionConfig", "Local backend configuration rejected")
    } finally {
        file.delete()
    }
}
