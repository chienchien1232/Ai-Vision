package com.example.ai_vision.speech

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface NativeSpeechHandle {
    fun render(text: String, cancelled: () -> Boolean): ByteArray
    fun release()
}

/** One native owner. Stop never frees an object that is still executing JNI. */
internal class SerialSpeechEngine(
    private val factory: () -> NativeSpeechHandle,
    private val idleMillis: Long = 60_000
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val epoch = AtomicLong()
    private val closed = AtomicBoolean()
    private var idleJob: Job? = null
    @Volatile private var native: NativeSpeechHandle? = null
    internal val modelLoaded: Boolean get() = native != null

    suspend fun render(text: String): ByteArray {
        check(!closed.get()) { "TTS is closed" }
        val requestEpoch = epoch.get()
        val caller = currentCoroutineContext()[Job]!!
        return mutex.withLock {
            caller.ensureActive()
            if (closed.get() || epoch.get() != requestEpoch) throw CancellationException("TTS cancelled")
            idleJob?.cancel()
            try {
                // Let JNI finish/callback-stop before releasing the mutex or native memory.
                val result = withContext(NonCancellable + Dispatchers.Default) {
                    val handle = native ?: factory().also { native = it }
                    if (!caller.isActive || closed.get() || epoch.get() != requestEpoch) throw CancellationException()
                    handle.render(text) { !caller.isActive || closed.get() || epoch.get() != requestEpoch }
                }
                caller.ensureActive()
                if (closed.get() || epoch.get() != requestEpoch) throw CancellationException("TTS cancelled")
                result
            } finally {
                idleJob = scope.launch {
                    delay(idleMillis)
                    mutex.withLock { native?.release(); native = null }
                }
            }
        }
    }

    fun stop() { epoch.incrementAndGet() }
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop()
        scope.launch {
            mutex.withLock { idleJob?.cancel(); native?.release(); native = null }
            scope.cancel()
        }
    }
}
