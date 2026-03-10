package com.voicebot.domain.usecase

/**
 * Detects short acknowledgment utterances so the bot can reply instantly
 * without going through the full RAG + LLM pipeline.
 *
 * ★ To extend: add keywords to [KEYWORDS].
 */
class BargeInDetector {

    companion object {
        const val ACKNOWLEDGMENT_RESPONSE =
            "Rất vui được giúp đỡ bạn, nếu bạn còn thắc mắc gì, vui lòng cho tôi biết nhé."

        private val KEYWORDS: Set<String> = setOf(
            "ok", "ok luôn", "okay",
            "được rồi", "được",
            "ừ", "ừm", "uhm", "uh huh",
            "vâng", "dạ", "dạ vâng",
            "cảm ơn", "cám ơn", "thanks", "thank you",
            "tốt", "tốt rồi", "hiểu rồi", "rõ rồi", "biết rồi",
            "thôi", "thôi được rồi", "không cần nữa", "đủ rồi", "ổn rồi"
        )
    }

    /**
     * Returns true when [text] (normalised) matches an acknowledgment keyword.
     * Accepts bare keyword or keyword + " nhé".
     */
    fun isAcknowledgment(text: String): Boolean {
        val norm = text.trim().lowercase()
            .removeSuffix(".").removeSuffix("!").trim()
        return KEYWORDS.any { kw -> norm == kw || norm == "$kw nhé" }
    }
}
