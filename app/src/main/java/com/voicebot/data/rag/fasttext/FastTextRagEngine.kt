package com.voicebot.data.rag.fasttext

import android.content.Context
import android.util.Log
import com.voicebot.domain.port.RagEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.security.MessageDigest
import java.util.StringTokenizer
import kotlin.coroutines.coroutineContext
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/**
 * RAG engine kết hợp BM25 + Cosine similarity (FastText embeddings).
 *
 * ── Thuật toán ────────────────────────────────────────────────────────────
 * 1. BM25 via Inverted Index  → O(K) thay vì O(N) — chỉ score docs có query terms
 * 2. Cosine similarity        → trên candidates từ BM25 (pre-normalized → dot product)
 * 3. Hybrid score             → 0.4 * cosine + 0.6 * normalized_bm25
 * 4. Trả về top-3 answers đạt ngưỡng ≥ [SIMILARITY_THRESHOLD] để đưa vào LLM
 *
 * ── Định dạng file QA (assets/qa_database.txt) ───────────────────────────
 *   Line 1: câu hỏi
 *   Line 2: câu trả lời
 *   Line 3: (dòng trống — phân cách)
 *   … lặp lại …
 *
 * ── Định dạng file vector (assets/vi_fasttext_pruned.vec) ─────────────────
 *   Dòng 1: <vocab_size> <dim>
 *   Còn lại: <word> <f1> <f2> … <fN>
 */
class FastTextRagEngine(private val context: Context) : RagEngine {

    companion object {
        private const val TAG = "FastTextRagEngine"
        private const val EMBEDDING_DIM = 300
        private const val CACHE_FILE = "fasttext_rag_cache_v1.bin"

        // Ngưỡng hybrid score để đưa vào context cho LLM
        private const val SIMILARITY_THRESHOLD = 0.8f

        // Số context tối đa trả về
        private const val TOP_K = 3

        // BM25 params (chuẩn Elasticsearch)
        private const val K1 = 1.2
        private const val B  = 0.75

        // BM25 score tối đa để normalize về [0, 1]
        private const val BM25_MAX_SCORE = 5.0

        // Trọng số hybrid
        private const val COSINE_WEIGHT = 0.4
        private const val BM25_WEIGHT   = 0.6
    }

    // ── Data ──────────────────────────────────────────────────────────────

    private data class QAPair(
        val id: Int,
        val question: String,
        val answer: String,
        var vector: FloatArray? = null
    )

    private val qaList        = mutableListOf<QAPair>()
    private val wordVectors   = HashMap<String, FloatArray>(50_000)

    // BM25 structures
    private val invertedIndex = HashMap<String, List<Pair<Int, Int>>>() // token → [(docIdx, tf)]
    private val docTermFreq   = mutableListOf<Map<String, Int>>()
    private val docLengths    = mutableListOf<Int>()
    private val idf           = HashMap<String, Double>()
    private var avgDocLen     = 0.0

    private var qaContentHash = ""

    @Volatile private var initialized = false

    // ── Public API ────────────────────────────────────────────────────────

