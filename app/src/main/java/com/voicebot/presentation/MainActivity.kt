package com.voicebot.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.voicebot.R
import com.voicebot.databinding.ActivityMainBinding
import com.voicebot.domain.model.BotConfig
import com.voicebot.domain.model.LlmType
import com.voicebot.domain.model.RagType
import com.voicebot.domain.model.SttType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Entry point — owns Android lifecycle and UI only.
 * All pipeline logic lives in [VoiceBotOrchestrator].
 *
 * ★ To switch STT/LLM/TTS/RAG engines:
 *   Edit [buildBotConfig] below — nothing else needs changing.
 */
class MainActivity : AppCompatActivity() {

    companion object { private const val TAG = "MainActivity" }

    private lateinit var binding: ActivityMainBinding
    private lateinit var orchestrator: VoiceBotOrchestrator
    private lateinit var chatAdapter: ChatAdapter

    private var isListeningEnabled = false
    private var isMicActive = false
    private var isInitialized = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) toggleListening(true)
            else Toast.makeText(this, "Cần quyền Microphone để hoạt động", Toast.LENGTH_SHORT).show()
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.e("DEBUG_CRASH", "1. MainActivity: Bắt đầu onCreate") // THÊM DÒNG NÀY

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.e("DEBUG_CRASH", "2. MainActivity: Đã set content view") // THÊM DÒNG NÀY

        setupRecyclerView()
        setupOrchestrator()
        setupButtons()
        startBotStateObserver()
    }

    override fun onDestroy() {
        super.onDestroy()
        orchestrator.release()
    }

    // ── Configuration ─────────────────────────────────────────────────────

    /**
     * ★ CHANGE ENGINES HERE.
     * The rest of the app adapts automatically via EngineFactory.
     */
    private fun buildBotConfig() = BotConfig(
        sttType = SttType.ANDROID,          // ← swap to SttType.SHERPA_ONNX for offline
        llmType = LlmType.LITE_RT,          // ← swap to GEMINI_API, EXECUTORCH, NATIVE_CPP
        ragType = RagType.FASTTEXT,         // ← swap to RagType.NONE to disable
        language = "vi-VN",
        geminiApiKey = "",                  // fill in if llmType = GEMINI_API
        llmSystemPrompt = "Bạn là trợ lý ảo của MISA. Trả lời ngắn gọn bằng tiếng Việt."
    )

    // ── Setup ─────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter()
        binding.rvChat.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply { stackFromEnd = true }
            adapter = chatAdapter
        }
    }

    private fun setupOrchestrator() {
        orchestrator = VoiceBotOrchestrator(this, buildBotConfig())

        // STT callbacks → forward to orchestrator
        orchestrator.sttEngine.apply {
            onListeningStarted = { isMicActive = true; updateStatusUI() }
            onListeningStopped = { isMicActive = false; updateStatusUI() }
            onError = { isMicActive = false; updateStatusUI() }
            onResult = { text ->
                Log.i(TAG, "STT result: $text")
                orchestrator.onUserSpeechFinalized(text)
            }
        }

        // Orchestrator → UI callbacks
        orchestrator.onLog = { msg, isUpdate ->
            runOnUiThread {
                if (isUpdate) chatAdapter.updateLastMessage(msg) else chatAdapter.addMessage(msg)
                binding.rvChat.scrollToPosition(chatAdapter.itemCount - 1)
            }
        }
        orchestrator.onMetricsUpdate = { metrics ->
            runOnUiThread {
                binding.tvMetricQuery.text = "${metrics.getQueryLatency()}ms"
                binding.tvMetricLlm.text   = "${metrics.getLlmLatency()}ms"
                binding.tvMetricTts.text   = "${metrics.getTtsLatency()}ms"
            }
        }

        // Heavy init off main thread
        lifecycleScope.launch {
            binding.btnToggleMic.isEnabled = false
            binding.btnClear.isEnabled = false
            orchestrator.init()
            isInitialized = true
            binding.btnToggleMic.isEnabled = true
            binding.btnClear.isEnabled = true
            updateStatusUI()
        }
    }

    private fun setupButtons() {
        binding.btnToggleMic.setOnClickListener {
            if (isListeningEnabled) toggleListening(false)
            else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) toggleListening(true)
                else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        binding.btnClear.setOnClickListener {
            chatAdapter.clear()
            orchestrator.reset()
            if (isListeningEnabled) toggleListening(false)
        }
    }

    // ── Mic control ───────────────────────────────────────────────────────

    private fun toggleListening(enable: Boolean) {
        isListeningEnabled = enable
        if (enable) startListeningInternal()
        else { orchestrator.sttEngine.stopListening(); isMicActive = false }
        updateStatusUI()
    }

    private fun startListeningInternal() {
        if (!isListeningEnabled || orchestrator.isBotBusy) return
        try { orchestrator.sttEngine.startListening() }
        catch (e: Exception) { Log.e(TAG, "startListening failed", e) }
    }

    /**
     * Polling loop: auto-stop mic while bot is busy; restart when bot is idle.
     * Keeps UI responsive without LiveData/StateFlow overhead.
     */
    private fun startBotStateObserver() {
        lifecycleScope.launch {
            while (true) {
                if (isListeningEnabled) {
                    val busy = orchestrator.isBotBusy
                    when {
                        busy && isMicActive -> {
                            orchestrator.sttEngine.stopListening()
                            isMicActive = false; updateStatusUI()
                        }
                        !busy && !isMicActive -> {
                            delay(300) // brief pause after TTS to avoid echo
                            startListeningInternal()
                        }
                    }
                }
                delay(200)
            }
        }
    }

    // ── UI state ──────────────────────────────────────────────────────────

    private fun updateStatusUI() = runOnUiThread {
        if (!isInitialized) {
            binding.tvStatus.text = "Đang khởi tạo hệ thống AI..."
            return@runOnUiThread
        }
        if (isListeningEnabled) {
            binding.tvStatus.text = if (isMicActive) "🎙️ Đang nghe..." else "⚙️ Đang xử lý / Nói..."
            binding.tvStatus.setTextColor(Color.parseColor("#10B981"))
            binding.btnToggleMic.text = "🛑 Dừng"
            binding.btnToggleMic.setBackgroundColor(Color.parseColor("#EF4444"))
        } else {
            binding.tvStatus.text = "Nhấn nút để bắt đầu"
            binding.tvStatus.setTextColor(Color.parseColor("#F9FAFB"))
            binding.btnToggleMic.text = "🎙️ Bắt đầu"
            binding.btnToggleMic.setBackgroundColor(Color.parseColor("#3B82F6"))
        }
    }
}
