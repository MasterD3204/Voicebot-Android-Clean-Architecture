package com.voicebot.data.rag

import android.content.Context
import android.util.Log
import com.google.ai.edge.localagents.rag.memory.DefaultSemanticTextMemory
import com.google.ai.edge.localagents.rag.memory.SqliteVectorStore
import com.google.ai.edge.localagents.rag.models.Embedder
import com.google.ai.edge.localagents.rag.models.GemmaEmbeddingModel
import com.google.ai.edge.localagents.rag.retrieval.RetrievalConfig
import com.google.ai.edge.localagents.rag.retrieval.RetrievalConfig.TaskType
import com.google.common.collect.ImmutableList
import com.voicebot.domain.port.RagEngine
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RAG Engine sử dụng Google AI Edge RAG SDK với EmbeddingGemma-300m on-device.
 *
 * - Embedding: GemmaEmbeddingModel (on-device, GPU-accelerated)
 * - Vector Store: SqliteVectorStore (persistent, dimension = 768)
 * - Retrieval: Top-3 semantic search với cosine similarity
 *
 * @param context Android application context
 */
class EmbeddingRagEngine(
    private val context: Context
) : RagEngine {

    companion object {
        private const val TAG = "EmbeddingRagEngine"

        // ── Model paths (push vào device bằng adb) ──────────────────────
        // adb push embeddinggemma-300m_seq256_mixed-precision.tflite /data/local/tmp/
        // adb push sentencepiece.model /data/local/tmp/
        private const val EMBEDDING_MODEL_PATH =
            "/data/local/tmp/embeddinggemma-300m_seq256_mixed-precision.tflite"
        private const val TOKENIZER_MODEL_PATH =
            "/data/local/tmp/sentencepiece.model"

        // EmbeddingGemma output dimension
        private const val EMBEDDING_DIMENSION = 768

        // Sử dụng GPU cho embedding inference
        private const val USE_GPU = true

        // Retrieval config
        private const val TOP_K = 3
        private const val SIMILARITY_THRESHOLD = 0.0f // 0 = không lọc, trả hết top-K

        // Chunk separator dùng trong QA file
        private const val CHUNK_SEPARATOR = "<chunk_splitter>"
    }

    // ── RAG SDK components ───────────────────────────────────────────────
    private var embedder: Embedder<String>? = null
    private var semanticMemory: DefaultSemanticTextMemory? = null
    private var isInitialized = false

    /**
     * Khởi tạo RAG engine:
     * 1. Tạo GemmaEmbeddingModel (on-device embedder)
     * 2. Tạo SqliteVectorStore + DefaultSemanticTextMemory
     * 3. Load QA file từ assets, chunk và memorize
     *
     * @param qaFile  Đường dẫn file QA trong assets/ (ví dụ: "qa_database.txt")
     * @param vectorFile Không sử dụng cho engine này (giữ để tương thích interface).
     *                   Truyền chuỗi rỗng "" nếu không cần.
     */
    override suspend fun initialize(qaFile: String, vectorFile: String) {
        if (isInitialized) {
            Log.w(TAG, "Engine đã được khởi tạo, bỏ qua.")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Đang khởi tạo EmbeddingGemma model...")

                // 1. Tạo on-device embedder với EmbeddingGemma-300m
                embedder = GemmaEmbeddingModel(
                    EMBEDDING_MODEL_PATH,
                    TOKENIZER_MODEL_PATH,
                    USE_GPU
                )

                // 2. Tạo vector store + semantic memory
                val vectorStore = SqliteVectorStore(EMBEDDING_DIMENSION)
                semanticMemory = DefaultSemanticTextMemory(vectorStore, embedder!!)

                Log.i(TAG, "EmbeddingGemma model đã sẵn sàng. Đang load QA data...")

                // 3. Load và memorize QA data từ assets
                val chunks = loadChunksFromAssets(qaFile)
                if (chunks.isNotEmpty()) {
                    memorizeChunks(chunks)
                    Log.i(TAG, "Đã memorize ${chunks.size} chunks từ '$qaFile'")
                } else {
                    Log.w(TAG, "Không tìm thấy chunks trong file '$qaFile'")
                }

                isInitialized = true
                Log.i(TAG, "EmbeddingRagEngine khởi tạo thành công!")

            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khởi tạo EmbeddingRagEngine", e)
                release()
                throw RuntimeException("Không thể khởi tạo EmbeddingRagEngine: ${e.message}", e)
            }
        }
    }

    /**
    * Tìm kiếm semantic top-3 chunks phù hợp nhất với query.
    *
    * @param query Câu hỏi của người dùng
    * @return Danh sách tối đa 3 chunks có similarity cao nhất,
    *         hoặc null nếu không tìm thấy kết quả phù hợp.
    */
    override suspend fun search(query: String): List<String>? {
        if (!isInitialized || semanticMemory == null) {
            Log.e(TAG, "Engine chưa được khởi tạo. Gọi initialize() trước.")
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Đang tìm kiếm cho query: '$query'")

                val retrievalConfig = RetrievalConfig.create(
                    TOP_K,
                    SIMILARITY_THRESHOLD,
                    TaskType.QUESTION_ANSWERING
                )

                val results = semanticMemory!!
                    .retrieveMemoryItems(query, retrievalConfig)
                    .get() // blocking get trên IO dispatcher

                if (results.isNullOrEmpty()) {
                    Log.d(TAG, "Không tìm thấy kết quả phù hợp.")
                    return@withContext null
                }

                // MemoryQueryResult chứa .text() và .similarity()
                val topResults = results
                    .mapNotNull { result ->
                        val text = result.text()
                        if (!text.isNullOrBlank()) text else null
                    }
                    .take(TOP_K)

                Log.d(TAG, "Tìm thấy ${topResults.size} kết quả.")
                topResults.forEachIndexed { index, text ->
                    Log.d(TAG, "  [$index] similarity=${results[index].similarity()} → ${text.take(80)}...")
                }

                if (topResults.isEmpty()) null else topResults

            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi search", e)
                null
            }
        }
    }

    /**
     * Giải phóng tài nguyên: embedder, vector store, semantic memory.
     */
    override fun release() {
        try {
            // GemmaEmbeddingModel implements Closeable
            (embedder as? AutoCloseable)?.close()
            embedder = null
            semanticMemory = null
            isInitialized = false
            Log.i(TAG, "EmbeddingRagEngine đã được giải phóng.")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi release", e)
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /**
     * Load file từ assets và tách thành chunks dựa trên <chunk_splitter> separator.
     *
     * Format file QA:
     * ```
     * <chunk_splitter> Câu hỏi 1 và câu trả lời...
     * Nội dung tiếp theo của chunk 1
     * <chunk_splitter> Câu hỏi 2 và câu trả lời...
     * ```
     */
    private fun loadChunksFromAssets(filename: String): List<String> {
        val chunks = mutableListOf<String>()
        try {
            val reader = BufferedReader(InputStreamReader(context.assets.open(filename)))
            val sb = StringBuilder()

            reader.useLines { lines ->
                lines.forEach { line ->
                    if (line.startsWith(CHUNK_SEPARATOR)) {
                        // Lưu chunk trước đó (nếu có)
                        if (sb.isNotEmpty()) {
                            chunks.add(sb.toString().trim())
                        }
                        sb.clear()
                        sb.append(line.removePrefix(CHUNK_SEPARATOR).trim())
                    } else {
                        if (sb.isNotEmpty()) sb.append(" ")
                        sb.append(line)
                    }
                }
            }
            // Lưu chunk cuối cùng
            if (sb.isNotEmpty()) {
                chunks.add(sb.toString().trim())
            }
            reader.close()

        } catch (e: Exception) {
            Log.e(TAG, "Lỗi đọc file '$filename' từ assets", e)
        }

        return chunks.filter { it.isNotBlank() }
    }

    /**
     * Memorize danh sách chunks vào semantic memory (tạo embeddings + lưu vector store).
     * Sử dụng batch API để tối ưu performance.
     */
    private fun memorizeChunks(chunks: List<String>) {
        try {
            val immutableChunks = ImmutableList.copyOf(chunks)
            semanticMemory?.recordBatchedMemoryItems(immutableChunks)?.get()
            Log.d(TAG, "Đã memorize ${chunks.size} chunks thành công.")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi memorize chunks", e)
            throw e
        }
    }
}
