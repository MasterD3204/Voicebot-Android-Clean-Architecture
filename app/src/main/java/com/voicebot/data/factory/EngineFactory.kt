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
import com.voicebot.data.rag.embedding.EmbedRagEngine
import com.voicebot.data.stt.android.AndroidSttEngine
//import com.voicebot.data.stt.sherpa.SherpaSttEngine
import com.voicebot.data.tts.android.AndroidTtsEngine
import com.voicebot.data.tts.piper.PiperTtsEngine
import com.voicebot.domain.model.BotConfig
import com.voicebot.domain.model.LlmType
import com.voicebot.domain.model.RagType
import com.voicebot.domain.model.SttType
import com.voicebot.domain.model.TtsType
import com.voicebot.domain.port.LlmEngine
import com.voicebot.domain.port.RagEngine
import com.voicebot.domain.port.SttEngine
import com.voicebot.domain.port.TextNormalizer
import com.voicebot.domain.port.TtsEngine
import com.voicebot.data.llm.ragonly.RagOnlyLlmEngine
import android.util.Log
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
                topP = config.llmTopP,
                noThink = config.noThink
            )
            LlmType.GEMINI_API -> GeminiLlmEngine(
                apiKey = config.geminiApiKey,
                systemInstruction = config.llmSystemPrompt
            )
            LlmType.EXECUTORCH -> ExecuTorchLlmEngine(
                context           = context,
                folderName        = config.execuTorchFolderName,
                modelFileName     = config.execuTorchModelFileName,
                tokenizerFileName = config.execuTorchTokenizerFileName,
                systemPrompt      = config.llmSystemPrompt,
                maxTokens         = config.llmMaxTokens
            )
            LlmType.NATIVE_CPP -> NativeLlmEngine(
                modelPath = config.nativeLlmModelName,
                systemPrompt = config.llmSystemPrompt,
                temperature = config.llmTemperature,
                topK = config.llmTopK,
                topP = config.llmTopP
            )
            LlmType.RAG_ONLY -> RagOnlyLlmEngine(
                context    = context,
                qaFile     = config.qaAssetFile,
                //vectorFile = config.vectorAssetFile
            )
        }
    fun createTtsEngine(context: Context, config: BotConfig): TtsEngine =
        when (config.ttsType) {
            TtsType.ANDROID -> AndroidTtsEngine(context, config.language)
            TtsType.PIPER -> PiperTtsEngine(
                context    = context,
                modelDir   = config.piperModelDir,        // ← THÊM
                modelName  = config.piperModelName,
                tokensName = config.piperTokensName,
                espDataDir = config.piperEspDataDir,
                speakerId  = config.piperSpeakerId,
                speed      = config.piperSpeed,
                numThreads = config.piperNumThreads
            )
        }

    fun createRagEngine(context: Context, ragType: RagType): RagEngine {
        return when (ragType) {
            RagType.NONE -> NoOpRagEngine()
            RagType.FASTTEXT -> FastTextRagEngine(context)
            RagType.EMBEDDING -> EmbedRagEngine(context)
        }
    }


    /**
     * Tìm file trong /sdcard/Download/ và các path phổ biến.
     * Dùng cho LiteRT, NativeCpp, Piper, v.v.
     */
    fun findDownloadFile(context: Context, fileName: String): String? {
        val ext = context.getExternalFilesDir(null)?.absolutePath ?: ""
        return listOf(
            "/sdcard/Download/$fileName",
            "/storage/emulated/0/Download/$fileName",
            "/sdcard/$fileName",
            "$ext/$fileName"
        ).firstOrNull { java.io.File(it).exists() }
    }

    /**
     * ExecuTorch: mỗi model nằm trong folder riêng có tên là [folderName].
     * /sdcard/Download/<folderName>/model.pte
     * /sdcard/Download/<folderName>/tokenizer.bin  (hoặc tokenizer.model)
     */
    fun findExecuTorchFile(context: Context, folderName: String, fileName: String): String? {
        val ext = context.getExternalFilesDir(null)?.absolutePath ?: ""
        return listOf(
            "/sdcard/Download/$folderName/$fileName",
            "/storage/emulated/0/Download/$folderName/$fileName",
            "$ext/$folderName/$fileName"
        ).firstOrNull { java.io.File(it).exists() }
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
