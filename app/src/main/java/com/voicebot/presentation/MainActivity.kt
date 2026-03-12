package com.voicebot.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.voicebot.databinding.ActivityMainBinding
import com.voicebot.domain.model.BotConfig
import com.voicebot.domain.model.LlmType
import com.voicebot.domain.model.RagType
import com.voicebot.domain.model.SttType
import com.voicebot.domain.model.TtsType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object { private const val TAG = "MainActivity" }

    private lateinit var binding: ActivityMainBinding
    private lateinit var orchestrator: VoiceBotOrchestrator
    private lateinit var chatAdapter: ChatAdapter

    private var isListeningEnabled = false
    private var isMicActive        = false
    private var isInitialized      = false
    private var isInitializing     = false   // ← guard tránh double init

    // ── Permission launchers ──────────────────────────────────────────────

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) toggleListening(true)
            else Toast.makeText(this, "Cần quyền Microphone để hoạt động", Toast.LENGTH_SHORT).show()
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted)
                Toast.makeText(this, "Không có quyền đọc file — model sẽ không load được", Toast.LENGTH_LONG).show()
            setupOrchestrator()
            initOrchestrator()
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupButtons()
        startBotStateObserver()

        if (hasStoragePermission()) {
            setupOrchestrator()
            initOrchestrator()
        } else {
            requestStoragePermission()
        }
    }

    override fun onResume() {
        super.onResume()
        // Chỉ init khi quay lại từ Settings (cấp MANAGE_EXTERNAL_STORAGE)
        // isInitializing guard tránh chạy lại khi onResume kế tiếp ngay sau onCreate
        if (!isInitialized && !isInitializing && hasStoragePermission()) {
            setupOrchestrator()
            initOrchestrator()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseOrchestrator()
    }

    // ── Resource management ───────────────────────────────────────────────

    /**
     * Giải phóng TOÀN BỘ tài nguyên: coroutine scope, STT, TTS, RAG, LLM.
     * Gọi trước khi quay về SetupActivity hoặc khi Activity bị destroy.
     */
    private fun releaseOrchestrator() {
        if (!::orchestrator.isInitialized) return
        try {
            orchestrator.release()
            Log.i(TAG, "✅ Orchestrator released")
        } catch (e: Throwable) {
            Log.e(TAG, "Error releasing orchestrator", e)
        }
    }

    /**
     * Quay về SetupActivity: dừng mic → release tài nguyên → restart fresh.
     * SetupActivity được tạo mới → không giữ state cũ.
     * FLAG_CLEAR_TOP đảm bảo không có MainActivity nào còn trong stack.
     */
    private fun goBackToSetup() {
        // Dừng mic trước
        if (isListeningEnabled) {
            isListeningEnabled = false
            runCatching { orchestrator.sttEngine.stopListening() }
        }

        // Disable buttons, hiện thông báo
        binding.btnToggleMic.isEnabled  = false
        binding.btnClear.isEnabled      = false
        binding.btnSettings.isEnabled   = false
        binding.tvStatus.text           = "⏳ Đang giải phóng tài nguyên..."

        lifecycleScope.launch {
            // ⚠️ KHÔNG dùng withContext(IO): SpeechRecognizer.destroy() bắt buộc chạy trên Main thread
            // Các engine khác (TTS, RAG, LLM) cũng được gọi trên Main — nhanh, không block lâu
            releaseOrchestrator()
            startActivity(
                Intent(this@MainActivity, SetupActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            finish()
        }
    }

    // ── Storage permission ────────────────────────────────────────────────

    private fun hasStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager()
        else
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setTitle("Cần quyền truy cập bộ nhớ")
                .setMessage(
                    "App cần quyền quản lý file để đọc model AI.\n\n" +
                            "Vui lòng bật 'Cho phép quản lý tất cả file' trong màn hình tiếp theo.\n\n" +
                            "Sau đó đặt file model vào /sdcard/Download/"
                )
                .setPositiveButton("Mở Settings") { _, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
                .setNegativeButton("Bỏ qua") { _, _ ->
                    setupOrchestrator()
                    initOrchestrator()
                }
                .show()
        } else {
            storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    // ── Configuration ─────────────────────────────────────────────────────

    /**
     * Đọc TẤT CẢ lựa chọn từ SetupActivity qua Intent extras và build BotConfig.
     *
     * Mapping:
     *   LiteRT      → liteRtModelName         = selectedModelName (.litertlm)
     *   NativeCpp   → nativeLlmModelName       = selectedModelName (.gguf)
     *   ExecuTorch  → execuTorchFolderName     = selectedModelName (tên folder)
     *   GeminiAPI   → geminiApiKey             = nhập từ EditText
     */
    private fun buildBotConfig(): BotConfig {
        val llmType = intent.getStringExtra(SetupActivity.EXTRA_LLM_TYPE)
            ?.let { runCatching { LlmType.valueOf(it) }.getOrNull() }
            ?: LlmType.LITE_RT

        val modelName = intent.getStringExtra(SetupActivity.EXTRA_LLM_MODEL_NAME) ?: ""
        val geminiKey = intent.getStringExtra(SetupActivity.EXTRA_GEMINI_KEY)
            ?.takeIf { it.isNotBlank() }
            ?: "AIzaSyAsAEaZw4D7y2cESBVoyymdxmV2kKJOZks"
        val sttType = intent.getStringExtra(SetupActivity.EXTRA_STT_TYPE)
            ?.let { runCatching { SttType.valueOf(it) }.getOrNull() }
            ?: SttType.ANDROID

        val ragType = intent.getStringExtra(SetupActivity.EXTRA_RAG_TYPE)
            ?.let { runCatching { RagType.valueOf(it) }.getOrNull() }
            ?: RagType.FASTTEXT

        val ttsType = intent.getStringExtra(SetupActivity.EXTRA_TTS_TYPE)
            ?.let { runCatching { TtsType.valueOf(it) }.getOrNull() }
            ?: TtsType.ANDROID

        return BotConfig(
            sttType  = sttType,
            llmType  = llmType,
            ttsType  = ttsType,
            ragType  = ragType,
            language = "vi-VN",
            // LLM-specific: chỉ field tương ứng loại mới có giá trị thực
            geminiApiKey           = geminiKey,
            liteRtModelName        = if (llmType == LlmType.LITE_RT)     modelName else BotConfig().liteRtModelName,
            nativeLlmModelName     = if (llmType == LlmType.NATIVE_CPP)  modelName else BotConfig().nativeLlmModelName,
            execuTorchFolderName   = if (llmType == LlmType.EXECUTORCH)  modelName else BotConfig().execuTorchFolderName,
            llmSystemPrompt = "Bạn là trợ lý ảo của MISA. Trả lời ngắn gọn bằng tiếng Việt."
        )
    }

    // ── Setup ─────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter()
        binding.rvChat.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply { stackFromEnd = true }
            adapter = chatAdapter
        }
    }

    private fun setupOrchestrator() {
        if (::orchestrator.isInitialized) return
        orchestrator = VoiceBotOrchestrator(this, buildBotConfig())

        orchestrator.sttEngine.apply {
            onListeningStarted = { isMicActive = true;  updateStatusUI() }
            onListeningStopped = { isMicActive = false; updateStatusUI() }
            onError            = { isMicActive = false; updateStatusUI() }
            onResult           = { text ->
                Log.i(TAG, "STT result: $text")
                orchestrator.onUserSpeechFinalized(text)
            }
        }

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
        orchestrator.onBotBusyChanged = { _ -> updateStatusUI() }
    }

    private fun initOrchestrator() {
        isInitializing = true
        lifecycleScope.launch {
            binding.btnToggleMic.isEnabled = false
            binding.btnClear.isEnabled     = false
            binding.btnSettings.isEnabled  = false
            try {
                orchestrator.init()
            } catch (e: Throwable) {
                Log.e(TAG, "orchestrator.init() failed", e)
                Toast.makeText(this@MainActivity, "Lỗi khởi tạo: ${e.message}", Toast.LENGTH_LONG).show()
            }
            isInitialized  = true
            isInitializing = false
            binding.btnToggleMic.isEnabled = true
            binding.btnClear.isEnabled     = true
            binding.btnSettings.isEnabled  = true
            updateStatusUI()
        }
    }

    private fun setupButtons() {
        // Mic
        binding.btnToggleMic.setOnClickListener {
            if (isListeningEnabled) toggleListening(false)
            else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) toggleListening(true)
                else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        // Clear
        binding.btnClear.setOnClickListener {
            chatAdapter.clear()
            orchestrator.reset()
            if (isListeningEnabled) toggleListening(false)
        }

        // Settings — quay lại màn hình cấu hình với xác nhận
        binding.btnSettings.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("⚙️ Đổi cấu hình?")
                .setMessage(
                    "Toàn bộ model đang tải sẽ được giải phóng khỏi bộ nhớ.\n\n" +
                            "Bạn sẽ cần chọn lại và tải lại engine từ đầu.\n\n" +
                            "Tiếp tục?"
                )
                .setPositiveButton("Đổi cấu hình") { _, _ -> goBackToSetup() }
                .setNegativeButton("Hủy", null)
                .show()
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

    private fun startBotStateObserver() {
        lifecycleScope.launch {
            while (true) {
                if (::orchestrator.isInitialized && isListeningEnabled) {
                    val busy = orchestrator.isBotBusy
                    when {
                        busy && isMicActive -> {
                            orchestrator.sttEngine.stopListening()
                            isMicActive = false; updateStatusUI()
                        }
                        !busy && !isMicActive -> {
                            delay(300)
                            startListeningInternal()
                        }
                    }
                }
                delay(200)
            }
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private fun updateStatusUI() = runOnUiThread {
        if (!isInitialized) {
            binding.tvStatus.text = "⏳ Đang khởi tạo hệ thống AI..."
            binding.tvStatus.setTextColor(Color.parseColor("#9CA3AF"))
            return@runOnUiThread
        }
        if (isListeningEnabled) {
            binding.tvStatus.text = if (isMicActive) "🎙️ Đang nghe..." else "⚙️ Đang xử lý / Nói..."
            binding.tvStatus.setTextColor(Color.parseColor("#10B981"))
            binding.btnToggleMic.text = "🛑 Dừng"
            binding.btnToggleMic.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#EF4444"))
        } else {
            binding.tvStatus.text = "Nhấn nút để bắt đầu"
            binding.tvStatus.setTextColor(Color.parseColor("#F9FAFB"))
            binding.btnToggleMic.text = "🎙️ Bắt đầu"
            binding.btnToggleMic.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#3B82F6"))
        }
    }
}