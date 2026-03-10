package com.voicebot.data.llm.litert

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.*
import com.voicebot.domain.port.LlmEngine
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import java.util.concurrent.CancellationException
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/**
 * LLM engine backed by Google LiteRT (.litertlm format).
 * Searches common storage paths for [modelName].
 * Falls back from GPU → CPU backend automatically.
 */
class LiteRtLlmEngine(
    private val context: Context,
    private val modelName: String,
    private val systemPrompt: String = "Bạn là trợ lý ảo trả lời ngắn gọn tất cả câu hỏi.",
    private val maxTokens: Int = 512,
    private val temperature: Float = 0.1f,
    private val topK: Int = 8,
    private val topP: Float = 0.95f
) : LlmEngine {

    companion object { private const val TAG = "LiteRtLlmEngine" }

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var initialized = false

    override fun isReady() = initialized && conversation != null

    override suspend fun init(): Boolean {
        Log.e("DEBUG_LLM", "LiteRt: Bắt đầu init, modelName=$modelName")

        // ──────────────────────────────────────────────
        // Bước 1: Tìm model path từ external storage
        // ──────────────────────────────────────────────
        var modelPath = findModelPath()

        // ──────────────────────────────────────────────
        // Bước 2: Fallback → tìm trong assets, copy ra cache
        // ──────────────────────────────────────────────
/*        if (modelPath == null) {
            Log.e("DEBUG_LLM", "LiteRt: Không tìm thấy ở storage, thử assets...")
            modelPath = copyModelFromAssets()
        }*/

        // ──────────────────────────────────────────────
        // Bước 3: Fallback → tìm qua MediaStore (Android 10+)
        // ──────────────────────────────────────────────
/*
        if (modelPath == null) {
            Log.e("DEBUG_LLM", "LiteRt: Không tìm thấy ở assets, thử MediaStore...")
            modelPath = copyModelFromMediaStore()
        }
*/

        // ──────────────────────────────────────────────
        // Không tìm thấy model ở đâu cả → fail
        // ──────────────────────────────────────────────
        if (modelPath == null) {
            Log.e("DEBUG_LLM", "❌ Model '$modelName' KHÔNG TÌM THẤY ở bất kỳ nguồn nào!")
            return false
        }

        Log.e("DEBUG_LLM", "LiteRt: ✅ Model path = $modelPath (size=${File(modelPath).length()} bytes)")

        // ──────────────────────────────────────────────
        // Bước 4: Load model - thử GPU trước, fallback CPU
        // ──────────────────────────────────────────────
        return if (tryInit(modelPath, Backend.GPU)) {
            Log.e("DEBUG_LLM", "LiteRt: ✅ Loaded on GPU")
            true
        } else if (tryInit(modelPath, Backend.CPU)) {
            Log.e("DEBUG_LLM", "LiteRt: ⚠️ GPU failed, loaded on CPU")
            true
        } else {
            Log.e("DEBUG_LLM", "LiteRt: ❌ Load THẤT BẠI trên cả GPU lẫn CPU")
            false
        }
    }



    private fun findModelPath(): String? {
        val externalDir = context.getExternalFilesDir(null)?.absolutePath ?: ""

        val candidates = listOf(
            // 1. Ưu tiên app-private dir (luôn có quyền)
            "$externalDir/$modelName",
            // 2. Thử direct path (chỉ work nếu có MANAGE_EXTERNAL_STORAGE)
            "/sdcard/$modelName",
            "/sdcard/Download/$modelName",
            "/storage/emulated/0/$modelName",
            "/storage/emulated/0/Download/$modelName"
        )

        for (path in candidates) {
            val file = File(path)
            val exists = file.exists()
            val canRead = file.canRead()
            val size = if (exists) file.length() else -1
            Log.e("DEBUG_LLM", "findModelPath: $path → exists=$exists, canRead=$canRead, size=$size")
        }

        val found = candidates.firstOrNull { File(it).exists() && File(it).canRead() }

        if (found == null) {
            Log.e("DEBUG_LLM", "❌ KHÔNG TÌM THẤY model '$modelName' ở bất kỳ path nào!")
            Log.e("DEBUG_LLM", "💡 Có thể do Scoped Storage (Android 10+) chặn truy cập /sdcard/Download/")
            Log.e("DEBUG_LLM", "💡 Hãy copy model vào: $externalDir/")

            // Thử tìm qua MediaStore
            Log.e("DEBUG_LLM", "🔍 Thử tìm qua MediaStore...")
            tryFindViaMediaStore()
        }

        return found
    }

    private fun tryFindViaMediaStore() {
        try {
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE
            )

            context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    val size = cursor.getLong(2)
                    if (name.contains(modelName, ignoreCase = true)) {
                        Log.e("DEBUG_LLM", "✅ MediaStore TÌM THẤY: $name ( $size bytes)")
                        Log.e("DEBUG_LLM", "→ File CÓ TỒN TẠI nhưng cần dùng ContentResolver để đọc!")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DEBUG_LLM", "MediaStore query failed", e)
        }
    }


    private fun tryInit(modelPath: String, backend: Backend): Boolean = try {
        val cfg = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            maxNumTokens = maxTokens,
            // cacheDir needed only when model lives under /data/local/tmp
            cacheDir = if (modelPath.startsWith("/data/local/tmp"))
                context.getExternalFilesDir(null)?.absolutePath else null
        )
        val eng = Engine(cfg).also { it.initialize() }
        val conv = eng.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(topK, topP.toDouble(), temperature.toDouble()),
                systemInstruction = Contents.of(Content.Text(systemPrompt))
            )
        )
        engine = eng; conversation = conv; initialized = true
        Log.i(TAG, "LiteRT ready on $backend at $modelPath")
        true
    } catch (e: Exception) {
        Log.w(TAG, "$backend init failed: ${e.message}")
        false
    }

    override fun chatStream(query: String): Flow<String> = callbackFlow {
        val conv = conversation ?: run { trySend("Lỗi: Model chưa khởi tạo."); close(); return@callbackFlow }
        conv.sendMessageAsync(Contents.of(Content.Text(query)), object : MessageCallback {
            override fun onMessage(msg: Message) { trySend(msg.toString()) }
            override fun onDone() { close() }
            override fun onError(t: Throwable) {
                if (t is CancellationException) close()
                else { trySend("\n[Lỗi: ${t.message}]"); close() }
            }
        })
        awaitClose()
    }

    override fun release() {
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        initialized = false; engine = null; conversation = null
    }
}
