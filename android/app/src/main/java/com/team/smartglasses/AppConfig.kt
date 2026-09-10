package com.team.smartglasses

/** Cau hinh chung cho app. Nguoi 3 quan ly. */
object AppConfig {
    const val APP_NAME = "Ai-Vision"
    const val DEFAULT_LANGUAGE = "vi" // vi | en
    const val COMMAND_TIMEOUT_MS = 12_000L
    const val MAX_IMAGE_SIZE = 1920
    const val API_TIMEOUT_SEC = 30L

    // Backend se dien o moc AI, hien de trong de build offline.
    const val BACKEND_BASE_URL = ""
    const val GEMINI_API_KEY_PLACEHOLDER = ""
}
