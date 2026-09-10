package com.team.smartglasses.media

import java.io.File

/** Quan ly file anh / video / audio. Nguoi 3. */
class MediaStore {
    fun photoDir(): String = "Pictures/Ai-Vision"
    fun videoDir(): String = "Movies/Ai-Vision"

    fun newPhotoFile(): File = File(photoDir(), "IMG_${System.currentTimeMillis()}.jpg")
    fun newVideoFile(): File = File(videoDir(), "VID_${System.currentTimeMillis()}.mp4")
}
