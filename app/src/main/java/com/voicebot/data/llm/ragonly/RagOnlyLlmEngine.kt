package com.voicebot.data.llm.ragonly

import android.content.Context
import android.util.Log
import com.google.ai.edge.localagents.rag.memory.DefaultSemanticTextMemory
import com.google.ai.edge.localagents.rag.memory.SqliteVectorStore
import com.google.ai.edge.localagents.rag.models.Embedder
import com.google.ai.edge.localagents.rag.models.GemmaEmbeddingModel
import com.google.ai.edge.localagents.rag.retrieval.RetrievalConfig
import com.google.ai.edge.localagents.rag.retrieval.RetrievalConfig.TaskType
import com.google.ai.edge.localagents.rag.retrieval.RetrievalRequest
import com.google.ai.edge.localagents.rag.retrieval.RetrievalResponse
import com.google.common.collect.ImmutableList
import com.voicebot.domain.port.LlmEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * LLM engine thay thế: RAG thuần dùng Gemma Embedding, không qua LLM.
 *
 * ── Thuật toán ──────────────────────────────────────────────────────────
 *   1. Load QA từ assets (format: "câu hỏi|câu trả lời")
 *   2. Memorize phần CÂU HỎI vào Gemma semantic memory
 *   3. Khi có query → retrieve top-1 câu hỏi khớp nhất
 *   4. Ngưỡng cosine similarity 0.85 — dưới ngưỡng trả "không biết"
 *   5. Map câu hỏi → câu trả lời và trả kết quả
 *
 * ── File QA (assets/qa_database.txt) ────────────────────────────────────
 *   câu hỏi 1|câu trả lời 1
 *   câu hỏi 2|câu trả lời 2
 *   (mỗi dòng 1 cặp, dấu | phân cách, dòng trống bỏ qua)
 *
 * ── Model trên thiết bị (/sdcard/Download/) ──────────────────────────────
 *   embeddinggemma-300M_seq1024_mixed-precision.tflite
 *   sentencepiece.model
 */
