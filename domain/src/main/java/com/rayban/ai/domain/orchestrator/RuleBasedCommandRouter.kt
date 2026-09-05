package com.rayban.ai.domain.orchestrator

import java.text.Normalizer
import java.util.Locale

class RuleBasedCommandRouter : CommandRouter {

    override fun route(request: CommandRequest): CommandMatch {
        val raw = request.utterance.trim()
        if (raw.isEmpty()) {
            return CommandMatch.Rejected(CommandError.EmptyInput)
        }
        val normalized = stripPrefixes(normalize(raw))
        val folded = foldToAscii(normalized)

        if (matchesAny(folded, STOP_EXACT, exact = true) || matchesAny(folded, STOP_CONTAINS, exact = false)) {
            return CommandMatch.Matched(AssistantCommand.Stop)
        }
        if (matchesAny(folded, REPEAT_EXACT, exact = true)) {
            return CommandMatch.Matched(AssistantCommand.RepeatLastAnswer)
        }
        if (matchesAny(folded, UNSUPPORTED_CONTAINS, exact = false)) {
            return CommandMatch.Rejected(CommandError.UnsupportedAction)
        }

        val matchedKinds = linkedSetOf<CommandKind>()
        for ((kind, phrases) in ACTION_PHRASES) {
            if (matchesAny(folded, phrases, exact = false)) {
                matchedKinds += kind
            }
        }

        return when (matchedKinds.size) {
            0 -> CommandMatch.Matched(AssistantCommand.OpenQuestion(raw))
            1 -> CommandMatch.Matched(ACTION_COMMANDS.getValue(matchedKinds.first()))
            else -> CommandMatch.Rejected(CommandError.Ambiguous(matchedKinds.toSet()))
        }
    }

    private fun matchesAny(text: String, phrases: List<String>, exact: Boolean): Boolean =
        if (exact) {
            text in phrases
        } else {
            phrases.any { it in text }
        }

    private fun normalize(raw: String): String {
        var s = raw.lowercase(Locale.ROOT).trim()
        s = s.replace("'", "")
        s = s.replace("đ", "d")
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
        s = s.replace(Regex("\\p{Mn}+"), "")
        s = s.replace(Regex("[^a-z0-9\\s]"), " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun foldToAscii(text: String): String = text

    private fun stripPrefixes(text: String): String {
        var current = text
        repeat(MAX_PREFIX_STRIP) {
            val prefix = POLITE_PREFIXES.firstOrNull { current.startsWith("$it ") }
            if (prefix == null) {
                return current
            }
            current = current.removePrefix("$prefix ").trim()
        }
        return current
    }

    private companion object {
        const val MAX_PREFIX_STRIP = 2

        val POLITE_PREFIXES = listOf(
            "please",
            "can you",
            "could you",
            "would you",
            "will you",
            "lam on",
            "giup toi",
            "giup minh",
            "cho minh",
            "hay",
        )

        val STOP_EXACT = listOf(
            "stop",
            "cancel",
            "never mind",
            "nevermind",
            "stop it",
            "dung",
            "dung lai",
            "dung lai di",
            "huy",
            "huy bo",
            "thoi",
        )

        val STOP_CONTAINS = listOf(
            "stop recording",
            "stop speaking",
            "stop listening",
            "stop the recording",
            "dung quay",
            "huy quay",
        )

        val REPEAT_EXACT = listOf(
            "repeat",
            "repeat that",
            "repeat the answer",
            "repeat last answer",
            "repeat the last answer",
            "say that again",
            "say it again",
            "what did you say",
            "lap lai",
            "lap lai di",
            "noi lai",
            "noi lai di",
            "lap lai cau tra loi",
            "noi lai cau tra loi",
            "cau vua noi gi",
        )

        val UNSUPPORTED_CONTAINS = listOf(
            "send a message",
            "send a text",
            "send an email",
            "send email",
            "make a call",
            "call someone",
            "gui tin nhan",
            "gui email",
            "goi dien",
            "delete",
            "xoa anh",
            "xoa video",
            "xoa di",
            "xoa no",
            "chia se",
            "share this",
            "share it",
            "mo youtube",
            "open youtube",
            "phat nhac",
            "play music",
            "chi duong",
            "navigate to",
            "dat lich",
            "set a reminder",
            "set a timer",
        )

        val ACTION_PHRASES: List<Pair<CommandKind, List<String>>> = listOf(
            CommandKind.RecordVideo to listOf(
                "quay video",
                "quay clip",
                "quay hinh",
                "quay mot doan",
                "quay giup toi",
                "quay mot video",
                "quay mot clip",
                "bat dau quay",
                "record a video",
                "record video",
                "start recording",
                "start a video",
                "start video",
                "film a video",
            ),
            CommandKind.TakePhoto to listOf(
                "chup anh",
                "chup hinh",
                "chup mot tam",
                "chup tam anh",
                "chup giup toi",
                "chup cho toi",
                "chup ho toi",
                "chup dum toi",
                "chup nhanh",
                "take a photo",
                "take a picture",
                "take photo",
                "take picture",
                "take a snap",
                "snap a photo",
                "snap a picture",
                "snap a pic",
                "capture a photo",
                "capture photo",
            ),
            CommandKind.ReadText to listOf(
                "doc chu",
                "doc text",
                "doc van ban",
                "doc ho chu",
                "doc bien bao",
                "bien bao ghi gi",
                "bien nay ghi",
                "bang nay ghi",
                "ghi gi",
                "read the text",
                "read this text",
                "read the sign",
                "read the label",
                "read the menu",
                "read this",
                "what does it say",
                "what does this say",
            ),
            CommandKind.SummarizeScene to listOf(
                "tom tat",
                "tom luoc",
                "noi tom tat",
                "summarize",
                "summarise",
                "give me a summary",
                "summary of the scene",
            ),
            CommandKind.DescribeScene to listOf(
                "nhin thay gi",
                "truoc mat co gi",
                "co gi truoc mat",
                "truoc minh co gi",
                "mo ta canh",
                "mo ta xung quanh",
                "mo ta phia truoc",
                "mo ta truoc mat",
                "phia truoc co gi",
                "xung quanh co gi",
                "what am i looking at",
                "what do you see",
                "what do i see",
                "what is in front of me",
                "whats in front of me",
                "whats around me",
                "describe the scene",
                "describe my surroundings",
                "describe what you see",
                "describe in front of me",
            ),
            CommandKind.IdentifyObject to listOf(
                "cai nay la gi",
                "cai do la gi",
                "cai kia la gi",
                "cai nay la vat gi",
                "la cai gi",
                "vat nay la gi",
                "vat gi",
                "day la gi",
                "nhan dien",
                "nhan dien vat",
                "what is this",
                "whats this",
                "what is that",
                "whats that",
                "what is it",
                "identify this",
                "identify that",
            ),
        )

        val ACTION_COMMANDS: Map<CommandKind, AssistantCommand> = mapOf(
            CommandKind.DescribeScene to AssistantCommand.DescribeScene,
            CommandKind.IdentifyObject to AssistantCommand.IdentifyObject,
            CommandKind.ReadText to AssistantCommand.ReadText,
            CommandKind.SummarizeScene to AssistantCommand.SummarizeScene,
            CommandKind.TakePhoto to AssistantCommand.TakePhoto,
            CommandKind.RecordVideo to AssistantCommand.RecordVideo,
        )
    }
}