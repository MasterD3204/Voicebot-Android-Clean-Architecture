package com.voicebot.domain.usecase

import com.voicebot.domain.port.LlmEngine
import com.voicebot.domain.port.RagEngine
import kotlinx.coroutines.flow.Flow

/**
 * Core business logic for processing a voice query.
 *
 * Priority order:
 *   1. Barge-in / acknowledgment  → instant canned reply
 *   2. RAG hit (top-3 docs ≥ 0.8) → augmented LLM prompt with context
 *   3. LLM streaming              → plain generative fallback
 *
 * Pure Kotlin — zero Android imports, fully unit-testable.
 */
class VoiceQueryUseCase(
    private val ragEngine: RagEngine,
    private val llmEngine: LlmEngine,
    private val bargeInDetector: BargeInDetector
) {
    suspend fun execute(rawQuery: String): QueryResult {
        val query = rawQuery.trim()
        val queryLower = query.lowercase()

        // Step 1: Barge-in check
        if (bargeInDetector.isAcknowledgment(queryLower)) {
            return QueryResult.Acknowledgment(BargeInDetector.ACKNOWLEDGMENT_RESPONSE)
        }

        if (!llmEngine.isReady()) {
            return QueryResult.Error("LLM engine chưa sẵn sàng.")
        }

        // Step 2: RAG — nếu tìm được context thì augment prompt trước khi đưa vào LLM
        val ragContexts = ragEngine.search(queryLower)
        val finalPrompt = if (!ragContexts.isNullOrEmpty()) {
            buildAugmentedPrompt(query, ragContexts)
        } else {
            query
        }

        // Step 3: LLM (có hoặc không có context)
        return QueryResult.LlmStream(llmEngine.chatStream(finalPrompt))
    }

    /**
     * Gộp các đoạn context từ RAG thành prompt augmented.
     * Format rõ ràng giúp LLM ưu tiên dùng context thay vì hallucinate.
     */
    private fun buildAugmentedPrompt(query: String, contexts: List<String>): String {
        val contextBlock = contexts
            .mapIndexed { i, ctx -> "[${i + 1}] $ctx" }
            .joinToString("\n")
        return """Dựa vào các thông tin sau để trả lời:
$contextBlock

Câu hỏi: $query"""
    }
}

/** Discriminated union of all possible query outcomes */
sealed class QueryResult {
    data class Acknowledgment(val response: String) : QueryResult()
    data class RagHit(val response: String) : QueryResult()
    data class LlmStream(val flow: Flow<String>) : QueryResult()
    data class Error(val message: String) : QueryResult()
}