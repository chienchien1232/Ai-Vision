package com.example.ai_vision.device

import java.io.IOException

/** A complete device error response: the framed TCP connection is still usable. */
class GlassCommandException(val code: String) : IOException("ESP32: $code")
