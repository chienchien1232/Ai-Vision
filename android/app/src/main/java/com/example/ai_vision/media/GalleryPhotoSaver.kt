package com.example.ai_vision.media

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.net.Uri
import android.provider.MediaStore
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaSaveException(message: String, cause: Throwable) : IOException(message, cause)

object GalleryPhotoSaver {
    suspend fun save(context: Context, jpeg: ByteArray): Uri = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // No broad storage permission: older Android keeps photos in app-owned storage.
            val directory = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: java.io.File(context.filesDir, "Pictures")
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create photo directory" }
            val file = java.io.File(directory, "AiVision_${java.util.UUID.randomUUID()}.jpg")
            try { file.outputStream().use { it.write(jpeg) } }
            catch (error: Exception) { file.delete(); throw error }
            return@withContext Uri.fromFile(file)
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "AiVision_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Ai-Vision")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Cannot create Gallery image")
        try {
            resolver.openOutputStream(uri)?.use { it.write(jpeg) }
                ?: throw IOException("Cannot write Gallery image")
            val finished = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            check(resolver.update(uri, finished, null, null) == 1) { "Cannot finish Gallery image" }
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
        uri
    }

    suspend fun saveVideo(context: Context, avi: ByteArray) = withContext(Dispatchers.IO) {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Saving to Gallery requires Android 10 or newer"
        }
        require(avi.size in 1..20 * 1024 * 1024) { "Invalid video data" }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "AiVision_${System.currentTimeMillis()}.avi")
            put(MediaStore.Video.Media.MIME_TYPE, "video/x-msvideo")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Ai-Vision")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Cannot create Gallery video")
        try {
            resolver.openOutputStream(uri)?.use { it.write(avi) }
                ?: throw IOException("Cannot write Gallery video")
            val finished = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            check(resolver.update(uri, finished, null, null) == 1) { "Cannot finish Gallery video" }
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }
}
