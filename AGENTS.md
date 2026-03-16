# AGENTS.md — Voicebot Android Clean Architecture

## Project Overview

Offline-first Android voice assistant (Kotlin) using Clean Architecture (Hexagonal/Ports-and-Adapters).
Pipeline: `SpeechRecognizer → VoiceQueryUseCase → RAG/LLM → TextNormalizer → TTS`.
Single Gradle module (`:app`). No Hilt/Dagger/Koin — manual factory-based DI.

---

## Build & Run Commands

All commands use the Gradle wrapper from the repo root:

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Install on connected device
./gradlew installDebug

# Clean build outputs
./gradlew clean

# Sync and check dependencies
./gradlew :app:dependencies
```

**Requirements:** JDK 17, Android Studio, physical ARM64 device (emulator is too slow for LLM).

---

## Test Commands

```bash
# Run all unit tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.voicebot.ExampleUnitTest"

# Run a single test method
./gradlew test --tests "com.voicebot.ExampleUnitTest.addition_isCorrect"

# Run instrumentation tests (requires connected device/emulator)
./gradlew connectedAndroidTest
```

Unit tests live in `app/src/test/java/`. Instrumentation tests go in `app/src/androidTest/java/`.
Test runner: `androidx.test.runner.AndroidJUnitRunner`.
There is no custom `testOptions` block — add one to `app/build.gradle.kts` if needed.

---

## SDK & Build Configuration

| Property | Value |
|---|---|
| `compileSdk` | 34 |
| `minSdk` | 31 |
| `targetSdk` | 34 |
| `ndkVersion` | 26.1.10909125 |
| `jvmTarget` | 17 |
| ABI filters | `arm64-v8a`, `armeabi-v7a` |
| View system | ViewBinding (Compose disabled) |

---

## Architecture — Package Layout

```
com.voicebot/
├── domain/                  ← Pure Kotlin, ZERO Android imports
│   ├── model/               BotConfig, ChatMessage, PerfMetrics
│   ├── port/                Interfaces: SttEngine, LlmEngine, TtsEngine, RagEngine, TextNormalizer
│   └── usecase/             VoiceQueryUseCase, BargeInDetector
│
├── data/                    ← Framework adapters (implement domain ports)
│   ├── stt/android/         AndroidSttEngine
│   ├── stt/sherpa/          SherpaSttEngine (Sherpa-ONNX)
│   ├── llm/litert/          LiteRtLlmEngine
│   ├── llm/gemini/          GeminiLlmEngine
│   ├── llm/executorch/      ExecuTorchLlmEngine (stub)
│   ├── llm/native/          NativeLlmEngine (llama.cpp JNI)
│   ├── tts/android/         AndroidTtsEngine
│   ├── rag/fasttext/        FastTextRagEngine
│   ├── normalizer/          TextNormalizers (Number, Product, Composite)
│   └── factory/             EngineFactory ★ single wiring point
│
└── presentation/            ← Android UI layer
    ├── MainActivity.kt      Lifecycle owner; zero pipeline logic
    ├── VoiceBotOrchestrator Wires engines; drives STT→RAG/LLM→TTS
    └── ChatAdapter.kt       RecyclerView chat bubbles
