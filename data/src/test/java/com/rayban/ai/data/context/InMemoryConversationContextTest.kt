package com.rayban.ai.data.context

import com.rayban.ai.domain.model.ImageFormat
import com.rayban.ai.domain.model.ImageFrame
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryConversationContextTest {

    @Test
    fun `records turns in order and exposes them as recent`() = runTest {
        val context = InMemoryConversationContext()

        context.recordTurn("What is this?", "A laptop.", 1L)
        context.recordTurn("What model?", "ThinkPad.", 2L)

        val snapshot = context.snapshot()
        assertEquals(2, snapshot.recentTurns.size)
        assertEquals("What is this?", snapshot.recentTurns[0].question)
        assertEquals("ThinkPad.", snapshot.recentTurns[1].answer)
    }

    @Test
    fun `recent turns are bounded per scene`() = runTest {
        val context = InMemoryConversationContext(maxTurnsPerScene = 2)

        context.recordTurn("Q1", "A1", 1L)
        context.recordTurn("Q2", "A2", 2L)
        context.recordTurn("Q3", "A3", 3L)

        val snapshot = context.snapshot()
        assertEquals(2, snapshot.recentTurns.size)
        assertEquals("Q2", snapshot.recentTurns[0].question)
        assertEquals("Q3", snapshot.recentTurns[1].question)
    }

    @Test
    fun `change scene isolates turns and clears the frame`() = runTest {
        val context = InMemoryConversationContext()
        context.cacheFrame(sampleFrame())

        context.recordTurn("Q1", "A1", 1L)
        context.changeScene()
        context.recordTurn("Q2", "A2", 2L)

        val snapshot = context.snapshot()
        assertEquals(1, snapshot.recentTurns.size)
        assertEquals("Q2", snapshot.recentTurns[0].question)
        assertNull(snapshot.latestFrame)
    }

    @Test
    fun `clear resets scene frame ocr and turns`() = runTest {
        val context = InMemoryConversationContext()
        context.cacheFrame(sampleFrame())
        context.cacheOcrText("HELLO")
        context.recordTurn("Q1", "A1", 1L)

        context.clear()

        val snapshot = context.snapshot()
        assertNull(snapshot.latestFrame)
        assertNull(snapshot.latestOcrText)
        assertTrue(snapshot.recentTurns.isEmpty())
    }

    @Test
    fun `cacheFrame and cacheOcrText are reflected in snapshot`() = runTest {
        val context = InMemoryConversationContext()
        val frame = sampleFrame()

        context.cacheFrame(frame)
        context.cacheOcrText("EXIT")

        val snapshot = context.snapshot()
        assertEquals(frame, snapshot.latestFrame)
        assertEquals("EXIT", snapshot.latestOcrText)
    }

    @Test
    fun `caching a new frame clears stale ocr text`() = runTest {
        val context = InMemoryConversationContext()
        context.cacheOcrText("OLD TEXT")

        context.cacheFrame(sampleFrame())

        assertNull(context.snapshot().latestOcrText)
    }

    @Test
    fun `older scenes are pruned from archive`() = runTest {
        val context = InMemoryConversationContext(maxTurnsPerScene = 2, maxScenes = 2)

        context.recordTurn("S1Q", "A", 1L)
        context.changeScene()
        context.recordTurn("S2Q", "A", 2L)
        context.changeScene()
        context.recordTurn("S3Q", "A", 3L)

        val snapshot = context.snapshot()
        assertEquals(1, snapshot.recentTurns.size)
        assertEquals("S3Q", snapshot.recentTurns[0].question)
    }

    private fun sampleFrame() = ImageFrame(
        data = ByteArray(16) { it.toByte() },
        width = 640,
        height = 480,
        timestampMillis = 1000L,
        rotationDegrees = 0,
        format = ImageFormat.JPEG,
    )
}