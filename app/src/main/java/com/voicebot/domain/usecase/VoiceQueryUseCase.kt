package com.voicebot.domain.usecase

import android.util.Log
import com.voicebot.domain.port.LlmEngine
import com.voicebot.domain.port.RagEngine
import kotlinx.coroutines.flow.Flow

/**
 * Core business logic xử lý voice query theo 3 bước:
 *
 *   1. Barge-in check      → phản hồi tức thì (không tốn LLM)
 *   2. Keyword detection   → quyết định dùng RAG hay LLM thuần
 *      • Có keyword MISA   → tìm RAG, đưa context vào prompt cho LLM
 *      • Không có keyword  → LLM thuần với prompt tổng quát
 *
 * Lịch sử hội thoại được giữ nguyên cho cả 2 nhánh trong cùng phiên
 * bởi vì LlmEngine (LiteRT) tự quản lý context window qua Conversation.
 * Gọi [resetHistory] khi user clear chat để xóa ngữ cảnh.
 *
 * Pure Kotlin — không có Android import, dễ unit test.
 */
class VoiceQueryUseCase(
    private val ragEngine: RagEngine,
    private val llmEngine: LlmEngine,
    private val bargeInDetector: BargeInDetector
) {

    companion object {
        private const val TAG = "VoiceQueryUseCase"

        /**
         * Từ khóa kích hoạt RAG.
         * Thêm/bớt tại đây mà không cần thay đổi logic.
         */
        val RAG_KEYWORDS: Set<String> = setOf(
            "misa", "amis", "meinvoice", "misainvoice","misa",
            "công ty", "doanh nghiệp", "tập đoàn",
            "chủ tịch", "giám đốc", "ceo", "ban lãnh đạo",
            "sản phẩm", "phần mềm", "giải pháp", "ứng dụng",
            "kế toán", "hóa đơn", "thuế", "lương", "nhân sự",
            "khách hàng", "đối tác", "trụ sở", "địa chỉ", "xin chào"
        )

        /**
         * Prompt cho nhánh RAG — yêu cầu LLM bám sát context được cung cấp.
         */
        private const val PROMPT_RAG_WITH_CONTEXT = """Bạn là trợ lý ảo chính thức của MISA. \
Dựa vào thông tin tham khảo bên dưới để trả lời câu hỏi một cách chính xác, ngắn gọn bằng tiếng Việt. \
Chỉ sử dụng thông tin được cung cấp, không bịa thêm."""

        /**
         * Prompt khi có keyword nhưng RAG không tìm được kết quả.
         */
        private const val PROMPT_RAG_NO_CONTEXT = """Bạn là trợ lý ảo chính thức của MISA. \
Hãy trả lời câu hỏi ngắn gọn bằng tiếng Việt dựa trên kiến thức của bạn về MISA."""

        /**
         * Prompt cho nhánh LLM thuần — tổng quát, không ràng buộc domain.
         */
        private const val PROMPT_GENERAL = """Bạn là trợ lý ảo thông minh. \
Hãy trả lời câu hỏi sau một cách tự nhiên, ngắn gọn bằng tiếng Việt."""
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Xử lý query và trả về kết quả phù hợp.
     * Lịch sử hội thoại được duy trì tự động bởi LlmEngine.
     */
    suspend fun execute(rawQuery: String): QueryResult {
        val query      = rawQuery.trim()
        val queryLower = query.lowercase()

        // Bước 1 — Barge-in: phản hồi tức thì
        if (bargeInDetector.isAcknowledgment(queryLower)) {
            return QueryResult.Acknowledgment(BargeInDetector.ACKNOWLEDGMENT_RESPONSE)
        }

        if (!llmEngine.isReady()) return QueryResult.Error("LLM engine chưa sẵn sàng.")

        // Bước 2 — Keyword check: RAG hay LLM thuần?
        return if (containsRagKeyword(queryLower)) {
            handleWithRag(query, queryLower)
        } else {
            handleGeneral(query)
        }
    }

    /** Xóa ngữ cảnh hội thoại, bắt đầu phiên mới. */
    fun resetHistory() = llmEngine.resetHistory()

    // ── Routing ───────────────────────────────────────────────────────────

    private suspend fun handleWithRag(query: String, queryLower: String): QueryResult {
        val contexts = ragEngine.search(queryLower)

        return if (!contexts.isNullOrEmpty()) {
            Log.i(TAG, "Nhánh RAG — ${contexts.size} context(s) tìm được")
            val prompt = buildRagPrompt(query, contexts)
            QueryResult.LlmStream(llmEngine.chatStream(prompt))
        } else {
            Log.i(TAG, "Nhánh RAG — không tìm được context, dùng LLM + gợi ý domain")
            val prompt = "$PROMPT_RAG_NO_CONTEXT\n\nCâu hỏi: $query"
            QueryResult.LlmStream(llmEngine.chatStream(prompt))
        }
    }

    private fun handleGeneral(query: String): QueryResult {
        Log.i(TAG, "Nhánh General — LLM thuần")
        val prompt = "$PROMPT_GENERAL\n\nCâu hỏi: $query"
        return QueryResult.LlmStream(llmEngine.chatStream(prompt))
    }

    // ── Prompt builders ───────────────────────────────────────────────────

    /**
     * Gộp các đoạn context từ RAG thành prompt augmented.
     * Mỗi context là phần trả lời (sau dấu |) từ QA database.
     */
    private fun buildRagPrompt(query: String, contexts: List<String>): String {
        val contextBlock = contexts
            .mapIndexed { i, ctx -> "[${i + 1}] $ctx" }
            .joinToString("\n")

        return """$PROMPT_RAG_WITH_CONTEXT

Thông tin tham khảo:
$contextBlock

Câu hỏi: $query"""
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    fun containsRagKeyword(query: String): Boolean =
        RAG_KEYWORDS.any { query.lowercase().contains(it) }
}

/** Discriminated union của tất cả kết quả có thể */
sealed class QueryResult {
    data class Acknowledgment(val response: String) : QueryResult()
    data class LlmStream(val flow: Flow<String>)    : QueryResult()
    data class Error(val message: String)           : QueryResult()
}