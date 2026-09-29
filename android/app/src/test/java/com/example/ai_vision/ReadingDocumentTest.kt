package com.example.ai_vision

import com.example.ai_vision.vision.ReadingDocument
import org.junit.Assert.*
import org.junit.Test

class ReadingDocumentTest {
    @Test fun emptyResultNeverInventsText() {
        assertTrue(ReadingDocument.fromText(" \n  ").pages.isEmpty())
    }
    @Test fun vietnameseLongTextIsPreservedWithoutCuttingWords() {
        val text = (1..300).joinToString(" ") { "Tiếng Việt số $it." }
        val doc = ReadingDocument.fromText(text)
        assertEquals(text, doc.text)
        assertEquals(text, doc.pages.joinToString(" "))
        assertTrue(doc.pages.all { it.length <= 220 })
        assertTrue(doc.pages.size > 1)
    }
    @Test fun paragraphsAndWindowsNewlinesRemainReadable() {
        val doc = ReadingDocument.fromText("Dòng thứ nhất\r\nDòng thứ hai")
        assertEquals("Dòng thứ nhất\nDòng thứ hai", doc.text)
        assertEquals(listOf("Dòng thứ nhất", "Dòng thứ hai"), doc.pages)
    }
    @Test fun oversizedWordIsNotSilentlyTruncated() {
        val word = "a".repeat(300)
        assertEquals(listOf(word), ReadingDocument.fromText(word).pages)
    }
    @Test(expected = IllegalArgumentException::class) fun inputIsBounded() {
        ReadingDocument.fromText("x".repeat(32_001))
    }
}
