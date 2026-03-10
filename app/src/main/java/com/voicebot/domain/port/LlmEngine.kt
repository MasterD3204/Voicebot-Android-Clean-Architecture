package com.voicebot.domain.port

import kotlinx.coroutines.flow.Flow

/**
 * Port for Large Language Model engines.
 * Implementations: LiteRtLlmEngine, GeminiLlmEngine, ExecuTorchLlmEngine, NativeLlmEngine
 */
interface LlmEngine {
    /** Heavy initialisation (load model, connect API). Call once, off main thread. */
    suspend fun init(): Boolean

    /** True after [init] succeeds */
    fun isReady(): Boolean

    /**
     * Stream tokens for [query].
     * Each emitted String is a partial token/chunk to append to the UI.
     * Flow completes when generation is done.
     */
    fun chatStream(query: String): Flow<String>

    /** Release resources (model weights, connections, etc.) */
    fun release()

    /**
     * Xóa lịch sử hội thoại, bắt đầu phiên mới.
     * Mỗi engine tự implement tuỳ cơ chế — default no-op cho engines không giữ state.
     */
    fun resetHistory() {}
}