package com.voicebot.data.llm.executorch

import android.content.Context
import android.util.Log
import com.voicebot.domain.port.LlmEngine
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow

import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmModule
import java.io.File

/**
 * LLM engine backed by PyTorch ExecuTorch (.pte format).
 *
 * ── Integration steps ──────────────────────────────────────────────────────
 * 1. Add ExecuTorch Android dependency to build.gradle.kts:
 *    implementation("org.pytorch:executorch-android:1.0.0")
 *
 * 2. Export a model to .pte:
 *    python -m executorch.examples.models.llama2.export_llama \
 *        --checkpoint <path> --params <path> -kv -d fp32 -X
 *
 * 3. Place .pte model and tokenizer on device storage.
 *
 * See: https://pytorch.org/executorch/stable/llm/llama-demo-android.html
 * ──────────────────────────────────────────────────────────────────────────
 */
class ExecuTorchLlmEngine(
    private val context: Context,
    private val modelName: String,
    private val tokenizerName: String,
    private val systemPrompt: String = "Bạn là trợ lý ảo trả lời ngắn gọn tất cả câu hỏi.",
    private val maxTokens: Int = 512,
    private val temperature: Float = 0.8f
) : LlmEngine {

    companion object {
        private const val TAG = "ExecuTorchEngine"
    }

    private var llmModule: LlmModule? = null
    private var initialized = false

    override suspend fun init(): Boolean {
        val modelPath = findModelPath(modelName) ?: run {
            Log.e(TAG, "Model '$modelName' not found")
            return false
        }
        val tokenizerPath = findModelPath(tokenizerName) ?: run {
            Log.e(TAG, "Tokenizer '$tokenizerName' not found")
            return false
        }

        return try {
            // Thêm modelCategory parameter
            llmModule = LlmModule(
                modelPath,
                tokenizerPath,
                temperature
            )
            val loadResult = llmModule?.load() ?: -1
            if (loadResult != 0) {
                Log.e(TAG, "ExecuTorch load failed with code: $loadResult")
                return false
            }
            Log.i(TAG, "ExecuTorch model loaded successfully from $modelPath")
            initialized = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "ExecuTorch init failed", e)
            false
        }
    }


    override fun isReady() = initialized

    override fun chatStream(query: String): Flow<String> = callbackFlow {
        if (!isReady()) {
            trySend("Lỗi: ExecuTorch engine chưa khởi tạo.")
            close()
            return@callbackFlow
        }

        val prompt = buildChatPrompt(query)

        // Buffer để phát hiện khi nào phần prompt kết thúc
        val buffer = StringBuilder()
        val assistantTag = "<|im_start|>assistant\n"
        var promptEnded = false
        // Một số model trả lại toàn bộ prompt, một số chỉ trả phần assistant.
        // Ta dùng flag để xử lý cả 2 trường hợp.
        var totalReceived = 0

        llmModule?.generate(prompt, maxTokens, object : LlmCallback {
            override fun onResult(result: String) {
                if (promptEnded) {
                    // Đã qua phần prompt -> emit token trả lời
                    // Lọc bỏ end-of-turn tokens
                    val cleaned = result
                        .replace("<|im_end|>", "")
                        .replace("<|endoftext|>", "")
                    if (cleaned.isNotEmpty()) {
                        trySend(cleaned)
                    }
                    return
                }

                // Đang trong giai đoạn buffer để detect prompt
                buffer.append(result)
                totalReceived += result.length

                // Kiểm tra xem buffer đã chứa assistant tag chưa
                val tagIndex = buffer.indexOf(assistantTag)
                if (tagIndex != -1) {
                    promptEnded = true
                    // Lấy phần text SAU assistant tag (nếu có)
                    val afterTag = buffer.substring(tagIndex + assistantTag.length)
                    val cleaned = afterTag
                        .replace("<|im_end|>", "")
                        .replace("<|endoftext|>", "")
                    if (cleaned.isNotEmpty()) {
                        trySend(cleaned)
                    }
                }

                // Fallback: nếu model KHÔNG echo lại prompt
                // (tức là token đầu tiên đã là câu trả lời)
                // Heuristic: nếu nhận > 5 tokens mà không thấy tag -> model không echo prompt
                if (!promptEnded && totalReceived > 200 && !buffer.contains("<|im_start|>")) {
                    promptEnded = true
                    val cleaned = buffer.toString()
                        .replace("<|im_end|>", "")
                        .replace("<|endoftext|>", "")
                    if (cleaned.isNotEmpty()) {
                        trySend(cleaned)
                    }
                }
            }

            override fun onStats(stats: String) {
                Log.d(TAG, "Stats: $stats")
            }
        })

        close()

        awaitClose {
            Log.d(TAG, "chatStream flow closed")
        }
    }


    /**
     * Llama-style chat template.
     * Adjust for your model's expected prompt format (Phi, Mistral, Qwen…).
     */
    private fun buildChatPrompt(query: String): String =
        "<|im_start|>system\n$systemPrompt<|im_end|>\n" +
                "<|im_start|>user\n$query /no_think<|im_end|>\n" +
                "<|im_start|>assistant\n"

    private fun findModelPath(fileName: String): String? {
        val externalDir = context.getExternalFilesDir(null)?.absolutePath ?: ""
        return listOf(
            "/sdcard/$fileName",
            "/sdcard/Download/$fileName",
            "/storage/emulated/0/$fileName",
            "$externalDir/$fileName"
        ).firstOrNull { File(it).exists() }
    }

    override fun release() {
        try {
            llmModule?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping module", e)
        }
        llmModule = null
        initialized = false
        Log.i(TAG, "ExecuTorch engine released")
    }
}
