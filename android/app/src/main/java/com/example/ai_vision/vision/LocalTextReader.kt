package com.example.ai_vision.vision

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun interface TextReader {
    suspend fun read(jpeg: ByteArray, rotationDegrees: Int): String
}

/** Bundled Latin OCR. Native work owns its bitmap until completion, even after Cancel. */
class LocalTextReader : TextReader {
    private val gate = Mutex()
    override suspend fun read(jpeg: ByteArray, rotationDegrees: Int): String = gate.withLock {
        currentCoroutineContext().ensureActive()
        require(rotationDegrees in setOf(0, 90, 180, 270)) { "Invalid OCR rotation" }
        require(jpeg.isNotEmpty() && jpeg.size <= 4 * 1024 * 1024) { "Ảnh OCR rỗng hoặc quá lớn." }
        val result = withContext(Dispatchers.Default + NonCancellable) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
                bounds.outWidth.toLong() * bounds.outHeight <= 8_000_000) { "Không đọc được ảnh OCR hoặc ảnh quá lớn." }
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                ?: error("Không đọc được ảnh OCR.")
            try {
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                try {
                    suspendCoroutine { continuation ->
                        recognizer.process(InputImage.fromBitmap(bitmap, rotationDegrees))
                            .addOnCompleteListener(Executor { it.run() }) { task ->
                                if (task.isSuccessful) continuation.resume(task.result.text)
                                else continuation.resumeWithException(task.exception ?: IllegalStateException("OCR failed"))
                            }
                    }
                } finally { recognizer.close() }
            } finally { bitmap.recycle() }
        }
        currentCoroutineContext().ensureActive()
        result
    }
}