    override suspend fun initialize(qaFile: String, vectorFile: String) {
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            try {
                loadQaPairs(qaFile)
                loadWordVectors(vectorFile)
                buildIndices()
                initialized = true
                Log.i(TAG, "RAG ready in ${System.currentTimeMillis() - t0}ms — " +
                        "${qaList.size} QA pairs, ${wordVectors.size} vectors")
            } catch (e: Throwable) {
                Log.e(TAG, "Initialization failed", e)
                initialized = false
            }
        }
    }

    /**
     * Trả về top-[TOP_K] answers có hybrid score ≥ [SIMILARITY_THRESHOLD],
     * hoặc null nếu không có câu nào đủ ngưỡng.
     */
    override suspend fun search(query: String): List<String>? {
        if (!initialized || qaList.isEmpty()) return null

        return withContext(Dispatchers.Default) {
            val tokens   = tokenize(query)
            if (tokens.isEmpty()) return@withContext null

            val queryVec = sentenceEmbedding(tokens)
            val bm25     = computeBm25Scores(tokens)
            val hits     = scoreHybrid(queryVec, bm25)
                .filter  { it.score >= SIMILARITY_THRESHOLD }
                .sortedByDescending { it.score }
                .take(TOP_K)

            logResult(query, hits, bm25.size)
            if (hits.isEmpty()) null else hits.map { it.answer }
        }
    }

    override fun release() {
        wordVectors.clear(); qaList.clear(); invertedIndex.clear()
        docTermFreq.clear(); docLengths.clear(); idf.clear()
        initialized = false
    }

    // ── Load ──────────────────────────────────────────────────────────────

    private suspend fun loadQaPairs(fileName: String) {
        qaList.clear()
        var id = 0
        val hashBuilder = StringBuilder()
        try {
            context.assets.open(fileName).bufferedReader().use { reader ->
                var question: String? = null
                var line = reader.readLine()
                while (line != null) {
                    coroutineContext.ensureActive()  // ✅ OK - trong suspend function body
                    val t = line.trim()
                    when {
                        t.isEmpty()      -> question = null
                        question == null  -> question = t.lowercase()
                        else -> {
                            question?.let { q -> qaList += QAPair(id++, q, t); hashBuilder.append(q) }
                            question = null
                        }
                    }
                    line = reader.readLine()
                }
            }
            qaContentHash = hashBuilder.toString().md5()
            Log.i(TAG, "Loaded ${qaList.size} QA pairs")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load '$fileName'", e); throw e
        }
    }

    private suspend fun loadWordVectors(fileName: String) {
        wordVectors.clear()
        var lineCount = 0
        try {
            context.assets.open(fileName).bufferedReader().use { reader ->
                reader.readLine() // skip header
                var line = reader.readLine()
                while (line != null) {
                    if (++lineCount % 1000 == 0) coroutineContext.ensureActive()
                    if (line.isNotBlank()) {
                        val spIdx = line.indexOf(' ')
                        if (spIdx != -1) {
                            val word = line.substring(0, spIdx)
                            val st   = StringTokenizer(line.substring(spIdx + 1), " ")
                            val vec  = FloatArray(EMBEDDING_DIM)
                            var i = 0
                            while (st.hasMoreTokens() && i < EMBEDDING_DIM) vec[i++] = st.nextToken().toFloat()
                            wordVectors[word] = vec
                        }
                    }
                    line = reader.readLine()
                }
            }
            Log.i(TAG, "Loaded ${wordVectors.size} word vectors")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vectors", e); throw e
        }
    }

    // ── Index building ────────────────────────────────────────────────────

    private fun buildIndices() {
        docLengths.clear(); idf.clear(); docTermFreq.clear()
        val docFreq         = HashMap<String, Int>()
        val invertedBuilder = HashMap<String, MutableList<Pair<Int, Int>>>()

        qaList.forEachIndexed { idx, qa ->
            val tokens = tokenize(qa.question)
            docLengths.add(tokens.size)

            val tfMap = HashMap<String, Int>()
            for (t in tokens) tfMap[t] = (tfMap[t] ?: 0) + 1
            docTermFreq.add(tfMap)

            for ((token, freq) in tfMap) {
                docFreq[token] = (docFreq[token] ?: 0) + 1
                invertedBuilder.getOrPut(token) { mutableListOf() } += Pair(idx, freq)
            }
        }

        invertedIndex.clear()
        for ((token, postings) in invertedBuilder) invertedIndex[token] = postings.toList()

        avgDocLen = docLengths.average()
        val n = qaList.size.toDouble()
        for ((term, freq) in docFreq) idf[term] = ln((n - freq + 0.5) / (freq + 0.5) + 1.0)

        buildAndCacheEmbeddings()
        Log.i(TAG, "Built inverted index — ${invertedIndex.size} unique terms")
    }

    private fun buildAndCacheEmbeddings() {
        val cacheFile = File(context.cacheDir, CACHE_FILE)
        if (cacheFile.exists()) {
            try {
                ObjectInputStream(FileInputStream(cacheFile)).use { ois ->
                    val cachedHash    = ois.readObject() as? String
                    @Suppress("UNCHECKED_CAST")
                    val cachedVectors = ois.readObject() as List<FloatArray>
                    if (cachedHash == qaContentHash && cachedVectors.size == qaList.size) {
                        for (i in qaList.indices) qaList[i].vector = cachedVectors[i]
                        Log.i(TAG, "Loaded ${qaList.size} embeddings from cache")
                        return
                    }
                    Log.w(TAG, "Cache invalid — rebuilding")
                }
            } catch (e: Exception) { Log.w(TAG, "Cache read failed", e) }
        }

        val toCache = mutableListOf<FloatArray>()
        qaList.forEach { qa ->
            val vec = sentenceEmbedding(tokenize(qa.question))
            qa.vector = vec; toCache += vec
        }
        try {
            ObjectOutputStream(FileOutputStream(cacheFile)).use { oos ->
                oos.writeObject(qaContentHash); oos.writeObject(toCache)
            }
            Log.i(TAG, "Saved ${toCache.size} embeddings to cache")
        } catch (e: Exception) { Log.w(TAG, "Failed to save cache", e) }
    }

    // ── Scoring ───────────────────────────────────────────────────────────

    private fun computeBm25Scores(queryTokens: List<String>): HashMap<Int, Double> {
        val scores = HashMap<Int, Double>()
        for (token in queryTokens) {
            val idfVal   = idf[token]            ?: continue
            val postings = invertedIndex[token]  ?: continue
            for ((docIdx, tf) in postings) {
                val docLen = docLengths[docIdx]
                val num    = tf * (K1 + 1)
                val den    = tf + K1 * (1 - B + B * (docLen / avgDocLen))
                scores[docIdx] = (scores[docIdx] ?: 0.0) + idfVal * (num / den)
            }
        }
        return scores
    }

    private data class ScoredResult(val score: Double, val answer: String, val question: String)

    private fun scoreHybrid(queryVec: FloatArray, bm25: HashMap<Int, Double>): List<ScoredResult> {
        val candidates = if (bm25.isNotEmpty()) bm25.keys else qaList.indices.toSet()
        return candidates.mapNotNull { idx ->
            val qa       = qaList[idx]
            val docVec   = qa.vector ?: return@mapNotNull null
            val cosine   = dotProduct(queryVec, docVec).toDouble()
            val normBm25 = min(bm25[idx] ?: 0.0, BM25_MAX_SCORE) / BM25_MAX_SCORE
            ScoredResult(cosine * COSINE_WEIGHT + normBm25 * BM25_WEIGHT, qa.answer, qa.question)
        }
    }

    // ── Embedding ─────────────────────────────────────────────────────────

    /** Average pooling + L2 normalize → cosine similarity = dot product */
    private fun sentenceEmbedding(tokens: List<String>): FloatArray {
        val vec   = FloatArray(EMBEDDING_DIM)
        var count = 0
        for (token in tokens) {
            val wv = wordVectors[token] ?: continue
            for (i in 0 until EMBEDDING_DIM) vec[i] += wv[i]
            count++
        }
        if (count > 0) { val inv = 1f / count; for (i in vec.indices) vec[i] *= inv }
        var normSq = 0f
        for (v in vec) normSq += v * v
        val norm = sqrt(normSq)
        if (norm > 0f) { val inv = 1f / norm; for (i in vec.indices) vec[i] *= inv }
        return vec
    }

    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ ]"), "")
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }

    // ── Logging ───────────────────────────────────────────────────────────

    private fun logResult(query: String, hits: List<ScoredResult>, candidateCount: Int) {
        if (hits.isEmpty()) {
            Log.i(TAG, "RAG MISS — query='$query' (0 docs ≥ $SIMILARITY_THRESHOLD from $candidateCount candidates)")
            return
        }
        Log.i(TAG, "RAG HIT — ${hits.size} docs for '$query' (from $candidateCount candidates)")
        hits.forEachIndexed { i, h ->
            Log.i(TAG, "  [${i+1}] score=${"%.3f".format(h.score)} | ${h.question.take(60)}")
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────

    private fun String.md5(): String =
        MessageDigest.getInstance("MD5").digest(toByteArray()).joinToString("") { "%02x".format(it) }
}