package com.example.ai_vision.core

/** One short-lived clarification, kept in memory only; no cloud classifier or history. */
class ConversationRouter(private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var pending: AppIntent.Clarification? = null
    private var askedAt = 0L

    fun clear() { pending = null }

    fun route(raw: String, wakeWord: String = ""): AppIntent {
        val routed = IntentRouter.route(raw, wakeWord)
        val question = pending
        val age = nowMillis() - askedAt
        clear()
        val normalized = IntentRouter.normalize(raw)
        val result = if (question?.topic == AppIntent.Clarification.Topic.READ_OR_DESCRIBE && age in 0..30_000) {
            when (normalized) {
                "doc", "doc di", "doc cho toi", "chu", "van ban", "read", "text" -> AppIntent.ReadText
                "mo ta", "canh", "mo ta di", "describe", "scene" -> AppIntent.Question(raw.trim(), true)
                else -> routed
            }
        } else routed
        if (result is AppIntent.Clarification && result.topic != null) {
            pending = result
            askedAt = nowMillis()
        }
        return result
    }
}
