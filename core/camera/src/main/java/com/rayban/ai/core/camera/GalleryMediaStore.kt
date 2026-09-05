package com.rayban.ai.core.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.rayban.ai.domain.model.MediaCaptureError
import com.rayban.ai.domain.model.MediaCaptureException
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared gallery persistence for every camera implementation.
 *
 * Extracted from [PhoneCameraSource] so the future ESP32 camera (which downloads
 * JPEG bytes from the glasses over Wi-Fi) saves photos exactly like the phone does.
 */
@Singleton
class GalleryMediaStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    fun savePhoto(jpegBytes: ByteArray, timestampMillis: Long): Uri {
        if (jpegBytes.isEmpty()) {
            throw MediaCaptureException(MediaCaptureError.CaptureFailed, "Photo bytes are empty")
        }
        val resolver = context.applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "glass_photo_$timestampMillis.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, PHOTO_RELATIVE_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw MediaCaptureException(MediaCaptureError.StorageUnavailable, "Could not create gallery entry")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(jpegBytes)
            } ?: throw MediaCaptureException(
                MediaCaptureError.StorageUnavailable,
                "Could not open gallery output stream",
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val publish = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, publish, null, null)
            }
            return uri
        } catch (e: MediaCaptureException) {
            resolver.delete(uri, null, null)
            throw e
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw MediaCaptureException(MediaCaptureError.StorageUnavailable, "Failed to write photo", e)
        }
    }

    private companion object {
        const val PHOTO_RELATIVE_PATH = "Pictures/AI Smart Glasses"
    }
}
