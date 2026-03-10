package com.voicebot.data.factory

import android.content.Context
import com.voicebot.data.llm.executorch.ExecuTorchLlmEngine
import com.voicebot.data.llm.gemini.GeminiLlmEngine
import com.voicebot.data.llm.litert.LiteRtLlmEngine
import com.voicebot.data.llm.native.NativeLlmEngine
import com.voicebot.data.normalizer.CompositeTextNormalizer
import com.voicebot.data.normalizer.NumberTextNormalizer
import com.voicebot.data.normalizer.ProductTextNormalizer
import com.voicebot.data.rag.fasttext.FastTextRagEngine
import com.voicebot.data.stt.android.AndroidSttEngine
//import com.voicebot.data.stt.sherpa.SherpaSttEngine
import com.voicebot.data.tts.android.AndroidTtsEngine
import com.voicebot.domain.model.BotConfig
import com.voicebot.domain.model.LlmType
import com.voicebot.domain.model.RagType
import com.voicebot.domain.model.SttType
import com.voicebot.domain.port.LlmEngine
import com.voicebot.domain.port.RagEngine
import com.voicebot.domain.port.SttEngine
import com.voicebot.domain.port.TextNormalizer
import com.voicebot.domain.port.TtsEngine

/**
 * ★ SINGLE PLACE to swap engine implementations.
 *
 * Change [BotConfig] fields in MainActivity → EngineFactory picks the right class.
 * No other files need to change when switching backends.
 */
object EngineFactory {

    fun createSttEngine(context: Context, config: BotConfig): SttEngine =
        when (config.sttType) {
            SttType.ANDROID -> AndroidSttEngine(context, config.language)
            else -> throw IllegalArgumentException("Unsupported STT type: ${config.sttType}")
        }

    fun createLlmEngine(context: Context, config: BotConfig): LlmEngine =
        when (config.llmType) {
            LlmType.LITE_RT -> LiteRtLlmEngine(
                context = context,
                modelName = config.liteRtModelName,
                systemPrompt = config.llmSystemPrompt,
                maxTokens = config.llmMaxTokens,
                temperature = config.llmTemperature,
                topK = config.llmTopK,
                topP = config.llmTopP
            )
            LlmType.GEMINI_API -> GeminiLlmEngine(
                apiKey = config.geminiApiKey,
                systemInstruction = config.llmSystemPrompt
            )
            LlmType.EXECUTORCH -> ExecuTorchLlmEngine(
                context = context,
                modelName = config.execuTorchModelName,
                systemPrompt = config.llmSystemPrompt,
                maxTokens = config.llmMaxTokens
            )
            LlmType.NATIVE_CPP -> NativeLlmEngine(
                modelPath = config.nativeLlmModelName,
                systemPrompt = config.llmSystemPrompt,
                temperature = config.llmTemperature,
                topK = config.llmTopK,
                topP = config.llmTopP
            )
        }

    fun createTtsEngine(context: Context, config: BotConfig): TtsEngine =
        AndroidTtsEngine(context, config.language)

    fun createRagEngine(context: Context, config: BotConfig): RagEngine =
        when (config.ragType) {
            RagType.FASTTEXT -> FastTextRagEngine(context)
            RagType.NONE -> NoOpRagEngine()
        }

    fun createTextNormalizer(context: Context): TextNormalizer =
        CompositeTextNormalizer(listOf(
            NumberTextNormalizer(),
            ProductTextNormalizer(context)
        ))
}

/** No-op RAG when RAG is disabled — passes everything to LLM */
class NoOpRagEngine : RagEngine {
    override suspend fun initialize(qaFile: String, vectorFile: String) {}
    override suspend fun search(query: String): List<String>? = null
    override fun release() {}
}
