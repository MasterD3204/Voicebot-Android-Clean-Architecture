package com.voicebot.data.llm.executorch

import android.content.Context
import android.util.Log
import com.voicebot.domain.port.LlmEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * LLM engine backed by PyTorch ExecuTorch (.pte format).
 *
 * ── Integration steps ──────────────────────────────────────────────────────
 * 1. Add ExecuTorch Android AAR to build.gradle:
 *    implementation("org.pytorch:executorch-android:<version>")
 *
 * 2. Export a model to .pte:
 *    python -m executorch.examples.models.llama2.export_llama \
 *        --checkpoint <path> --params <path> -kv -d fp32 -X
 *
 * 3. Uncomment and implement the TODO sections below.
 *
 * See: https://pytorch.org/executorch/stable/llm/llama-demo-android.html
 * ──────────────────────────────────────────────────────────────────────────
 */
class ExecuTorchLlmEngine(
    private val context: Context,
    private val modelName: String,
    private val systemPrompt: String = "Bạn là trợ lý ảo trả lời ngắn gọn tất cả câu hỏi.",
    private val maxTokens: Int = 512
) : LlmEngine {

    companion object { private const val TAG = "ExecuTorchEngine" }

    // TODO: import com.pytorch.executorch.LlmModule
    // private var llmModule: LlmModule? = null
    private var initialized = false

    override suspend fun init(): Boolean {
        val path = findModelPath() ?: run {
            Log.e(TAG, "Model '$modelName' not found"); return false
        }
        return try {
            // TODO: initialize ExecuTorch LLM module
            // llmModule = LlmModule(context.filesDir.absolutePath, path)
            // llmModule?.load()
            Log.w(TAG, "ExecuTorch stub — model found at $path, implement JNI bridge")
            initialized = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "ExecuTorch init failed", e); false
        }
    }

    override fun isReady() = initialized

    override fun chatStream(query: String): Flow<String> = flow {
        if (!isReady()) { emit("Lỗi: ExecuTorch engine chưa khởi tạo."); return@flow }
        val prompt = buildChatPrompt(query)

        // TODO: stream tokens from ExecuTorch
        // llmModule?.generate(prompt, maxTokens) { token -> emit(token) }

        emit("[ExecuTorch stub] Query received: $query")
    }

    /**
     * Llama-style chat template.
     * Adjust for your model's expected prompt format (Phi, Mistral, Qwen…).
     */
    private fun buildChatPrompt(query: String): String =
        "<s>[INST] <<SYS>>\n$systemPrompt\n<</SYS>>\n$query [/INST]"

    private fun findModelPath(): String? {
        val externalDir = context.getExternalFilesDir(null)?.absolutePath ?: ""
        return listOf(
            "/sdcard/$modelName",
            "/sdcard/Download/$modelName",
            "/storage/emulated/0/$modelName",
            "$externalDir/$modelName"
        ).firstOrNull { File(it).exists() }
    }

    override fun release() {
        // TODO: llmModule?.resetNative()
        initialized = false
    }
}
