package com.voicebot.data.llm.gemini

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.voicebot.domain.port.LlmEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * LLM engine backed by Google Gemini REST API.
 * Requires internet access and a valid [apiKey].
 */
class GeminiLlmEngine(
    private val apiKey: String,
    private val modelName: String = "gemini-2.5-flash-lite",
    private val systemInstruction: String = "Bạn là trợ lý ảo hữu ích. Trả lời ngắn gọn bằng tiếng Việt."
) : LlmEngine {

    companion object { private const val TAG = "GeminiLlmEngine" }

    private var model: GenerativeModel? = null

    override suspend fun init(): Boolean = try {
        model = GenerativeModel(modelName = modelName, apiKey = apiKey)
        Log.i(TAG, "Gemini engine ready ($modelName)")
        true
    } catch (e: Exception) {
        Log.e(TAG, "Gemini init failed", e); false
    }

    override fun isReady() = model != null && apiKey.isNotBlank()

    override fun chatStream(query: String): Flow<String> = flow {
        val m = model ?: run { emit("Lỗi: Model chưa khởi tạo."); return@flow }
        try {
            val prompt = "$systemInstruction\nUser hỏi: $query"
            m.generateContentStream(prompt).collect { chunk -> chunk.text?.let { emit(it) } }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini stream error", e)
            emit("Xin lỗi, có lỗi kết nối mạng.")
        }
    }.flowOn(Dispatchers.IO)

    override fun release() { model = null }
}
