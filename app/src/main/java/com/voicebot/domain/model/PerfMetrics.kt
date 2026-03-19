package com.voicebot.domain.model

data class PerfMetrics(
    val sttEndTime: Long = 0L,
    val llmFirstTokenTime: Long = 0L,
    val ttsFirstAudioTime: Long = 0L,
    val queryEndTime: Long = 0L,
    val firstChunkReceivedTime: Long = 0L
) {
    fun getLlmLatency(): Long =
        if (llmFirstTokenTime > sttEndTime) llmFirstTokenTime - sttEndTime else 0L

    fun getTtsLatency(): Long =
        if (ttsFirstAudioTime > firstChunkReceivedTime && firstChunkReceivedTime > 0L)
            ttsFirstAudioTime - firstChunkReceivedTime
        else 0L

    fun getQueryLatency(): Long =
        if (queryEndTime > sttEndTime) queryEndTime - sttEndTime else 0L

    override fun toString(): String =
        "⚡ TTFT: ${getLlmLatency()}ms | 🔊 TTFA: ${getTtsLatency()}ms | 🔍 Query: ${getQueryLatency()}ms"
}
