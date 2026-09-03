package com.rayban.ai.data.context

import com.rayban.ai.domain.context.ConversationContext
import com.rayban.ai.domain.model.AssistantContextSnapshot
import com.rayban.ai.domain.model.ConversationTurn
import com.rayban.ai.domain.model.ImageFrame
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryConversationContext(
    private val maxTurnsPerScene: Int = DEFAULT_MAX_TURNS_PER_SCENE,
    private val maxScenes: Int = DEFAULT_MAX_SCENES,
) : ConversationContext {

    private val lock = Mutex()

    private var sceneId: Long = 0L
    private var latestFrame: ImageFrame? = null
    private var latestOcrText: String? = null
    private val allTurns = mutableListOf<ConversationTurn>()

    override suspend fun snapshot(): AssistantContextSnapshot = lock.withLock {
        AssistantContextSnapshot(
            sceneId = sceneId,
            latestFrame = latestFrame,
            latestOcrText = latestOcrText,
            recentTurns = currentSceneTurns(),
        )
    }

    override suspend fun cacheFrame(frame: ImageFrame) {
        lock.withLock {
            latestFrame = frame
            latestOcrText = null
        }
    }

    override suspend fun cacheOcrText(text: String?) {
        lock.withLock { latestOcrText = text }
    }

    override suspend fun recordTurn(
        question: String,
        answer: String,
        timestampMillis: Long,
    ) {
        lock.withLock {
            allTurns += ConversationTurn(
                question = question,
                answer = answer,
                sceneId = sceneId,
                timestampMillis = timestampMillis,
            )
            pruneToLimit()
        }
    }

    override suspend fun changeScene() {
        lock.withLock {
            sceneId += 1
            latestFrame = null
            latestOcrText = null
            pruneSceneArchive()
        }
    }

    override suspend fun clear() {
        lock.withLock {
            sceneId += 1
            latestFrame = null
            latestOcrText = null
            allTurns.clear()
        }
    }

    private fun currentSceneTurns(): List<ConversationTurn> {
        val sceneTurns = allTurns.filter { it.sceneId == sceneId }
        val drop = sceneTurns.size - maxTurnsPerScene
        return if (drop > 0) sceneTurns.drop(drop) else sceneTurns
    }

    private fun pruneToLimit() {
        val sceneTurns = allTurns.filter { it.sceneId == sceneId }
        val drop = sceneTurns.size - maxTurnsPerScene
        if (drop > 0) {
            val remove = sceneTurns.take(drop).toSet()
            allTurns.removeAll { it in remove }
        }
    }

    private fun pruneSceneArchive() {
        val sceneIds = allTurns.map { it.sceneId }.distinct()
        if (sceneIds.size > maxScenes) {
            val oldest = sceneIds.sorted().take(sceneIds.size - maxScenes).toSet()
            allTurns.removeAll { it.sceneId in oldest }
        }
    }

    private companion object {
        const val DEFAULT_MAX_TURNS_PER_SCENE = 6
        const val DEFAULT_MAX_SCENES = 3
    }
}