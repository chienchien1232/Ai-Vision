package com.example.ai_vision

import com.example.ai_vision.device.inspectPcm16
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmDiagnosticsTest {
    @Test
    fun silenceAndClippingAreMeasurableWithoutBackend() {
        val silence = inspectPcm16(ByteArray(32000))
        assertEquals(16000, silence.samples)
        assertEquals(0, silence.peak)
        assertEquals(0, silence.rms)
        assertEquals(0, silence.nearClipped)

        val clipped = inspectPcm16(byteArrayOf(0, 0x80.toByte(), 0xff.toByte(), 0x7f))
        assertEquals(2, clipped.samples)
        assertEquals(32768, clipped.peak)
        assertEquals(2, clipped.nearClipped)
    }
}
