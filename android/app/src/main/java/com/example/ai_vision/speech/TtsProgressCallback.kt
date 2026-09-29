package com.example.ai_vision.speech

import androidx.annotation.Keep

/** Sherpa 1.13.8 JNI reflects invoke(float[]): Integer; indy lambdas only expose erased invoke(Object). */
@Keep
internal class TtsProgressCallback(private val action: (FloatArray) -> Int) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = action(samples)
}
