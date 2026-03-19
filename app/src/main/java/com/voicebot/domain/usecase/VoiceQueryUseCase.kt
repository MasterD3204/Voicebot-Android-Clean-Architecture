package com.voicebot.domain.usecase

import android.util.Log
import com.voicebot.domain.port.LlmEngine
import com.voicebot.domain.port.RagEngine
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

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
    private val bargeInDetector: BargeInDetector,
    private val isRagOnly: Boolean = false,   // ← RAG_ONLY mode: bypass LLM routing
    private val noThink: Boolean = false      // ← Qwen3 0.6B: append /no_think to every prompt
) {

    companion object {
        private const val TAG = "VoiceQueryUseCase"

        val RAG_KEYWORDS: Set<String> = setOf(
            "misa", "amis", "meinvoice", "misainvoice",
            "công ty", "doanh nghiệp", "tập đoàn",
            "chủ tịch", "giám đốc", "ceo", "ban lãnh đạo",
            "sản phẩm", "phần mềm", "giải pháp", "ứng dụng",
            "kế toán", "hóa đơn", "thuế", "lương", "nhân sự",
            "khách hàng", "đối tác", "trụ sở", "địa chỉ"
        )

        /** Lấy ngày hiện tại dạng gọn — offline, không cần quyền */
        private fun today(): String {
            val c = Calendar.getInstance()
            val dow = arrayOf("Chủ nhật","Thứ hai","Thứ ba","Thứ tư","Thứ năm","Thứ sáu","Thứ bảy")
            return "${dow[c.get(Calendar.DAY_OF_WEEK) - 1]}, ngày ${c.get(Calendar.DAY_OF_MONTH)} " +
                   "tháng ${c.get(Calendar.MONTH) + 1} năm ${c.get(Calendar.YEAR)}"
        }

        /**
         * System prompt dùng chung cho mọi nhánh.
         * Các placeholder {DATE} được thay khi gọi buildSystemBlock().
         */
        private const val SYSTEM_TEMPLATE = """Mày là MISA AVA, trợ lý ảo của công ty MISA. Hôm nay: {DATE}.
Quy tắc:
1. Chỉ chào khi đây là tin nhắn đầu tiên của cuộc trò chuyện.
2. Xưng hô nhất quán theo người dùng: nếu họ dùng "bạn/tôi" thì mày dùng "tôi/bạn"; nếu họ gọi mày bằng "em/anh/chị" thì mày xưng theo chiều ngược lại.
3. Trả lời bằng tiếng Việt, ngắn gọn, tự nhiên như hội thoại nói.
4. Dùng tối đa kiến thức nội tại: thơ, văn, kiến thức chung, lời khuyên sức khỏe cơ bản, v.v. đều trả lời được.
5. Chỉ từ chối khi câu hỏi cần dữ liệu thời gian thực (giá vàng, thời tiết, tỷ giá) hoặc thông tin nội bộ không có trong kiến thức. Khi từ chối: nói đúng một câu "Tôi không có thông tin đó." rồi nêu ngắn gọn mày có thể giúp gì."""

        private fun buildSystemBlock() = SYSTEM_TEMPLATE.replace("{DATE}", today())
    }

    // ── Prompt suffix — appended to every prompt when noThink = true ──────
    // Qwen3 0.6B defaults to thinking mode; /no_think disables it per-turn.
    private val promptSuffix = if (noThink) "\n/no_think" else ""

    private fun buildRagWithContextPrompt(query: String, contexts: List<String>): String {
        val ctx = contexts.mapIndexed { i, c -> "[${i+1}] $c" }.joinToString("\n")
        return "${buildSystemBlock()}\n\nTài liệu tham khảo:\n$ctx\n\nNgười dùng: $query$promptSuffix"
    }

    private fun buildRagNoContextPrompt(query: String) =
        "${buildSystemBlock()}\n\nNgười dùng: $query$promptSuffix"

    private fun buildGeneralPrompt(query: String) =
        "${buildSystemBlock()}\n\nNgười dùng: $query$promptSuffix"

    // ── Conversation turn tracking ────────────────────────────────────────
    private var generalTurnCount = 0
    private val MAX_GENERAL_TURNS = 3

    // ── Public API ────────────────────────────────────────────────────────

    suspend fun execute(rawQuery: String): QueryResult {
        val query      = rawQuery.trim()
        val queryLower = query.lowercase()

        // Bước 1 — Barge-in: phản hồi tức thì
        if (bargeInDetector.isAcknowledgment(queryLower)) {
            return QueryResult.Acknowledgment(BargeInDetector.ACKNOWLEDGMENT_RESPONSE)
        }

        if (!llmEngine.isReady()) return QueryResult.Error("LLM engine chưa sẵn sàng.")

        // Bước 2 — RAG_ONLY
        if (isRagOnly) {
            Log.i(TAG, "Nhánh RAG_ONLY")
            return QueryResult.LlmStream(llmEngine.chatStream(query))
        }

        // Bước 3 — Routing
        return if (containsRagKeyword(queryLower)) {
            handleWithRag(query, queryLower)
        } else {
            handleGeneral(query)
        }
    }

    fun resetHistory() {
        llmEngine.resetHistory()
        generalTurnCount = 0
    }

    // ── Routing ───────────────────────────────────────────────────────────

    private suspend fun handleWithRag(query: String, queryLower: String): QueryResult {
        val contexts = ragEngine.search(queryLower)
        llmEngine.resetHistory()   // RAG context lớn, không tích history giữa các turn

        return if (!contexts.isNullOrEmpty()) {
            Log.i(TAG, "RAG — ${contexts.size} context(s)")
            QueryResult.LlmStream(llmEngine.chatStream(buildRagWithContextPrompt(query, contexts)))
        } else {
            Log.i(TAG, "RAG — không có context, dùng LLM + system")
            QueryResult.LlmStream(llmEngine.chatStream(buildRagNoContextPrompt(query)))
        }
    }

    private fun handleGeneral(query: String): QueryResult {
        generalTurnCount++
        if (generalTurnCount > MAX_GENERAL_TURNS) {
            Log.i(TAG, "General turn $generalTurnCount > $MAX_GENERAL_TURNS — reset history")
            llmEngine.resetHistory()
            generalTurnCount = 1
        }
        Log.i(TAG, "General — lượt $generalTurnCount/$MAX_GENERAL_TURNS")
        return QueryResult.LlmStream(llmEngine.chatStream(buildGeneralPrompt(query)))
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    fun containsRagKeyword(query: String): Boolean =
        RAG_KEYWORDS.any { query.lowercase().contains(it) }
}

sealed class QueryResult {
    data class Acknowledgment(val response: String) : QueryResult()
    data class LlmStream(val flow: Flow<String>)    : QueryResult()
    data class Error(val message: String)           : QueryResult()
}