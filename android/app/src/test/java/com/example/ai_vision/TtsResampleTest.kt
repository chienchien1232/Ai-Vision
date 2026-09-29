package com.example.ai_vision

import com.example.ai_vision.speech.resampleToMono16k
import com.example.ai_vision.speech.speakerTestPcm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class TtsResampleTest {
    private fun sineBytes(frames: Int, rate: Int, channels: Int, bits: Int): ByteArray {
        val out = ByteArray(frames * channels * bits / 8)
        var offset = 0
        repeat(frames) { frame ->
            val value = sin(2 * PI * 440 * frame / rate)
            repeat(channels) {
                if (bits == 8) {
                    out[offset++] = (128 + (value * 100)).toInt().toByte()
                } else {
                    val pcm = (value * 30000).toInt().toShort()
                    out[offset++] = (pcm.toInt() and 0xff).toByte()
                    out[offset++] = ((pcm.toInt() shr 8) and 0xff).toByte()
                }
            }
        }
        return out
    }

    private fun peak(out: ByteArray): Int {
        var max = 0
        var offset = 0
        while (offset + 1 < out.size) {
            val low = out[offset].toInt() and 0xff
            val high = out[offset + 1].toInt()
            val sample = kotlin.math.abs(((high shl 8) or low).toShort().toInt())
            if (sample > max) max = sample
            offset += 2
        }
        return max
    }

    @Test
    fun mono8kUpsamplesTo16k() {
        val out = resampleToMono16k(sineBytes(8000, 8000, 1, 16), 1, 8000, 16)
        assertEquals(32000, out.size)
        assertTrue(peak(out) > 20000)
    }

    @Test
    fun stereoDownmixesToMono() {
        val out = resampleToMono16k(sineBytes(16000, 16000, 2, 16), 2, 16000, 16)
        assertEquals(32000, out.size)
        assertTrue(peak(out) > 20000)
    }

    @Test
    fun eightBitPcmIsAccepted() {
        val out = resampleToMono16k(sineBytes(8000, 8000, 1, 8), 1, 8000, 8)
        assertEquals(32000, out.size)
        assertTrue(peak(out) > 5000)
    }

    @Test
    fun speakerTestToneIsBoundedAndFaded() {
        val out = speakerTestPcm()
        assertEquals(32000, out.size)
        assertEquals(0, out[0].toInt())
        assertEquals(0, out[1].toInt())
        assertEquals(0, out[out.lastIndex].toInt())
        assertTrue(peak(out) in 9500..10000)
    }
}
