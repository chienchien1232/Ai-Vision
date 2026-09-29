package com.example.ai_vision

import com.example.ai_vision.media.previewSampleSize
import org.junit.Assert.*
import org.junit.Test

class PhotoPreviewTest {
    @Test fun boundedDecodeUsesRealDimensionsAndDownsamples() {
        assertEquals(1, previewSampleSize(320, 240))
        assertEquals(4, previewSampleSize(4096, 1024))
        for ((w, h) in listOf(0 to 240, 4096 to 4096, 5000 to 100)) {
            try { previewSampleSize(w, h); fail("Oversized JPEG accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
