package com.voicebot.data.llm.native

import android.util.Log
import com.voicebot.domain.port.LlmEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LLM engine backed by a native C++ library (e.g. llama.cpp) via JNI.
 * Loads 'libai_core.so' — must be compiled and placed in jniLibs/.
 *
 * ── Integration steps ──────────────────────────────────────────────────────
 * 1. Implement the JNI functions in src/main/cpp/ai_core.cpp
 * 2. Add CMakeLists.txt entry:
 *    add_library(ai_core SHARED ai_core.cpp)
 *    target_link_libraries(ai_core llama ggml android log)
 *
 * Supports any GGUF-compatible model (Qwen, Llama, Phi, Gemma…)
 * ──────────────────────────────────────────────────────────────────────────
 */
class NativeLlmEngine(
    private val modelPath: String,
    private val systemPrompt: String = "Bạn là trợ lý ảo trả lời ngắn gọn.",
    private val threads: Int = 4,
    private val contextSize: Int = 2048,
    private val temperature: Float = 0.7f,
    private val topK: Int = 40,
    private val topP: Float = 0.9f
) : LlmEngine {

    companion object {
        private const val TAG = "NativeLlmEngine"

        init {
            try { System.loadLibrary("ai_core") }
            catch (e: UnsatisfiedLinkError) { Log.e(TAG, "libai_core.so not found", e) }
        }
    }

    // ── JNI declarations ─────────────────────────────────────────────────
    private external fun nativeInit(path: String, threads: Int, ctxSize: Int, temp: Float, topK: Int, topP: Float, minP: Float, mirostat: Int, mirostatTau: Float, mirostatEta: Float, seed: Int): Boolean
    private external fun nativeGenerateStream(prompt: String, maxTokens: Int, callback: NativeTokenCallback): Boolean
    private external fun nativeRelease(): Boolean
    private external fun nativeStopGeneration()
    private external fun nativeSetSystemPrompt(prompt: String)

    private var initialized = false

    override suspend fun init(): Boolean {
        if (!File(modelPath).exists()) {
            Log.e(TAG, "Model not found: $modelPath"); return false
        }
        return withContext(Dispatchers.IO) {
            try {
                val ok = nativeInit(modelPath, threads, contextSize, temperature, topK, topP, 0f, 0, 0.1f, 0.1f, -1)
                if (ok) { nativeSetSystemPrompt(systemPrompt); initialized = true }
                Log.i(TAG, "Native LLM init: $ok at $modelPath")
                ok
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "JNI not linked — build libai_core.so first", e); false
            } catch (e: Exception) {
                Log.e(TAG, "Native init failed", e); false
            }
        }
    }

    override fun isReady() = initialized

    override fun chatStream(query: String): Flow<String> = flow {
        if (!isReady()) { emit("Lỗi: Native engine chưa khởi tạo."); return@flow }
        val channel = Channel<Result<String>>(Channel.UNLIMITED)
        val cb = object : NativeTokenCallback {
            override fun onToken(t: String) { channel.trySend(Result.success(t)) }
            override fun onDone() { channel.close() }
            override fun onError(msg: String) { channel.close(RuntimeException(msg)) }
        }
        withContext(Dispatchers.IO) {
            nativeGenerateStream(buildPrompt(query), 512, cb)
        }
        for (result in channel) emit(result.getOrThrow())
    }.flowOn(Dispatchers.IO)

    /** Llama-2 style; adjust for your model's chat template */
    private fun buildPrompt(query: String) =
        "<s>[INST] <<SYS>>\n$systemPrompt\n<</SYS>>\n$query [/INST]"

    fun stop() = nativeStopGeneration()

    override fun release() {
        if (initialized) { runCatching { nativeRelease() }; initialized = false }
    }
}

/** JNI callback — implemented in Kotlin, called from C++ */
interface NativeTokenCallback {
    fun onToken(token: String)
    fun onDone()
    fun onError(message: String)
}
