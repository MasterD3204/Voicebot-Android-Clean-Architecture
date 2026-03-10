package com.voicebot.domain.port

/**
 * Port for Retrieval-Augmented Generation engines.
 * Implementations: FastTextRagEngine, NoOpRagEngine
 *
 * A RAG engine loads a QA database and a vector index,
 * then returns the best-matching answer for a query — or null if no match.
 */
interface RagEngine {
    /**
     * Load [qaFile] (question-answer pairs) and [vectorFile] (word embeddings).
     * Both are Android asset paths relative to the assets/ folder.
     * Must complete before [search] is called.
     */
    suspend fun initialize(qaFile: String, vectorFile: String)

    /**
     * Semantic search over loaded QA pairs.
     * @return top matching answer strings (up to 3) above confidence threshold,
     *         or null when no match is confident enough.
     *         Results are ordered best-first for use as LLM context.
     */
    suspend fun search(query: String): List<String>?

    fun release()
}