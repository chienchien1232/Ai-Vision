package com.rayban.ai.data.vision

import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

data class HttpResponse(
    val statusCode: Int,
    val body: String,
)

fun interface GeminiHttpClient {
    suspend fun generateContent(
        model: String,
        apiKey: String,
        requestJson: String,
    ): HttpResponse
}

class OkHttpGeminiHttpClient(
    private val okHttpClient: OkHttpClient = defaultClient(),
) : GeminiHttpClient {

    override suspend fun generateContent(
        model: String,
        apiKey: String,
        requestJson: String,
    ): HttpResponse = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url("$BASE_URL/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val call = okHttpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                try {
                    response.use { r ->
                        val body = r.body?.string().orEmpty()
                        continuation.resume(HttpResponse(statusCode = r.code, body = body))
                    }
                } catch (e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        })
    }

    private companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
