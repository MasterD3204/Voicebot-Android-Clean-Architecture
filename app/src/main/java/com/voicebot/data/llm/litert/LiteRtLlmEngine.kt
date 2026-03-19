package com.voicebot.data.llm.litert

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.*
import com.voicebot.domain.port.LlmEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CancellationException

/**
 * LLM engine backed by Google LiteRT (.litertlm format).
 * Searches common storage paths for [modelName].
 * Falls back from GPU → CPU backend automatically.
 */
class LiteRtLlmEngine(
    private val context: Context,
    private val modelName: String,
    private val systemPrompt: String = "Bạn là trợ lý ảo trả lời ngắn gọn tất cả câu hỏi.",
    private val maxTokens: Int = 1024,
    private val temperature: Float = 0.1f,
    private val topK: Int = 8,
    private val topP: Float = 0.95f,
    /** Khi true: tự quản lý history để tránh LiteRT Jinja crash trên <think> tokens */
    private val noThink: Boolean = false
) : LlmEngine {

    companion object { private const val TAG = "LiteRtLlmEngine" }

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var initialized = false

    /**
     * Manual history dùng khi noThink=true.
     * Mỗi entry: (userMessage, assistantReply đã strip <think>).
     * Được replay vào Conversation fresh trước mỗi turn.
     */
    private val manualHistory = mutableListOf<Pair<String, String>>()
    /** Buffer tích lũy reply của turn hiện tại để lưu vào manualHistory sau khi stream xong */
    private val currentReplyBuf = StringBuilder()

    override fun isReady() = initialized && conversation != null

    override suspend fun init(): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "===== START MODEL INITIALIZATION =====")
        Log.d(TAG, "Looking for model: '$modelName'")

        val path = findModelPath() ?: run {
            Log.e(TAG, "❌ Model '$modelName' not found in any search path")
            return@withContext false
        }

        Log.i(TAG, "✅ Model found at: $path")
        Log.d(TAG, "File size: ${File(path).length() / (1024 * 1024)} MB")

        val gpuSuccess = tryInit(path, Backend.GPU())
        if (gpuSuccess) return@withContext true

        Log.w(TAG, "⚠️ GPU init failed, falling back to CPU...")
        val cpuSuccess = tryInit(path, Backend.CPU())

        if (!cpuSuccess) {
            Log.e(TAG, "❌ Both GPU and CPU initialization failed!")
        }
        cpuSuccess
    }

    private fun findModelPath(): String? {
        val externalDir = context.getExternalFilesDir(null)?.absolutePath ?: ""
        Log.d(TAG, "External files dir: $externalDir")

        val searchPaths = listOf(
            "/sdcard/$modelName",
            "/sdcard/Download/$modelName",
            "/storage/emulated/0/$modelName",
            "/storage/emulated/0/Download/$modelName",
            "$externalDir/$modelName"
        )

        Log.d(TAG, "--- Scanning ${searchPaths.size} paths ---")

        for (path in searchPaths) {
            val file = File(path)
            val exists = file.exists()
            val readable = file.canRead()
            Log.d(TAG, "  Path: $path | exists=$exists | readable=$readable" +
                    if (exists) " | size=${file.length() / (1024 * 1024)} MB" else ""
            )
        }

        val found = searchPaths.firstOrNull { File(it).exists() }
        Log.d(TAG, if (found != null) "✅ Selected path: $found" else "❌ No valid path found")
        return found
    }

    private fun tryInit(modelPath: String, backend: Backend): Boolean = try {
        Log.d(TAG, "--- Attempting init with backend=$backend ---")
        Log.d(TAG, "  modelPath=$modelPath")
        Log.d(TAG, "  maxTokens=$maxTokens, topK=$topK, topP=$topP, temperature=$temperature")

        val cacheDir = if (modelPath.startsWith("/data/local/tmp"))
            context.getExternalFilesDir(null)?.absolutePath else null
        Log.d(TAG, "  cacheDir=$cacheDir")

        val cfg = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            maxNumTokens = maxTokens,
            cacheDir = cacheDir
        )

        Log.d(TAG, "  Creating Engine...")
        val eng = Engine(cfg)

        Log.d(TAG, "  Initializing Engine...")
        eng.initialize()
        Log.d(TAG, "  ✅ Engine initialized successfully")

        Log.d(TAG, "  Creating Conversation...")
        val conv = eng.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(topK, topP.toDouble(), temperature.toDouble()),
                systemInstruction = Contents.of(Content.Text(systemPrompt))
            )
        )
        Log.d(TAG, "  ✅ Conversation created successfully")

        engine = eng; conversation = conv; initialized = true
        Log.i(TAG, "🚀 LiteRT ready on $backend at $modelPath")
        true
    } catch (e: Exception) {
        Log.e(TAG, "❌ $backend init failed at $modelPath", e)
        Log.e(TAG, "  Exception type: ${e.javaClass.simpleName}")
        Log.e(TAG, "  Message: ${e.message}")
        e.cause?.let { Log.e(TAG, "  Cause: ${it.message}") }
        false
    }


    override fun chatStream(query: String): Flow<String> = callbackFlow {
        if (noThink) {
            // ── noThink mode: manual history ──────────────────────────────
            // Reset Conversation để tránh <think> tokens tích lũy trong native context
            val freshConv = createFreshConversation() ?: run {
                trySend("Lỗi: Không thể tạo conversation."); close(); return@callbackFlow
            }
            conversation = freshConv

            // Replay history sạch (đã strip <think>) vào Conversation mới
            // Dùng format few-shot: ghép tất cả turns trước + câu hỏi hiện tại thành 1 message
            val fullPrompt = buildReplayPrompt(query)
            currentReplyBuf.clear()

            freshConv.sendMessageAsync(Contents.of(Content.Text(fullPrompt)), object : MessageCallback {
                override fun onMessage(msg: Message) {
                    val token = msg.toString()
                    currentReplyBuf.append(token)
                    trySend(token)
                }
                override fun onDone() {
                    // Lưu reply đã strip <think> vào manual history
                    val cleanReply = stripThinkTags(currentReplyBuf.toString())
                    manualHistory.add(Pair(query, cleanReply))
                    Log.d(TAG, "History saved: ${manualHistory.size} turns")
                    close()
                }
                override fun onError(t: Throwable) {
                    if (t is CancellationException) close()
                    else { trySend("\n[Lỗi: ${t.message}]"); close() }
                }
            })
        } else {
            // ── Normal mode: LiteRT native Conversation history ────────────
            val conv = conversation ?: run { trySend("Lỗi: Model chưa khởi tạo."); close(); return@callbackFlow }
            conv.sendMessageAsync(Contents.of(Content.Text(query)), object : MessageCallback {
                override fun onMessage(msg: Message) { trySend(msg.toString()) }
                override fun onDone() { close() }
                override fun onError(t: Throwable) {
                    if (t is CancellationException) close()
                    else { trySend("\n[Lỗi: ${t.message}]"); close() }
                }
            })
        }
        awaitClose()
    }

    override fun release() {
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        initialized = false; engine = null; conversation = null
        manualHistory.clear()
    }

    /**
     * Xóa lịch sử hội thoại bằng cách tạo mới Conversation từ Engine đang có.
     * Engine (weights) không cần load lại — chỉ reset context window.
     */
    override fun resetHistory() {
        val eng = engine ?: return
        runCatching { conversation?.close() }
        manualHistory.clear()
        currentReplyBuf.clear()
        conversation = try {
            eng.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(topK, topP.toDouble(), temperature.toDouble()),
                    systemInstruction = Contents.of(Content.Text(systemPrompt))
                )
            ).also { Log.i(TAG, "Conversation history reset") }
        } catch (e: Exception) {
            Log.e(TAG, "resetHistory failed", e); null
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun createFreshConversation(): Conversation? {
        val eng = engine ?: return null
        runCatching { conversation?.close() }
        return try {
            eng.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(topK, topP.toDouble(), temperature.toDouble()),
                    systemInstruction = Contents.of(Content.Text(systemPrompt))
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "createFreshConversation failed", e); null
        }
    }

    /**
     * Build prompt chứa toàn bộ history sạch + câu hỏi mới.
     * Format: "User: ...\nAssistant: ...\nUser: ...\nAssistant: ..." (few-shot style)
     * Gửi như 1 message duy nhất vào Conversation fresh để tránh Jinja template bug.
     */
    private fun buildReplayPrompt(currentQuery: String): String {
        if (manualHistory.isEmpty()) return currentQuery
        val sb = StringBuilder()
        for ((user, assistant) in manualHistory) {
            sb.append("User: $user\nAssistant: $assistant\n")
        }
        sb.append("User: $currentQuery")
        return sb.toString()
    }

    /**
     * Strip tất cả <think>...</think> blocks khỏi text.
     * Dùng để làm sạch reply trước khi lưu vào manualHistory.
     */
    private fun stripThinkTags(text: String): String {
        var result = text
        // Loop để xử lý nhiều block lồng nhau hoặc liên tiếp
        while (result.contains("<think>")) {
            val open  = result.indexOf("<think>")
            val close = result.indexOf("</think>", open)
            result = if (close == -1) {
                // Tag mở nhưng chưa đóng — xóa từ <think> đến hết
                result.substring(0, open)
            } else {
                result.substring(0, open) + result.substring(close + "</think>".length)
            }
        }
        return result.trim()
    }