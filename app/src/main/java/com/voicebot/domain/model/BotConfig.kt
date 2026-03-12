package com.voicebot.domain.model

/**
 * Central configuration for the VoiceBot.
 *
 * ★ To switch engines, change only the type fields here.
 *   No other code changes needed — EngineFactory handles the rest.
 */
data class BotConfig(
    // ── Engine selection ──────────────────────────────────────────────────
    val sttType: SttType = SttType.ANDROID,
    val llmType: LlmType = LlmType.LITE_RT,
    val ttsType: TtsType = TtsType.ANDROID,
    val ragType: RagType = RagType.FASTTEXT,

    // ── Locale ───────────────────────────────────────────────────────────
    val language: String = "vi-VN",

    // ── LLM credentials / model names ────────────────────────────────────
    val geminiApiKey: String = "AIzaSyAsAEaZw4D7y2cESBVoyymdxmV2kKJOZks",
    val liteRtModelName: String = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
    //val liteRtModelName: String = "qwen3-5-2b_q8_ekv128.litertlm",
    val execuTorchFolderName: String        = "qwen2.5_pte",
    val execuTorchModelFileName: String     = "model.pte",
    val execuTorchTokenizerFileName: String = "tokenizer.json",
    val nativeLlmModelName: String = "model.gguf",

    // ── RAG assets ───────────────────────────────────────────────────────
    val qaAssetFile: String = "qa_database.txt",
    val vectorAssetFile: String = "vi_fasttext_pruned.vec",
    val qaAssetFile_Gemma: String = "qa_database.txt",
    val vectorAssetFile_Gemma: String = "embeddinggemma-300m.tflite",

    // ── Sherpa-ONNX STT assets (used only when sttType = SHERPA_ONNX) ───
    val sherpaEncoderAsset: String = "model/encoder-epoch-20-avg-10.int8.onnx",
    val sherpaDecoderAsset: String = "model/decoder-epoch-20-avg-10.int8.onnx",
    val sherpaJoinerAsset: String = "model/joiner-epoch-20-avg-10.int8.onnx",
    val sherpaTokensAsset: String = "model/tokens.txt",
    val sherpaVadType: Int = 0,           // 0 = Silero VAD, 1 = TenVAD

    // ── LLM generation params ─────────────────────────────────────────────
    val llmMaxTokens: Int = 1024,
    val llmTemperature: Float = 0.7f,
    val llmTopK: Int = 20,
    val llmTopP: Float = 0.8f,
    val llmSystemPrompt: String = "Bạn là trợ lý ảo trả lời ngắn gọn tất cả câu hỏi.",

    // ── Piper TTS assets / config ─────────────────────────────────────────
    val piperModelDir: String      = "vits-piper-vi-ngochuyen",
    val piperModelName: String     = "ngochuyen.onnx",
    val piperTokensName: String    = "tokens.txt",
    val piperEspDataDir: String    = "vits-piper-vi-ngochuyen/espeak-ng-data",
    val piperSpeakerId: Int        = 0,
    val piperSpeed: Float          = 1.0f,
    val piperNumThreads: Int       = 4

)

enum class SttType {
    ANDROID,        // Android SpeechRecognizer SDK — no extra models needed
    SHERPA_ONNX     // Sherpa-ONNX offline ASR — requires ONNX models in assets
}

enum class LlmType {
    LITE_RT,        // Google LiteRT (.litertlm) — requires model on device storage
    GEMINI_API,     // Google Gemini REST API — requires internet + API key
    EXECUTORCH,     // PyTorch ExecuTorch (.pte) — requires ExecuTorch runtime
    NATIVE_CPP      // llama.cpp JNI (.gguf) — requires libai_core.so + model
}

enum class TtsType {
    ANDROID,         // Android TextToSpeech SDK — no extra models needed
    PIPER
}

enum class RagType {
    FASTTEXT,       // FastText word embeddings — requires .vec asset
    EMBEDDING, // Su dung embedding model
    NONE            // Disable RAG, go straight to LLM
}