class RagOnlyLlmEngine(
    private val context: Context,
    private val qaFile: String = "qa_database.txt",
    // vectorFile không dùng — giữ để tương thích với EngineFactory
    private val vectorFile: String = ""
) : LlmEngine {

    companion object {
        private const val TAG = "RagOnlyLlmEngine"

        private const val EMBEDDING_MODEL_PATH =
            "/sdcard/Download/embeddinggemma-300M_seq1024_mixed-precision.tflite"
        private const val TOKENIZER_MODEL_PATH =
            "/sdcard/Download/sentencepiece.model"

        private const val EMBEDDING_DIMENSION = 768
        private const val USE_GPU             = true
        private const val TOP_K               = 1

        // Ngưỡng cosine similarity để chấp nhận kết quả
        // SDK lọc nội bộ — nếu không có gì vượt ngưỡng → trả danh sách rỗng
        private const val SIMILARITY_THRESHOLD = 0.6f

        private const val NO_ANSWER =
            "Xin lỗi, tôi không có thông tin về câu hỏi này."
    }

    // Map: question_lowercase → answer (giữ nguyên case gốc)
    private val questionToAnswer = LinkedHashMap<String, String>()

    private var embedder: Embedder<String>?               = null
    private var semanticMemory: DefaultSemanticTextMemory? = null

    @Volatile private var initialized = false

    // ── LlmEngine interface ───────────────────────────────────────────────

    override suspend fun init(): Boolean = withContext(Dispatchers.IO) {
        try {
            val t0 = System.currentTimeMillis()

            Log.i(TAG, "Đang khởi tạo GemmaEmbeddingModel...")
            embedder = GemmaEmbeddingModel(
                EMBEDDING_MODEL_PATH,
                TOKENIZER_MODEL_PATH,
                USE_GPU
            )

            val vectorStore = SqliteVectorStore(EMBEDDING_DIMENSION)
            semanticMemory  = DefaultSemanticTextMemory(vectorStore, embedder!!)

            val pairs = loadQaPairs()
            if (pairs.isEmpty()) {
                Log.w(TAG, "Không có QA pair nào trong '$qaFile'")
                return@withContext false
            }

            pairs.forEach { (q, a) -> questionToAnswer[q] = a }

            // Memorize toàn bộ câu hỏi (lowercase) một lần duy nhất
            val questions = ImmutableList.copyOf(pairs.map { it.first })
            semanticMemory!!.recordBatchedMemoryItems(questions).get()

            initialized = true
            Log.i(TAG, "RagOnlyLlmEngine sẵn sàng trong ${System.currentTimeMillis() - t0}ms " +
                    "— ${pairs.size} QA pairs")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Init thất bại", e)
            release()
            false
        }
    }

    override fun isReady() = initialized

    /**
     * Emit TỪNG TỪ riêng lẻ — KHÔNG cộng dồn.
     *
     * ❌ SAI (gây lặp chữ):
     *    emit("Chào")          → consumeStream append → "Chào"
     *    emit("Chào bạn")      → consumeStream append → "ChàoChào bạn"
     *    emit("Chào bạn tôi")  → consumeStream append → "ChàoChào bạnChào bạn tôi"
     *
     * ✅ ĐÚNG:
     *    emit("Chào ")   → consumeStream append → "Chào "
     *    emit("bạn ")    → consumeStream append → "Chào bạn "
     *    emit("tôi")     → consumeStream append → "Chào bạn tôi"
     */
    override fun chatStream(query: String): Flow<String> = flow {
        if (!initialized) {
            emit(NO_ANSWER)
            return@flow
        }

        val answer = withContext(Dispatchers.IO) { findAnswer(query) }
        Log.i(TAG, "RAG-Only answer: '${answer.take(100)}'")

        // Emit từng từ riêng lẻ
        answer.split(" ").forEach { word ->
            if (word.isNotEmpty()) emit("$word ")
        }
    }

    override fun release() {
        try { (embedder as? AutoCloseable)?.close() } catch (_: Exception) {}
        embedder       = null
        semanticMemory = null
        questionToAnswer.clear()
        initialized    = false
        Log.i(TAG, "Released")
    }

    override fun resetHistory() { /* RAG-only không có conversation history */ }

    // ── Core search ───────────────────────────────────────────────────────

    private fun findAnswer(query: String): String {
        val memory = semanticMemory ?: return NO_ANSWER
        return try {
            val config  = RetrievalConfig.create(TOP_K, SIMILARITY_THRESHOLD, TaskType.QUESTION_ANSWERING)
            val request = RetrievalRequest.create(query, config)

            val response: RetrievalResponse<String> = memory.retrieveResults(request).get()
            val entities = response.getEntities()

            if (entities.isNullOrEmpty()) {
                Log.d(TAG, "Không có kết quả vượt ngưỡng $SIMILARITY_THRESHOLD cho: '$query'")
                return NO_ANSWER
            }

            // Câu hỏi khớp nhất trả về từ semantic memory (đã memorize lowercase)
            val matchedQ = entities.first().getData()?.trim()?.lowercase() ?: return NO_ANSWER
            Log.d(TAG, "Khớp với câu hỏi: '$matchedQ'")

            // Tra cứu trực tiếp, fallback nếu có khoảng trắng thừa
            questionToAnswer[matchedQ]
                ?: questionToAnswer.entries.firstOrNull { it.key.trim() == matchedQ.trim() }?.value
                ?: NO_ANSWER

        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi tìm kiếm", e)
            NO_ANSWER
        }
    }

    // ── Load data ─────────────────────────────────────────────────────────

    /**
     * Đọc file QA từ assets.
     * Format: "câu hỏi|câu trả lời" — mỗi dòng một cặp.
     * Trả về List<Pair<question_lowercase, answer_original_case>>
     */
    private fun loadQaPairs(): List<Pair<String, String>> {
        val pairs = mutableListOf<Pair<String, String>>()
        try {
            context.assets.open(qaFile).bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    val t = line.trim()
                    if (t.isBlank()) return@forEachLine

                    val pipeIdx = t.indexOf('|')
                    if (pipeIdx == -1) return@forEachLine

                    val question = t.substring(0, pipeIdx).trim()
                    val answer   = t.substring(pipeIdx + 1).trim()

                    if (question.isNotEmpty() && answer.isNotEmpty()) {
                        // lowercase để khớp với kết quả trả về từ semantic memory
                        pairs.add(question.lowercase() to answer)
                    }
                }
            }
            Log.i(TAG, "Loaded ${pairs.size} QA pairs từ '$qaFile'")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi đọc '$qaFile'", e)
        }
        return pairs
    }
}