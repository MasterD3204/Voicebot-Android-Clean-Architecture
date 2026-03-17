package com.voicebot.data.llm.gemini

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.voicebot.domain.port.LlmEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import com.google.ai.client.generativeai.Chat
import com.google.ai.client.generativeai.type.content

/**
 * LLM engine backed by Google Gemini REST API.
 * Requires internet access and a valid [apiKey].
 */
class GeminiLlmEngine(
    private val apiKey: String,
    private val modelName: String = "gemini-2.5-flash-lite",
    private val systemInstruction: String = "Bạn là trợ lý ảo hữu ích. Trả lời ngắn gọn bằng tiếng Việt."
) : LlmEngine {

    companion object {
        private const val TAG = "GeminiLlmEngine"
    }

    private var model: GenerativeModel? = null
    private var chat: Chat? = null  // ← Giữ ngữ cảnh hội thoại

    // ── INIT ──
    override suspend fun init(): Boolean = try {
        Log.d(TAG, "⏳ Initializing Gemini engine | model=$modelName")
        model = GenerativeModel(
            modelName = modelName,
            apiKey = apiKey,
            systemInstruction = content { text(systemInstruction) }
        )
        chat = model!!.startChat()
        Log.i(TAG, "✅ Gemini engine ready | model=$modelName")
        true
    } catch (e: Exception) {
        Log.e(TAG, "❌ Gemini init failed | ${e.message}", e)
        false
    }

    // ── IS READY ── (BẮT BUỘC - implement từ LlmEngine interface)
    override fun isReady(): Boolean {
        val ready = model != null && chat != null && apiKey.isNotBlank()
        Log.d(TAG, "isReady=$ready")
        return ready
    }

    // ── CHAT STREAM ──
    override fun chatStream(query: String): Flow<String> = flow {
        val c = chat ?: run {
            Log.e(TAG, "❌ chatStream aborted: chat is null")
            emit("Lỗi: Chat chưa khởi tạo.")
            return@flow
        }

        try {
            Log.d(TAG, "📤 Sending message | length=${query.length}")
            var chunkCount = 0
            var totalChars = 0

            // Chat object TỰ ĐỘNG giữ history các lượt trước
            c.sendMessageStream(query).collect { chunk ->
                chunk.text?.let { text ->
                    chunkCount++
                    totalChars += text.length
                    emit(text)
                }
            }
            Log.i(TAG, "✅ Stream done | chunks=$chunkCount | chars=$totalChars")

        } catch (e: UnknownHostException) {
            Log.e(TAG, "🌐❌ DNS error", e)
            emit("Xin lỗi, không có kết nối mạng.")
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "🌐⏱️ Timeout", e)
            emit("Xin lỗi, kết nối quá chậm. Vui lòng thử lại.")
        } catch (e: SSLException) {
            Log.e(TAG, "🌐🔒 SSL error", e)
            emit("Xin lỗi, lỗi bảo mật kết nối.")
        } catch (e: IOException) {
            Log.e(TAG, "🌐❌ IO error", e)
            emit("Xin lỗi, lỗi kết nối mạng.")
        } catch (e: com.google.ai.client.generativeai.type.GoogleGenerativeAIException) {
            Log.e(TAG, "🤖❌ Gemini API error", e)
            val msg = when {
                e.message?.contains("API key", true) == true -> "Lỗi: API key không hợp lệ."
                e.message?.contains("quota", true) == true -> "Lỗi: Vượt quá giới hạn API."
                e.message?.contains("blocked", true) == true ||
                        e.message?.contains("safety", true) == true -> "Nội dung bị chặn bởi bộ lọc an toàn."
                else -> "Có lỗi từ dịch vụ AI. Vui lòng thử lại."
            }
            emit(msg)
        } catch (e: Exception) {
            Log.e(TAG, "❓ Unexpected error", e)
            emit("Xin lỗi, lỗi không xác định ( ${e.javaClass.simpleName}).")
        }
    }.flowOn(Dispatchers.IO)

    // ── RESET HISTORY ── Tạo chat session mới = xóa toàn bộ ngữ cảnh
    override fun resetHistory() {
        Log.i(TAG, "🔄 Resetting chat history")
        chat = model?.startChat()
    }

    // ── RELEASE ──
    override fun release() {
        Log.i(TAG, "🧹 Releasing Gemini engine")
        chat = null
        model = null
    }
}
