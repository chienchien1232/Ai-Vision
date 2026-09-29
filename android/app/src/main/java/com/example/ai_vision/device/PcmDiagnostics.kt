package com.example.ai_vision.device

import kotlin.math.sqrt

internal data class PcmDiagnostics(val samples: Int, val peak: Int, val rms: Int, val nearClipped: Int)

/** Local-only mic diagnostic; never uploads recorded audio or needs a backend. */
internal fun inspectPcm16(pcm: ByteArray): PcmDiagnostics {
    require(pcm.isNotEmpty() && pcm.size % 2 == 0)
    var peak = 0
    var nearClipped = 0
    var energy = 0L
    for (offset in pcm.indices step 2) {
        val sample = ((pcm[offset + 1].toInt() shl 8) or
            (pcm[offset].toInt() and 0xff)).toShort().toInt()
        val magnitude = kotlin.math.abs(sample)
        if (magnitude > peak) peak = magnitude
        if (magnitude >= 32600) nearClipped++
        energy += magnitude.toLong() * magnitude
    }
    val samples = pcm.size / 2
    return PcmDiagnostics(samples, peak, sqrt(energy.toDouble() / samples).toInt(), nearClipped)
}
