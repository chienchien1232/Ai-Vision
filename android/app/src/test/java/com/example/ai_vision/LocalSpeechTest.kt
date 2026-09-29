package com.example.ai_vision

import com.example.ai_vision.voice.pcm16ToFloat
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class LocalSpeechTest {
    @Test fun convertsSignedLittleEndianPcmWithoutClipping() {
        assertArrayEquals(floatArrayOf(0f, -1f, 32767f / 32768, -1f / 32768),
            pcm16ToFloat(byteArrayOf(0, 0, 0, -128, -1, 127, -1, -1)), 0f)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsPartialSample() { pcm16ToFloat(byteArrayOf(1)) }
    @Test(expected = IllegalArgumentException::class) fun rejectsEmptyCapture() { pcm16ToFloat(byteArrayOf()) }
    @Test(expected = IllegalArgumentException::class) fun boundsNativeInputAllocation() { pcm16ToFloat(ByteArray(960002)) }
}
