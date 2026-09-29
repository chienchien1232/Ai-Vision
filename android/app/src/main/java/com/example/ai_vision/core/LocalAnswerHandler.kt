package com.example.ai_vision.core

import java.math.BigDecimal
import java.math.MathContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

object LocalAnswerHandler {
    fun answer(intent: AppIntent, languageTag: String, now: Date = Date()): String {
        val english = languageTag == "en-US"
        val locale = Locale.forLanguageTag(languageTag)
        return when (intent) {
            is AppIntent.LocalQuery -> when (intent.kind) {
                AppIntent.LocalQuery.Kind.TIME -> (if (english) "It is " else "Bây giờ là ") + DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(now)
                AppIntent.LocalQuery.Kind.DATE -> DateFormat.getDateInstance(DateFormat.FULL, locale).format(now)
                AppIntent.LocalQuery.Kind.GREETING -> if (english) "Hello. How can I help?" else "Xin chào. Bạn cần mình giúp gì?"
                AppIntent.LocalQuery.Kind.HELP -> if (english) "You can take a photo, start or stop video, check the glasses, ask the time or calculate. Other questions can use AI." else "Bạn có thể chụp ảnh, quay hoặc dừng video, kiểm tra kính, hỏi giờ và tính toán. Câu hỏi khác có thể dùng AI."
            }
            is AppIntent.Calculate -> {
                val left = BigDecimal(intent.left.replace(',', '.'))
                val right = BigDecimal(intent.right.replace(',', '.'))
                val result = when (intent.operator) {
                    "+", "cong", "plus" -> left + right
                    "-", "tru", "minus" -> left - right
                    "*", "×", "nhan", "times" -> left * right
                    "/", "÷", "chia", "divided by" -> {
                        if (right.compareTo(BigDecimal.ZERO) == 0) return if (english) "Cannot divide by zero." else "Không thể chia cho không."
                        left.divide(right, MathContext.DECIMAL64)
                    }
                    else -> error("Unsupported calculation operator")
                }
                result.stripTrailingZeros().toPlainString()
            }
            is AppIntent.Clarification -> intent.message
            else -> error("Intent has no local answer")
        }
    }
}
