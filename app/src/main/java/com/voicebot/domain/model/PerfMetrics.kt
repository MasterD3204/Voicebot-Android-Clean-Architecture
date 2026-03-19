package com.voicebot.domain.model

data class PerfMetrics(
    val sttEndTime: Long = 0L,
    val llmFirstTokenTime: Long = 0L,
    val ttsSynthesisMs: Long = 0L,       // thời gian synthesize thuần (ms) — do engine report
    val queryEndTime: Long = 0L,
    val firstChunkReceivedTime: Long = 0L
) {
    fun getLlmLatency(): Long =
        if (llmFirstTokenTime > sttEndTime) llmFirstTokenTime - sttEndTime else 0L

    fun getTtsLatency(): Long = ttsSynthesisMs   // thời gian generate() thuần của engine

    fun getQueryLatency(): Long =
        if (queryEndTime > sttEndTime) queryEndTime - sttEndTime else 0L

    override fun toString(): String =
        "⚡ TTFT: ${getLlmLatency()}ms | 🔊 TTS: ${getTtsLatency()}ms | 🔍 Query: ${getQueryLatency()}ms"
}
