package com.voicebot.domain.model

data class PerfMetrics(
    var sttEndTime: Long = 0L,
    var llmFirstTokenTime: Long = 0L,
    var ttsFirstAudioTime: Long = 0L,
    var queryEndTime: Long = 0L,
    var firstChunkReceivedTime: Long = 0L
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
