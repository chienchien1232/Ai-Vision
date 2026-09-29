package com.example.ai_vision.ai

import java.net.HttpURLConnection
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

private val httpWorkers = Executors.newFixedThreadPool(2) { work -> Thread(work, "ai-http").apply { isDaemon = true } }

/** Coroutine cancellation closes the socket instead of waiting for the read timeout. */
internal suspend fun <T> cancelableHttp(connection: HttpURLConnection, request: () -> T): T = suspendCancellableCoroutine { continuation ->
    val future = httpWorkers.submit {
        try {
            if (continuation.isActive) {
                val result = request()
                continuation.resume(result)
            }
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        } finally { connection.disconnect() }
    }
    continuation.invokeOnCancellation { runCatching { connection.disconnect() }; future.cancel(true) }
}
