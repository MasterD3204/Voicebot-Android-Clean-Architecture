# VoiceBot — Clean Architecture

## Package Structure

```
com.voicebot/
│
├── domain/                         ← Pure Kotlin, zero Android imports
│   ├── model/
│   │   ├── BotConfig.kt            Engine selection + all config params
│   │   ├── ChatMessage.kt          Data class for chat log
│   │   └── PerfMetrics.kt          Latency tracking (STT→LLM→TTS)
│   │
│   ├── port/                       Interfaces (Ports — the "P" in Hexagonal)
│   │   ├── SttEngine.kt
│   │   ├── LlmEngine.kt
│   │   ├── TtsEngine.kt
│   │   ├── RagEngine.kt
│   │   └── TextNormalizer.kt
│   │
│   └── usecase/
│       ├── VoiceQueryUseCase.kt    STT result → RAG/LLM pipeline (pure logic)
│       └── BargeInDetector.kt      Quick acknowledgment short-circuit
│
├── data/                           Framework-specific implementations (Adapters)
│   ├── stt/
│   │   ├── android/AndroidSttEngine.kt    Android SpeechRecognizer SDK
│   │   └── sherpa/SherpaSttEngine.kt      Sherpa-ONNX offline ASR + VAD
│   │
│   ├── llm/
│   │   ├── litert/LiteRtLlmEngine.kt      Google LiteRT (.litertlm)
│   │   ├── gemini/GeminiLlmEngine.kt      Google Gemini API
│   │   ├── executorch/ExecuTorchLlmEngine.kt  PyTorch ExecuTorch (.pte) [stub]
│   │   └── native/NativeLlmEngine.kt      llama.cpp JNI (.gguf)
│   │
│   ├── tts/
│   │   └── android/AndroidTtsEngine.kt    Android TextToSpeech SDK
│   │
│   ├── rag/
│   │   └── fasttext/FastTextRagEngine.kt  FastText cosine similarity search
│   │
│   ├── normalizer/
│   │   └── TextNormalizers.kt             Number + Product + Composite
│   │
│   └── factory/
│       └── EngineFactory.kt        ★ Single place to swap implementations
│
└── presentation/                   Android UI layer
    ├── MainActivity.kt             Lifecycle owner; zero pipeline logic
    ├── VoiceBotOrchestrator.kt     Wires engines; drives STT→RAG/LLM→TTS
    └── ChatAdapter.kt              RecyclerView for chat bubbles
```

---

## How to Switch Engines

**Everything is controlled by `BotConfig` in `MainActivity.buildBotConfig()`:**

```kotlin
private fun buildBotConfig() = BotConfig(
    sttType = SttType.ANDROID,        // or SHERPA_ONNX
    llmType = LlmType.LITE_RT,        // or GEMINI_API, EXECUTORCH, NATIVE_CPP
    ragType = RagType.FASTTEXT,       // or NONE
    language = "vi-VN",
    geminiApiKey = "YOUR_KEY",        // only if llmType = GEMINI_API
)
```

`EngineFactory` reads the config and instantiates the correct implementation.
**No other code changes are needed when switching backends.**

---

## Engine Matrix

| Type | Class | Format | Notes |
|------|-------|--------|-------|
| **STT** | `AndroidSttEngine` | — | Online, needs internet |
| **STT** | `SherpaSttEngine` | `.onnx` | Fully offline, VAD included |
| **LLM** | `LiteRtLlmEngine` | `.litertlm` | GPU/CPU auto-fallback |
| **LLM** | `GeminiLlmEngine` | REST API | Streaming, needs internet |
| **LLM** | `ExecuTorchLlmEngine` | `.pte` | Stub — implement JNI |
| **LLM** | `NativeLlmEngine` | `.gguf` | llama.cpp JNI, fully offline |
| **TTS** | `AndroidTtsEngine` | — | Built-in, no extra models |
| **RAG** | `FastTextRagEngine` | `.vec` | FastText cosine similarity |
| **RAG** | `NoOpRagEngine` | — | Disable RAG, always use LLM |

---

## Files Removed vs Original

| Original File | Status | Reason |
|--------------|--------|--------|
| `SimulateStreamingAsr.kt` | ❌ Removed | Was already commented out |
| `NativeLib.kt` | ❌ Removed | Replaced by `NativeLlmEngine.kt` |
| `VoiceBotManager.kt` | ♻️ Replaced | → `VoiceBotOrchestrator.kt` |
| `GeminiHelper.kt` | ♻️ Replaced | → `GeminiLlmEngine.kt` |
| `LiteRTManager.kt` | ♻️ Replaced | → `LiteRtLlmEngine.kt` |
| `AndroidTTSManager.kt` | ♻️ Replaced | → `AndroidTtsEngine.kt` |
| `QAEngine.kt` | ♻️ Replaced | → `FastTextRagEngine.kt` |
| `NumberNormalizer.kt` | ♻️ Replaced | → `TextNormalizers.kt` |
| `MisaProductNormalizer.kt` | ♻️ Replaced | → `TextNormalizers.kt` |
| `BargeInKeywords.kt` | ♻️ Replaced | → `BargeInDetector.kt` |
| `FileStorageHelper.kt` | ✅ Keep | Used by SherpaSttEngine |
| `OfflineRecognizer.kt` | ✅ Keep | Sherpa-ONNX wrapper |
| `OfflineStream.kt` | ✅ Keep | Sherpa-ONNX wrapper |
| `Vad.kt` | ✅ Keep | Sherpa-ONNX VAD wrapper |
| `FeatureConfig.kt` | ✅ Keep | Sherpa-ONNX config |
| `HomophoneReplacerConfig.kt` | ✅ Keep | Sherpa-ONNX config |
| `QnnConfig.kt` | ✅ Keep | Sherpa-ONNX config |

---

## Query Pipeline

```
User speaks
    │
    ▼
SttEngine.onResult(text)
    │
    ▼
VoiceBotOrchestrator.onUserSpeechFinalized(text)
    │
    ▼
VoiceQueryUseCase.execute(text)
    │
    ├─ BargeInDetector hit → instant canned response
    │
    ├─ RagEngine.search() hit → stream words to UI, speak
    │
    └─ LlmEngine.chatStream() → token stream → sentence chunking → TTS queue
                                                    │
                                                    ▼
                                             TextNormalizer (numbers + products)
                                                    │
                                                    ▼
                                             TtsEngine.speak()
```

---

## Adding a New Engine

1. Implement the relevant port interface (e.g. `LlmEngine`)
2. Add an enum value to `BotConfig` (e.g. `LlmType.MY_ENGINE`)
3. Add a `when` branch in `EngineFactory.createLlmEngine()`
4. Select it in `MainActivity.buildBotConfig()`

That's it — 4 steps, no other files touched.
