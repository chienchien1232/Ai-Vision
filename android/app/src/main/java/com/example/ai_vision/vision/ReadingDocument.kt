package com.example.ai_vision.vision

/** Full OCR text is preserved; short speech pages never cut a word or use cloud. */
data class ReadingDocument(val text: String, val pages: List<String>) {
    companion object {
        fun fromText(raw: String, pageCharacters: Int = 220): ReadingDocument {
            require(pageCharacters in 160..400)
            require(raw.length <= 32_000) { "Văn bản quá lớn để đọc trong một lượt." }
            val text = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
            val pages = mutableListOf<String>()
            var page = ""
            for (paragraph in text.split('\n')) {
                for (word in paragraph.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                    if (page.isNotEmpty() && page.length + word.length + 1 > pageCharacters) {
                        pages += page
                        page = ""
                    }
                    page = if (page.isEmpty()) word else "$page $word"
                    if (page.length >= pageCharacters || (page.length >= 100 && word.last() in ".!?;:")) {
                        pages += page
                        page = ""
                    }
                }
                if (page.isNotEmpty()) { pages += page; page = "" }
            }
            return ReadingDocument(text, pages)
        }
    }
}
