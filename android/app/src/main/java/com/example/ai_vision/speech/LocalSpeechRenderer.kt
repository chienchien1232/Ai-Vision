package com.example.ai_vision.speech

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** Vietnamese never consults Android system voices or a network TTS service. */
class LocalSpeechRenderer(context: Context, idleMillis: Long = 60_000) : SpeechRenderer {
    private val app = context.applicationContext
    private var english: PhoneSpeaker? = null
    private var prepared: File? = null
    private val engine = SerialSpeechEngine({
        val dir = installModel()
        val tts = OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(
            vits = OfflineTtsVitsModelConfig(model = File(dir, "model.onnx").path,
                tokens = File(dir, "tokens.txt").path, dataDir = File(dir, "espeak-ng-data").path),
            numThreads = 2, provider = "cpu"), maxNumSentences = 1))
        if (tts.sampleRate() != 22050 || tts.numSpeakers() != 1) { tts.release(); error("Offline TTS model is unavailable") }
        object : NativeSpeechHandle {
            override fun render(text: String, cancelled: () -> Boolean): ByteArray {
                var tooLong = false
                var frames = 0L
                val parts = mutableListOf<FloatArray>()
                for (chunk in speechChunks(text)) {
                    if (cancelled()) throw CancellationException("TTS cancelled")
                    val callback = TtsProgressCallback { samples ->
                        frames += samples.size
                        tooLong = frames > tts.sampleRate().toLong() * 30
                        if (cancelled() || tooLong) 0 else 1
                    }
                    val audio = tts.generateWithCallback(chunk, sid = 0, speed = 1f, callback = callback)
                    if (cancelled()) throw CancellationException("TTS cancelled")
                    if (tooLong) throw java.io.IOException("Phản hồi quá dài để phát (tối đa 30 giây). Nội dung chữ vẫn được giữ.")
                    parts += audio.samples
                }
                val count = parts.sumOf { it.size }
                if (count > tts.sampleRate() * 30) throw java.io.IOException("Phản hồi quá dài để phát (tối đa 30 giây). Nội dung chữ vẫn được giữ.")
                val samples = FloatArray(count)
                var offset = 0
                for (part in parts) { part.copyInto(samples, offset); offset += part.size }
                return floatPcm16k(samples, tts.sampleRate())
            }
            override fun release() = tts.release()
        }
    }, idleMillis)
    internal val modelLoaded: Boolean get() = engine.modelLoaded

    override suspend fun renderPcm16k(text: String, languageTag: String): ByteArray {
        require(text.isNotBlank() && text.length <= 4000) { "Answer text must contain 1..4000 characters" }
        if (languageTag == "en-US") return (english ?: PhoneSpeaker(app).also { english = it }).renderPcm16k(text, languageTag)
        require(languageTag == "vi-VN") { "Unsupported speech language" }
        val started = SystemClock.elapsedRealtime()
        val preset = vietnamesePresets[text.trim()]
        val pcm = if (preset != null) withContext(Dispatchers.IO) {
            app.assets.open("tts/replies/$preset.pcm").use { it.readBytes() }.also {
                require(it.isNotEmpty() && it.size % 2 == 0 && it.size <= GLASS_PCM_MAX_BYTES) { "Invalid preset audio" }
            }
        } else try {
            kotlinx.coroutines.withTimeout(30_000) { engine.render(text) }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            throw java.io.IOException("Tổng hợp tiếng nói quá thời gian 30 giây. Nội dung chữ vẫn được giữ.")
        }
        Log.i("VI_TTS", "source=${if (preset == null) "model" else "preset"} renderMs=${SystemClock.elapsedRealtime() - started} audioMs=${pcm.size * 1000L / 32000}")
        return pcm
    }

    private fun installModel(): File {
        prepared?.let { return it }
        val manifest = app.assets.open("tts/vi/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        check(manifest.getString("archiveSha256") == "fa1367710767d36ed5cf13b4a449e20c35ffd12791c2e47c2e64142bfa55551a") { "Invalid TTS model version" }
        val dir = File(app.noBackupFilesDir, "tts-vi-fa136771").apply { check(isDirectory || mkdirs()) }
        val files = manifest.getJSONArray("files")
        for (index in 0 until files.length()) {
            val entry = files.getJSONObject(index)
            val path = entry.getString("path")
            val file = File(dir, path)
            check(!path.startsWith('/') && file.canonicalPath.startsWith(dir.canonicalPath + File.separator)) { "Invalid model path" }
            val expectedSize = entry.getLong("size")
            val expectedHash = entry.getString("sha256")
            fun valid(): Boolean = file.isFile && file.length() == expectedSize &&
                file.inputStream().use { input ->
                    val sha = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(65536)
                    while (true) { val count = input.read(buffer); if (count < 0) break; sha.update(buffer, 0, count) }
                    sha.digest().joinToString("") { "%02x".format(it) }
                } == expectedHash
            if (!valid()) {
                check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                val temp = File(file.parentFile, file.name + ".tmp")
                try {
                    app.assets.open("tts/vi/$path").use { input -> temp.outputStream().use { input.copyTo(it) } }
                    check(temp.renameTo(file)) { "Cannot install offline TTS data" }
                    check(valid()) { "Corrupt offline TTS model" }
                } finally { temp.delete() }
            }
        }
        return dir.also { prepared = it }
    }

    override fun stop() { engine.stop(); english?.stop() }
    override fun close() { engine.close(); english?.close() }
}
