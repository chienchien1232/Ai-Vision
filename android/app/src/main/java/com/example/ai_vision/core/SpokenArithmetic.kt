package com.example.ai_vision.core

/** Small, deterministic grammar for STT numbers; unsupported numeric expressions ask for clarification. */
internal object SpokenArithmetic {
    private val digits = Regex("-?\\d{1,18}(?:[.,]\\d{1,12})?")
    private val expression = Regex("^(?:tinh |calculate )?(.+?)\\s*(cong|tru|nhan|chia|plus|minus|times|divided by|[+*/×÷-])\\s*(.+?)(?: bang bao nhieu| bang may| equals what)?$")
    private val viUnits = mapOf("khong" to 0, "mot" to 1, "hai" to 2, "ba" to 3, "bon" to 4,
        "tu" to 4, "nam" to 5, "lam" to 5, "sau" to 6, "bay" to 7, "tam" to 8, "chin" to 9)
    private val enUnits = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val enTens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private val numberWords = viUnits.keys + enUnits + enTens.keys + setOf("tram", "muoi", "linh", "le",
        "nghin", "ngan", "trieu", "ty", "hundred", "and", "thousand", "million", "billion", "am", "negative", "minus", "phay", "point")
    private val numericToken = Regex("-?\\d+(?:[.,]\\d+)?")
    private const val CLARIFY = "Chưa rõ phép tính. Hãy nói hai số và cộng, trừ, nhân hoặc chia; ví dụ hai cộng ba."

    fun route(normalized: String): AppIntent? {
        val match = expression.matchEntire(normalized) ?: run {
            val incomplete = Regex("^(.+?) (cong|tru|nhan|chia|plus|minus|times|divided by|[+*/×÷-])$")
                .matchEntire(normalized)
            if (normalized == "tinh" || normalized == "calculate" || normalized.startsWith("tinh ") ||
                normalized.startsWith("calculate ") || incomplete?.let { number(it.groupValues[1]) != null } == true) {
                return AppIntent.Clarification(CLARIFY)
            }
            return null
        }
        val (left, operator, right) = match.destructured
        // Word operators must be whole words (e.g. "nhan" inside "nhan vat" is not arithmetic).
        if (operator.first().isLetter() &&
            !Regex("(?:^| )${Regex.escape(operator)}(?: |$)").containsMatchIn(normalized)) return null
        val a = number(left)
        val b = number(right)
        if (a != null && b != null) return AppIntent.Calculate(a, operator, b)
        // Do not classify ordinary questions containing an operator word as calculations.
        if (a != null || b != null || (numericPhrase(left) && numericPhrase(right)) ||
            normalized.startsWith("tinh ") || normalized.startsWith("calculate ")) {
            return AppIntent.Clarification(CLARIFY)
        }
        return null
    }

    private fun numericPhrase(raw: String): Boolean = raw.trim().split(' ').all { it in numberWords || numericToken.matches(it) }

    private fun number(raw: String): String? {
        val text = raw.trim()
        if (digits.matches(text)) return text.replace(',', '.')
        val sign = listOf("am ", "minus ", "negative ").firstOrNull { text.startsWith(it) }
        val unsigned = if (sign == null) text else text.removePrefix(sign)
        val parts = unsigned.split(Regex(" phay | point "))
        if (parts.size > 2) return null
        val integer = parts[0].toIntOrNull()?.takeIf { it in 0..999 }
            ?: viInteger(parts[0].split(' ')) ?: enInteger(parts[0].split(' ')) ?: return null
        val fraction = if (parts.size == 2) {
            val words = parts[1].split(' ')
            if (words.size > 12) return null
            words.map { word ->
                viUnits[word] ?: enUnits.indexOf(word).takeIf { it in 0..9 }
                ?: word.toIntOrNull()?.takeIf { word.length == 1 && it in 0..9 } ?: return null
            }.joinToString("")
        } else null
        return (if (sign == null) "" else "-") + integer + (fraction?.let { ".$it" } ?: "")
    }

    private fun viInteger(words: List<String>): Int? {
        if (words.size == 1) return viUnits[words[0]]
        if (words.size >= 2 && words[1] == "tram") {
            val hundreds = viUnits[words[0]]?.takeIf { it in 1..9 } ?: return null
            var rest = words.drop(2)
            if (rest.isEmpty()) return hundreds * 100
            if (rest.first() in setOf("linh", "le")) {
                rest = rest.drop(1)
                return if (rest.size == 1) viUnits[rest[0]]?.takeIf { it in 1..9 }?.let { hundreds * 100 + it } else null
            }
            return viTens(rest)?.let { hundreds * 100 + it }
        }
        return viTens(words)
    }

    private fun viTens(words: List<String>): Int? {
        val tens: Int
        val rest: List<String>
        if (words.firstOrNull() == "muoi") { tens = 10; rest = words.drop(1) }
        else {
            if (words.size < 2 || words[1] != "muoi") return null
            tens = (viUnits[words[0]]?.takeIf { it in 2..9 } ?: return null) * 10
            rest = words.drop(2)
        }
        return when (rest.size) {
            0 -> tens
            1 -> viUnits[rest[0]]?.takeIf { it in 1..9 }?.let { tens + it }
            else -> null
        }
    }

    private fun enInteger(words: List<String>): Int? {
        if (words.size == 1) return enUnits.indexOf(words[0]).takeIf { it >= 0 } ?: enTens[words[0]]
        if (words.size >= 2 && words[1] == "hundred") {
            val hundreds = enUnits.indexOf(words[0]).takeIf { it in 1..9 } ?: return null
            val rest = words.drop(2).let { if (it.firstOrNull() == "and") it.drop(1) else it }
            if (rest.isEmpty()) return if (words.last() == "hundred") hundreds * 100 else null
            return enInteger(rest)?.takeIf { it in 1..99 }?.let { hundreds * 100 + it }
        }
        if (words.size != 2) return null
        val tens = enTens[words[0]] ?: return null
        return enUnits.indexOf(words[1]).takeIf { it in 1..9 }?.let { tens + it }
    }
}
