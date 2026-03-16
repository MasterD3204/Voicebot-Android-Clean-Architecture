package com.voicebot.presentation

import android.content.Context
import android.util.Log
import com.voicebot.data.factory.EngineFactory
import com.voicebot.data.tts.piper.PiperTtsEngine
import com.voicebot.domain.model.BotConfig
import com.voicebot.domain.model.PerfMetrics
import com.voicebot.domain.model.RagType
import com.voicebot.domain.model.LlmType
import com.voicebot.domain.model.TtsType
import com.voicebot.domain.port.LlmEngine
import com.voicebot.domain.port.RagEngine
import com.voicebot.domain.port.SttEngine
import com.voicebot.domain.port.TextNormalizer
import com.voicebot.domain.port.TtsEngine
import com.voicebot.domain.usecase.BargeInDetector
import com.voicebot.domain.usecase.QueryResult
import com.voicebot.domain.usecase.VoiceQueryUseCase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow

/**
 * Orchestrates the STT → RAG/LLM → TTS pipeline.
 *
 * Lifecycle:
 *   1. Create with a [BotConfig]
 *   2. Call [init] (suspending, heavy work)
 *   3. Wire [sttEngine] callbacks from Activity/Fragment
 *   4. Call [onUserSpeechFinalized] when STT emits a result
 *   5. Call [release] in onDestroy
 */
