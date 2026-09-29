package com.example.ai_vision.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedSettings(val backendUrl: String = "", val backendToken: String = "", val languageTag: String = "vi-VN", val cloudEnabled: Boolean = true)

/** App token encrypted with a non-exportable Android Keystore key. Never store Gemini keys. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("private_ai_settings", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("ai_vision_settings", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("ai_vision_settings", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): SavedSettings {
        val token = runCatching {
            val encrypted = prefs.getString("token", "").orEmpty()
            if (encrypted.isBlank()) "" else {
                val parts = encrypted.split(':')
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
                String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
            }
        }.getOrDefault("") // Restored prefs without the device key require a fresh token.
        return SavedSettings(prefs.getString("url", "").orEmpty(), token,
            prefs.getString("language", "vi-VN").takeIf { it in setOf("vi-VN", "en-US") } ?: "vi-VN",
            prefs.getBoolean("cloud", true))
    }
    fun save(settings: SavedSettings) {
        val token = if (settings.backendToken.isBlank()) "" else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(cipher.doFinal(settings.backendToken.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }
        check(prefs.edit().putString("url", settings.backendUrl.trim()).putString("token", token)
            .putString("language", settings.languageTag).putBoolean("cloud", settings.cloudEnabled).commit()) { "Cannot save settings" }
    }
}
