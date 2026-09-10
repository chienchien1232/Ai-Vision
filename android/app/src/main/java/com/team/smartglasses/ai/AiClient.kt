package com.team.smartglasses.ai

/** Client goi AI. Backend that se dien o moc AI. Nguoi 3. */
data class AiRequestConfig(
    val model: String = "gemini-flash",
    val language: String = "vi",
    val timeoutSec: Long = 30
)

interface AiClient {
    suspend fun describeImage(jpeg: ByteArray, config: AiRequestConfig): String
    suspend fun ask(question: String, config: AiRequestConfig): String
}
