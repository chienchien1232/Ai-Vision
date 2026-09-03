package com.rayban.ai.domain.usecase

import com.rayban.ai.domain.assistant.AssistantEngine
import com.rayban.ai.domain.context.ConversationContext
import com.rayban.ai.domain.model.AssistantRequest
import com.rayban.ai.domain.model.AssistantTask
import com.rayban.ai.domain.model.ConversationTurn
import com.rayban.ai.domain.model.FramePolicy
import com.rayban.ai.domain.model.ImageFrame
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AskAssistantUseCase(
    private val capturePreparedFrameUseCase: CapturePreparedFrameUseCase,
    private val assistantEngine: AssistantEngine,
    private val conversationContext: ConversationContext,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val frameTtlMillis: Long = DEFAULT_FRAME_TTL_MILLIS,
    private val serialLock: Mutex = Mutex(),
) {

    suspend operator fun invoke(
        question: String,
        task: AssistantTask = AssistantTask.OpenQuestion,
        framePolicy: FramePolicy = FramePolicy.RecentOrFresh,
    ): Result<AssistantResult> {
        if (question.isBlank()) {
            return Result.failure(IllegalArgumentException("Question cannot be blank"))
        }
        return serialLock.withLock {
            try {
                val snapshot = conversationContext.snapshot()
                val frame: ImageFrame
                val ocrText: String?
                if (framePolicy == FramePolicy.Fresh) {
                    frame = captureAndCache()
                    ocrText = null
                } else {
                    val cached = snapshot.latestFrame
                    val isFresh = cached != null &&
                        (nowMillis() - cached.timestampMillis) <= frameTtlMillis
                    if (cached != null && isFresh) {
                        frame = cached
                        ocrText = snapshot.latestOcrText
                    } else {
                        frame = captureAndCache()
                        ocrText = null
                    }
                }
                val response = assistantEngine.respond(
                    AssistantRequest(
                        question = question,
                        frame = frame,
                        latestOcrText = ocrText,
                        recentTurns = snapshot.recentTurns,
                        task = task,
                    ),
                )
                conversationContext.recordTurn(
                    question = question,
                    answer = response.answer,
                    timestampMillis = nowMillis(),
                )
                if (task == AssistantTask.ReadText) {
                    conversationContext.cacheOcrText(response.answer)
                }
                Result.success(
                    AssistantResult(
                        answer = response.answer,
                        frame = frame,
                        processingTimeMillis = response.processingTimeMillis,
                        turns = conversationContext.snapshot().recentTurns,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun newConversation() {
        serialLock.withLock { conversationContext.clear() }
    }

    suspend fun changeScene() {
        serialLock.withLock { conversationContext.changeScene() }
    }

    suspend fun recentTurns(): List<ConversationTurn> = conversationContext.snapshot().recentTurns

    private suspend fun captureAndCache(): ImageFrame {
        val frame = capturePreparedFrameUseCase().getOrElse { throw it }
        conversationContext.cacheFrame(frame)
        return frame
    }

    companion object {
        const val DEFAULT_FRAME_TTL_MILLIS = 10_000L
    }
}