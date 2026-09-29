package com.example.ai_vision

import com.example.ai_vision.ai.cancelableHttp
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpCancellationTest {
    @Test fun cancelClosesConnectionWithoutWaitingForReadTimeout() = runBlocking {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val disconnected = AtomicBoolean(false)
        val connection = object : HttpURLConnection(URL("https://example.invalid")) {
            override fun connect() {}
            override fun usingProxy() = false
            override fun disconnect() { disconnected.set(true); released.countDown() }
        }
        val job = launch {
            cancelableHttp(connection) {
                started.countDown()
                check(released.await(10, TimeUnit.SECONDS))
                "late result must not be returned"
            }
            error("Cancelled operation returned a late result")
        }
        assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
        withTimeout(1000) { job.cancelAndJoin() }
        assertTrue(disconnected.get())
    }
}