class VoiceBotOrchestrator(
    private val context: Context,
    private val config: BotConfig = BotConfig()
) {
    companion object { private const val TAG = "VoiceBotOrchestrator" }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Engines (via factory, easily swappable) ───────────────────────────
    val sttEngine: SttEngine = EngineFactory.createSttEngine(context, config)
    private val llmEngine: LlmEngine = EngineFactory.createLlmEngine(context, config)
    val ttsEngine: TtsEngine = EngineFactory.createTtsEngine(context, config)
    private val ragEngine: RagEngine = EngineFactory.createRagEngine(context, config.ragType)
    private val textNormalizer: TextNormalizer = EngineFactory.createTextNormalizer(context)

    // ── Use case ──────────────────────────────────────────────────────────
    private val useCase = VoiceQueryUseCase(
        ragEngine      = ragEngine,
        llmEngine      = llmEngine,
        bargeInDetector = BargeInDetector(),
        isRagOnly      = (config.llmType == com.voicebot.domain.model.LlmType.RAG_ONLY)
    )

    // ── State ─────────────────────────────────────────────────────────────
    @Volatile var isBotBusy = false
        private set

    private var currentMetrics = PerfMetrics()
    private var processJob: Job? = null
    private var sentenceCount = 0
    private var isFirstToken = true

    // ── Callbacks (set by Activity/Fragment) ──────────────────────────────
    var onLog: ((message: String, isUpdate: Boolean) -> Unit)? = null
    var onMetricsUpdate: ((PerfMetrics) -> Unit)? = null
    var onBotBusyChanged: ((Boolean) -> Unit)? = null
    var onAllSpeechComplete: (() -> Unit)? = null
    var onTtsStartWithLength: ((isLong: Boolean) -> Unit)? = null

    private var isTtsQueueComplete = false

    init {
        ttsEngine.onSpeechStart = { setBotBusy(true) }
        ttsEngine.onAllSpeechDone = {
            if (isTtsQueueComplete) {
                isTtsQueueComplete = false
                setBotBusy(false)
                CoroutineScope(Dispatchers.Main).launch { onAllSpeechComplete?.invoke() }
            }
        }
    }

    // ── Initialisation ────────────────────────────────────────────────────

    suspend fun init() {
        Log.e("DEBUG_CRASH", "3. Orchestrator: Bắt đầu init() các engine") // THÊM

        logToUI("System: Đang khởi tạo...", false)

        // Init Piper TTS nếu được chọn (cần tìm file trên storage)
        if (config.ttsType == TtsType.PIPER) {
            val ttsOk = withContext(Dispatchers.IO) {
                (ttsEngine as? PiperTtsEngine)?.init() ?: false
            }
            logToUI(
                if (ttsOk) "✅ Piper TTS sẵn sàng!"
                else "⚠️ Piper TTS: không tìm thấy model — kiểm tra /sdcard/Download/",
                false
            )
        }

        // RAG_ONLY: RagOnlyLlmEngine tự load QA+vector trong llmEngine.init() bên dưới
        // Các mode khác: init ragEngine riêng để inject context vào LLM
        if (config.llmType != LlmType.RAG_ONLY && config.ragType != RagType.NONE) {
            try {
                val qaFile     = if (config.ragType == RagType.EMBEDDING) "gemma_database.txt" else config.qaAssetFile
                val vectorFile = if (config.ragType == RagType.EMBEDDING) "" else config.vectorAssetFile
                ragEngine.initialize(qaFile, vectorFile)
                Log.i(TAG, "RAG initialized (${config.ragType.name})")
            } catch (e: Exception) {
                logToUI("⚠️ Không tải được QA database.", false)
            }
        }

        Log.e("DEBUG_CRASH", "6. Orchestrator: Chuẩn bị load LLM") // THÊM
        val ok = withContext(Dispatchers.IO) { llmEngine.init() }
        Log.e("DEBUG_CRASH", "7. Orchestrator: Load LLM xong, kết quả: $ok") // THÊM
        logToUI(
            if (ok) "✅ Bot sẵn sàng (${config.llmType.name})!"
            else "❌ Lỗi: Không khởi tạo được LLM — kiểm tra model path.",
            false
        )
    }

    // ── Main entry point ──────────────────────────────────────────────────

    fun onUserSpeechFinalized(text: String) {
        if (text.isBlank() || isBotBusy) return

        currentMetrics = PerfMetrics(sttEndTime = System.currentTimeMillis())
        isFirstToken = true; sentenceCount = 0
        notifyMetrics()

        val display = text.trim().replaceFirstChar { it.uppercaseChar() }
        logToUI("User: $display", false)
        setBotBusy(true)

        processJob = scope.launch {
            logToUI("Bot: ", false)
            try {
                when (val result = useCase.execute(text)) {
                    is QueryResult.Acknowledgment -> {
                        currentMetrics.queryEndTime = System.currentTimeMillis()
                        notifyMetrics()
                        streamWords(result.response)
                        speakText(result.response, 0)
                        finalizeTtsQueue()
                    }
                    is QueryResult.LlmStream -> {
                        currentMetrics.queryEndTime = System.currentTimeMillis()
                        notifyMetrics()
                        consumeStream(result.flow)
                    }
                    is QueryResult.Error -> {
                        logToUI("Bot: [Lỗi] ${result.message}", true)
                        setBotBusy(false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline error", e)
                logToUI("Bot: [Lỗi hệ thống] ${e.message}", true)
                setBotBusy(false)
            }
        }
    }

    // ── Internal pipeline helpers ─────────────────────────────────────────

    /** Animate a static string word-by-word in the chat bubble */
    private suspend fun streamWords(text: String) {
        val sb = StringBuilder()
        text.split(" ").forEach { word ->
            sb.append("$word ")
            logToUI("Bot: ${sb.toString().trim()}", true)
            delay(30)
        }
    }

    private suspend fun consumeStream(flow: Flow<String>) {
        val fullText = StringBuilder()
        val sentenceBuf = StringBuilder()

        flow.collect { chunk ->
            if (isFirstToken) {
                val now = System.currentTimeMillis()
                currentMetrics.llmFirstTokenTime = now
                currentMetrics.firstChunkReceivedTime = now
                notifyMetrics()
                isFirstToken = false
            }
            fullText.append(chunk)
            logToUI("Bot: $fullText", true)
            sentenceBuf.append(chunk)

            while (hasSentenceEnd(sentenceBuf.toString())) {
                val (sentence, rest) = cutSentence(sentenceBuf.toString())
                if (sentence.isNotBlank()) speakText(sentence, sentenceCount++)
                sentenceBuf.clear().append(rest)
            }
        }

        if (sentenceBuf.isNotBlank()) speakText(sentenceBuf.toString(), sentenceCount++)
        finalizeTtsQueue()
    }

    private fun finalizeTtsQueue() {
        isTtsQueueComplete = true
        ttsEngine.markQueueComplete()
    }

    private fun hasSentenceEnd(text: String) =
        text.any { it == '.' || it == '?' || it == '!' || it == '\n' || it == ',' || it == ':' }

    private fun cutSentence(text: String): Pair<String, String> {
        val idx = Regex("[.?!,:\\n]").find(text)?.range?.last?.plus(1)
            ?: return "" to text
        return text.substring(0, idx).trim() to text.substring(idx).trim()
    }

    private fun speakText(text: String, id: Int) {
        if (id == 0) {
            currentMetrics.ttsFirstAudioTime = System.currentTimeMillis()
            notifyMetrics()
        }
        val isLong = text.trim().split(Regex("\\s+")).count { it.isNotBlank() } >= 8
        CoroutineScope(Dispatchers.Main).launch {
            onTtsStartWithLength?.invoke(isLong)
        }
        ttsEngine.speak(textNormalizer.normalize(text), "utt_$id")
    }

    // ── State management ──────────────────────────────────────────────────

    private fun setBotBusy(busy: Boolean) {
        isBotBusy = busy
        CoroutineScope(Dispatchers.Main).launch { onBotBusyChanged?.invoke(busy) }
    }

    private fun notifyMetrics() {
        CoroutineScope(Dispatchers.Main).launch { onMetricsUpdate?.invoke(currentMetrics) }
    }

    private fun logToUI(msg: String, isUpdate: Boolean) {
        CoroutineScope(Dispatchers.Main).launch { onLog?.invoke(msg, isUpdate) }
    }

    fun reset() {
        processJob?.cancel(); processJob = null
        ttsEngine.stop()
        isBotBusy = false
        isTtsQueueComplete = false
        currentMetrics = PerfMetrics()
        notifyMetrics()
        useCase.resetHistory()
        Log.i(TAG, "Bot state reset")
    }

    fun release() {
        scope.cancel()
        sttEngine.destroy()
        ttsEngine.shutdown()
        ragEngine.release()
        llmEngine.release()
    }
}