```

**The full query pipeline:** `SttEngine.onResult` → `VoiceBotOrchestrator` → `VoiceQueryUseCase.execute` → RAG hit / LLM stream → `TextNormalizer` → `TtsEngine.speak`.

---

## Adding a New Engine (4-Step Recipe)

1. Implement the port interface in `data/` (e.g. `data/llm/myengine/MyLlmEngine.kt : LlmEngine`)
2. Add an enum value to `BotConfig` (e.g. `LlmType.MY_ENGINE`)
3. Add a `when` branch in `EngineFactory.createLlmEngine()`
4. Select it in `MainActivity.buildBotConfig()`

No other files need to change.

---

## Naming Conventions

| Element | Convention | Example |
|---|---|---|
| Classes / Interfaces / Enums | `UpperCamelCase` | `VoiceQueryUseCase`, `LlmEngine` |
| Port interfaces | `UpperCamelCase` + `Engine` suffix | `LlmEngine`, `SttEngine` |
| Functions & properties | `lowerCamelCase` | `chatStream()`, `isReady()` |
| Compile-time constants | `UPPER_SNAKE_CASE` in `companion object` | `private const val TAG = "Foo"` |
| Runtime constant collections | `UPPER_SNAKE_CASE val` in `companion object` | `val RAG_KEYWORDS = setOf(...)` |
| Enum entries | `UPPER_CASE` | `MessageRole.USER`, `LlmType.LITE_RT` |
| Data classes | `UpperCamelCase` with default values for IDs/timestamps | `data class ChatMessage(val id: String = UUID...)` |
| Files | One top-level class per file, filename matches class name | `AndroidSttEngine.kt` |

---

## Import Ordering

Group imports in this order (no blank lines between groups is fine; logical grouping matters):

```kotlin
import android.*           // Platform
import androidx.*          // AndroidX
import com.voicebot.*      // Project (domain → data → presentation order)
import kotlinx.*           // Kotlin extensions
import java.*              // JVM stdlib
```

No strict alphabetization is enforced — keep groups cohesive.

---

## Coroutines & Flow Patterns

**Suspend functions for heavy I/O:**
```kotlin
override suspend fun init(): Boolean = withContext(Dispatchers.IO) {
    try { /* heavy work */ true }
    catch (e: Exception) { Log.e(TAG, "init failed", e); false }
}
```

**Streaming APIs as `Flow<String>`:**
```kotlin
override fun chatStream(query: String): Flow<String> = flow {
    if (!isReady()) { emit("Error: not initialized."); return@flow }
    // ...
}.flowOn(Dispatchers.IO)
```

**Bridging callback APIs into Flow with `Channel<Result<T>>`:**
```kotlin
val channel = Channel<Result<String>>(Channel.UNLIMITED)
val cb = object : NativeTokenCallback {
    override fun onToken(t: String) { channel.trySend(Result.success(t)) }
    override fun onDone()          { channel.close() }
    override fun onError(msg: String) { channel.close(RuntimeException(msg)) }
}
withContext(Dispatchers.IO) { nativeGenerateStream(prompt, 512, cb) }
for (result in channel) emit(result.getOrThrow())
```

**Orchestrator scope:**
```kotlin
private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
processJob = scope.launch {
    try { /* pipeline */ }
    catch (e: Exception) { Log.e(TAG, "Pipeline error", e); updateUiState() }
}
```

- Use `Dispatchers.Main` only for UI callback invocation.
- Store `Job` references to support cancellation (e.g., barge-in).
- Use `SupervisorJob` in orchestrator scopes so one child failure doesn't cancel siblings.

---

## Error Handling

**Sealed classes for use-case outputs (preferred over exceptions at boundaries):**
```kotlin
sealed class QueryResult {
    data class Acknowledgment(val response: String) : QueryResult()
    data class LlmStream(val flow: Flow<String>)    : QueryResult()
    data class Error(val message: String)           : QueryResult()
}
```

**Engine lifecycle: `Boolean` return + guard:**
```kotlin
override suspend fun init(): Boolean   // return false on failure, not throw
override fun isReady(): Boolean        // always check before use
```

**Top-level coroutine try/catch (in orchestrator):**
```kotlin
scope.launch {
    try { when (val result = useCase.execute(text)) { ... } }
    catch (e: Exception) { Log.e(TAG, "Pipeline error", e); /* update UI */ }
}
```

**Channel-based propagation:** Use `channel.close(RuntimeException(msg))` to push errors through a `Flow`; the collector receives them via `result.getOrThrow()`.

**Platform errors:** Map SDK error codes to human-readable names in `when` blocks before logging/propagating. Some transient errors (e.g., `ERROR_NO_MATCH`) should be swallowed silently.

---

## Dependency Injection

No DI framework. Use **constructor injection** exclusively.

`EngineFactory` (an `object`) is the **only** place that instantiates concrete engine implementations:
```kotlin
object EngineFactory {
    fun createLlmEngine(context: Context, config: BotConfig): LlmEngine =
        when (config.llmType) {
            LlmType.LITE_RT    -> LiteRtLlmEngine(...)
            LlmType.NATIVE_CPP -> NativeLlmEngine(...)
            else -> throw IllegalArgumentException("Unsupported: ${config.llmType}")
        }
}
```

Never instantiate engine implementations directly outside `EngineFactory`.

---

## Code Style Rules

- **`domain/`** must have **zero Android imports** — pure Kotlin only. Keep it unit-testable without Robolectric.
- Use `companion object { private const val TAG = "ClassName" }` in every class that logs.
- Use `by lazy { }` for heavy Android objects (e.g., `SpeechRecognizer`, file handles).
- Prefer `data class` with default values over mutable builders.
- Override event callbacks as `var` properties on port interfaces (e.g., `override var onResult: ((String) -> Unit)? = null`).
- Never suppress type errors with `as?` casts in lieu of real fixes, `@Suppress`, or `!!` where `?.let` is viable.
- Keep `MainActivity` free of pipeline logic — it owns lifecycle and config only.
- No linting config files exist (no `.editorconfig`, `detekt.yml`, or `ktlint`). Follow the patterns above manually.

---

## Native / JNI Notes

- C++ sources: `app/src/main/cpp/` with `CMakeLists.txt`.
- Prebuilt `.so` libs: `app/src/main/jniLibs/` (libonnxruntime, libsherpa-onnx-jni).
- JNI methods declared with `private external fun` in the engine class.
- Wrap all `external` calls in `try/catch(UnsatisfiedLinkError)` and `try/catch(Exception)`.
- Use `jniLibs { useLegacyPackaging = true }` (already configured) when shipping `.so` files.

---

## Key Files Reference

| File | Purpose |
|---|---|
| `app/build.gradle.kts` | SDK versions, NDK, dependencies |
| `gradle/libs.versions.toml` | Version catalog (plugin/dep aliases) |
| `domain/model/BotConfig.kt` | Engine selection config — start here |
| `data/factory/EngineFactory.kt` | Wiring point for all engine implementations |
| `domain/usecase/VoiceQueryUseCase.kt` | Core pipeline logic (STT → RAG/LLM) |
| `presentation/VoiceBotOrchestrator.kt` | Runtime wiring; drives pipeline |
| `ARCHITECTURE.md` (in `com/voicebot/`) | Canonical architecture reference |
