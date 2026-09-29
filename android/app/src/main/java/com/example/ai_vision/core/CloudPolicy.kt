package com.example.ai_vision.core

/** No cloud classifier: supported commands/answers never spend Gemini calls. */
object CloudPolicy {
    fun reason(intent: AppIntent): String? = when (intent) {
        is AppIntent.Question -> if (intent.needsFreshPhoto) "SCENE_QUESTION" else "BEYOND_LOCAL_HANDLERS"
        else -> null
    }
}
