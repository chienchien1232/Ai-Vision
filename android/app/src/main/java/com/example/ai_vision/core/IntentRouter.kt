package com.example.ai_vision.core

import java.util.Locale
import java.text.Normalizer

sealed interface AppIntent {
    data object Ping : AppIntent
    data object Status : AppIntent
    data object Capture : AppIntent
    data object ReadText : AppIntent
    data object ReadNext : AppIntent
    data object StartVideo : AppIntent
    data object StopVideo : AppIntent
    data object StopSpeaking : AppIntent
    data object Replay : AppIntent
    data class LocalQuery(val kind: Kind) : AppIntent {
        enum class Kind { TIME, DATE, GREETING, HELP }
    }
    data class Calculate(val left: String, val operator: String, val right: String) : AppIntent
    data class Clarification(val message: String, val topic: Topic? = null) : AppIntent {
        enum class Topic { READ_OR_DESCRIBE }
    }
    data class UnsupportedLocal(val feature: String) : AppIntent
    data class Question(val text: String, val needsFreshPhoto: Boolean) : AppIntent
}

/** Routes local glasses/phone transcripts; never calls a cloud classifier. */
object IntentRouter {
    fun normalize(text: String): String = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace('đ', 'd')
        .replace(Regex("\\s+"), " ").trim().trimEnd('.', '?', '!')

    fun route(raw: String, wakeWord: String = ""): AppIntent {
        val text = raw.trim().replace(Regex("\\s+"), " ")
        require(text.isNotEmpty() && text.length <= 4000) { "Input must contain 1..4000 characters" }
        var normalized = normalize(text)
        val wake = normalize(wakeWord)
        if (wake.isNotBlank()) normalized = normalized.replace(Regex("^${Regex.escape(wake)}[, ]+"), "")
        repeat(3) { normalized = normalized.replace(Regex("^(kinh oi[, ]+|hay |lam on |vui long |please )"), "") }
        normalized = normalized
            .replace(Regex(" (nhe|nha|please)$"), "")
        val accented = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFC)
        // Vietnamese "không" is also a question particle and the number zero.
        // Negation must target an action, rather than merely occur in a question.
        val negatedAction = Regex("^(?:(?:toi|minh|i) )?(?:khong(?: duoc| can| muon)?|do not|don't|dont|never) (?:chup|quay|doc|phat|noi|bat|dung|tang|giam|kiem tra|take|capture|record|read|play|start|stop|turn|check|speak)\\b")
        if (Regex("(?<![\\p{L}\\p{N}])đừng(?![\\p{L}\\p{N}])").containsMatchIn(accented) || negatedAction.containsMatchIn(normalized)) {
            return AppIntent.Clarification("Không thực hiện lệnh. Hãy nói yêu cầu bạn muốn thực hiện.")
        }
        // STT without diacritics cannot distinguish “đừng” from “dừng”. Neither
        // interpretation should execute a following capture/read/play action.
        if (Regex("^dung (chup|phat|bat dau)\\b").containsMatchIn(normalized) || normalized.startsWith("dung doc ")) {
            return AppIntent.Clarification("Chưa rõ bạn muốn dừng hay thực hiện lệnh. Hãy nói lại một yêu cầu.")
        }
        return when {
            normalized in setOf("ping", "kiem tra ket noi") -> AppIntent.Ping
            normalized in setOf("trang thai kinh", "get status", "status", "kiem tra trang thai kinh") -> AppIntent.Status
            Regex("^(chup( giup| cho)?( toi| minh)?( mot| tam)? (anh|hinh)( giup| cho)?( toi| minh)?|take( a)? photo|capture)$").matches(normalized) -> AppIntent.Capture
            normalized in setOf("doc tiep", "doc tiep van ban", "read next", "continue reading") -> AppIntent.ReadNext
            Regex("^(doc (chu|van ban|noi dung|nhan|bien bao|trang sach)( (nay|truoc mat( toi)?|(tren|trong) (anh( nay)?|nhan|bien bao|trang sach)))?( (giup|cho)( toi| minh)?)?|read (text|this|this text|the text|the label|the sign))$").matches(normalized) -> AppIntent.ReadText
            normalized in setOf("quay video", "bat dau quay video", "bat dau quay", "start video", "record video") -> AppIntent.StartVideo
            normalized in setOf("dung quay video", "dung quay", "dung video", "stop video", "stop recording video") -> AppIntent.StopVideo
            normalized in setOf("dung", "stop", "dung noi", "dung doc", "im lang", "stop speaking") -> AppIntent.StopSpeaking
            normalized in setOf("repeat", "phat lai", "noi lai", "doc lai") -> AppIntent.Replay
            normalized in setOf("volume up", "volume down", "tang am luong", "giam am luong") ->
                AppIntent.UnsupportedLocal("Điều khiển âm lượng bằng giọng nói chờ Local AI trên kính; không gửi Gemini.")
            normalized in setOf("battery status", "muc pin", "pin con bao nhieu", "trang thai pin") ->
                AppIntent.UnsupportedLocal("Kính chưa có phần cứng đo mức pin, chưa thể báo phần trăm pin.")
            normalized in setOf("bat dau ghi am", "dung ghi am", "start recording", "stop recording") ->
                AppIntent.UnsupportedLocal("Audio recording protocol is not available yet")
            normalized in setOf("may gio", "may gio roi", "bay gio la may gio", "what time is it", "time") -> AppIntent.LocalQuery(AppIntent.LocalQuery.Kind.TIME)
            normalized in setOf("hom nay ngay may", "hom nay la ngay may", "ngay hom nay", "what is today's date", "date") -> AppIntent.LocalQuery(AppIntent.LocalQuery.Kind.DATE)
            normalized in setOf("xin chao", "chao", "hello", "hi") -> AppIntent.LocalQuery(AppIntent.LocalQuery.Kind.GREETING)
            normalized in setOf("tro giup", "huong dan", "help", "ban lam duoc gi") -> AppIntent.LocalQuery(AppIntent.LocalQuery.Kind.HELP)
            else -> {
                SpokenArithmetic.route(normalized)?.let { return it }
                if (normalized in setOf("doc", "doc giup toi", "doc giup minh", "xem giup toi", "xem cai nay", "giup toi voi cai nay", "read", "look at this", "help me with this")) {
                    return AppIntent.Clarification("Bạn muốn đọc chữ hay mô tả cảnh? Hãy nói đọc chữ hoặc mô tả cảnh.",
                        AppIntent.Clarification.Topic.READ_OR_DESCRIBE)
                }
                if (Regex("^(chup|quay|bat dau quay|dung quay|take photo|start video)\\b").containsMatchIn(normalized)) {
                    return AppIntent.Clarification("Chưa rõ lệnh. Hãy nói chụp ảnh, quay video hoặc dừng quay.")
                }
                val needsPhoto = listOf("truoc mat", "nhin thay gi", "nhin giup", "mo ta canh", "mo ta can phong", "mo ta phong", "canh nay", "buc anh", "anh nay", "doc chu", "day la", "day co phai", "cai nay", "vat nay", "vat ben trai", "vat ben phai", "ben trai toi", "ben phai toi", "what is in front", "what do you see", "describe the scene", "describe the room", "look for me", "on my left", "on my right", "this photo", "what is this", "is this", "read this")
                    .any { Regex("(?:^| )${Regex.escape(it)}(?: |$)").containsMatchIn(normalized) }
                AppIntent.Question(text, needsPhoto)
            }
        }
    }
}
