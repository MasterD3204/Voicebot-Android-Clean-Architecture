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
import com.voicebot.databinding.ActivitySecondBinding
import com.voicebot.domain.model.BotConfig
import com.voicebot.domain.model.LlmType
import com.voicebot.domain.model.RagType
import com.voicebot.domain.model.SttType
import com.voicebot.domain.model.TtsType
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object { private const val TAG = "MainActivity" }

    private lateinit var binding: ActivitySecondBinding
    private lateinit var orchestrator: VoiceBotOrchestrator

    private var isListeningEnabled = false
    private var isMicActive        = false
    private var isInitialized      = false
    private var isInitializing     = false
    private var silenceTimeoutJob: Job? = null

    // ── Display mode (set once in onCreate, immutable afterwards) ─────────
    private var showAvatar = true

    // ── Optional 3D avatar (null = no avatar, non-null = avatar enabled) ──
    private var avatarController: AvatarController? = null

    // ── Chat mode adapter (used only when showAvatar = false) ─────────────
    private val chatAdapter = ChatAdapter()

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
        binding = ActivitySecondBinding.inflate(layoutInflater)
        setContentView(binding.root)

        showAvatar = intent.getBooleanExtra(SetupActivity.EXTRA_SHOW_AVATAR, true)

        setupDisplayMode()
        setupButtons()
        startBotStateObserver()

        if (hasStoragePermission()) {
            setupOrchestrator()
            initOrchestrator()
        } else {
            requestStoragePermission()
        }
    }

    // ── Display mode setup ────────────────────────────────────────────────

    private fun setupDisplayMode() {
        if (showAvatar) {
            // Avatar mode: SceneView hiển thị, RecyclerView ẩn, tvUserTalk/tvBotTalk hiển thị
            binding.sceneView.visibility  = android.view.View.VISIBLE
            binding.rvChat.visibility     = android.view.View.GONE
            binding.tvUserTalk.visibility = android.view.View.VISIBLE
            binding.tvBotTalk.visibility  = android.view.View.VISIBLE
            avatarController = AvatarController(this, binding.sceneView).also { it.setup() }
        } else {
            // Chat mode: SceneView ẩn, RecyclerView hiển thị, tvUserTalk/tvBotTalk ẩn
            binding.sceneView.visibility  = android.view.View.GONE
            binding.tvUserTalk.visibility = android.view.View.GONE
            binding.tvBotTalk.visibility  = android.view.View.GONE
            binding.rvChat.visibility     = android.view.View.VISIBLE
            binding.rvChat.layoutManager = LinearLayoutManager(this).apply {
                stackFromEnd = true   // scroll mới nhất ở cuối
            }
            binding.rvChat.adapter = chatAdapter
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isInitialized && !isInitializing && hasStoragePermission()) {
            setupOrchestrator()
            initOrchestrator()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        avatarController?.destroy()
        avatarController = null
        releaseOrchestrator()
    }

    // ── Resource management ───────────────────────────────────────────────

    private fun releaseOrchestrator() {
        if (!::orchestrator.isInitialized) return
        try {
            orchestrator.release()
            Log.i(TAG, "✅ Orchestrator released")
        } catch (e: Throwable) {
            Log.e(TAG, "Error releasing orchestrator", e)
        }
    }

    private fun goBackToSetup() {
        if (isListeningEnabled) {
            isListeningEnabled = false
            runCatching { orchestrator.sttEngine.stopListening() }
        }

        binding.btnToggleMic.isEnabled = false
        binding.btnClear.isEnabled     = false
        binding.btnSettings.isEnabled  = false
        binding.tvStatus.text          = "⏳ Đang giải phóng tài nguyên..."

        lifecycleScope.launch {
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

    private fun buildBotConfig(): BotConfig {
        val llmType = intent.getStringExtra(SetupActivity.EXTRA_LLM_TYPE)
            ?.let { runCatching { LlmType.valueOf(it) }.getOrNull() }
            ?: LlmType.LITE_RT

        val modelName = intent.getStringExtra(SetupActivity.EXTRA_LLM_MODEL_NAME) ?: ""
        val geminiKey = intent.getStringExtra(SetupActivity.EXTRA_GEMINI_KEY)
            ?.takeIf { it.isNotBlank() }
            ?: ""
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
            geminiApiKey           = geminiKey,
            liteRtModelName        = if (llmType == LlmType.LITE_RT)     modelName else BotConfig().liteRtModelName,
            nativeLlmModelName     = if (llmType == LlmType.NATIVE_CPP)  modelName else BotConfig().nativeLlmModelName,
            execuTorchFolderName   = if (llmType == LlmType.EXECUTORCH)  modelName else BotConfig().execuTorchFolderName,
            llmSystemPrompt = "Bạn là trợ lý ảo của MISA. Trả lời ngắn gọn bằng tiếng Việt."
        )
    }

    // ── Setup ─────────────────────────────────────────────────────────────

    private fun setupOrchestrator() {
        if (::orchestrator.isInitialized) return
        orchestrator = VoiceBotOrchestrator(this, buildBotConfig())

        orchestrator.sttEngine.apply {
            onListeningStarted = {
                if (isListeningEnabled) {
                    isMicActive = true
                    updateStatusUI()
                } else {
                    orchestrator.sttEngine.stopListening()
                }
            }
            onListeningStopped = {
                isMicActive = false
                cancelSilenceTimer()
                updateStatusUI()
            }
            onError = {
                isMicActive = false
                cancelSilenceTimer()
                updateStatusUI()
            }
            onPartialResult = { _ -> startSilenceTimer() }
            onResult = { text ->
                cancelSilenceTimer()
                Log.i(TAG, "STT result: $text")
                orchestrator.onUserSpeechFinalized(text)
            }
        }

        orchestrator.onLog = { msg, isUpdate ->
            runOnUiThread {
                if (showAvatar) {
                    // Avatar mode: text overlay nhỏ phía trên SceneView
                    when {
                        msg.startsWith("User:") -> binding.tvUserTalk.text = msg.removePrefix("User:").trim()
                        msg.startsWith("Bot:")  -> binding.tvBotTalk.text  = msg.removePrefix("Bot:").trim()
                    }
                } else {
                    // Chat mode: đẩy vào RecyclerView
                    when {
                        msg.startsWith("User:") -> {
                            // Mỗi lần nói mới = tin nhắn mới
                            if (!isUpdate) chatAdapter.addMessage(msg)
                            else chatAdapter.updateLastMessage(msg)
                        }
                        msg.startsWith("Bot:") -> {
                            // Stream: update in-place nếu isUpdate, thêm mới nếu không
                            if (!isUpdate) chatAdapter.addMessage(msg)
                            else chatAdapter.updateLastMessage(msg)
                        }
                        msg.startsWith("System:") -> {
                            // Thông báo hệ thống (khởi tạo, lỗi) — thêm như bot message
                            if (!isUpdate) chatAdapter.addMessage("Bot: ${ msg.removePrefix("System:").trim() }")
                            else chatAdapter.updateLastMessage("Bot: ${ msg.removePrefix("System:").trim() }")
                        }
                    }
                    // Auto-scroll xuống cuối
                    binding.rvChat.scrollToPosition(chatAdapter.itemCount - 1)
                }
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

        // ── Avatar callbacks (no-op when avatarController is null) ────────
        orchestrator.onTtsStartWithLength = { isLong ->
            avatarController?.onTtsStart(isLong)
        }
        orchestrator.ttsEngine.onSpeechDone = {
            avatarController?.onTtsDone()
        }
        orchestrator.onAllSpeechComplete = {
            runOnUiThread {
                avatarController?.onAllSpeechDone()
                if (showAvatar) {
                    // Avatar mode: xóa overlay text sau khi nói xong
                    binding.tvBotTalk.text  = ""
                    binding.tvUserTalk.text = ""
                }
                // Chat mode: giữ nguyên lịch sử chat
            }
        }
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
        binding.btnToggleMic.setOnClickListener {
            if (isListeningEnabled) toggleListening(false)
            else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) toggleListening(true)
                else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        binding.btnClear.setOnClickListener {
            if (showAvatar) {
                binding.tvUserTalk.text = ""
                binding.tvBotTalk.text  = ""
            } else {
                chatAdapter.clear()
            }
            orchestrator.reset()
            if (isListeningEnabled) toggleListening(false)
        }

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

    // ── Silence timeout ───────────────────────────────────────────────────

    private fun startSilenceTimer() {
        silenceTimeoutJob?.cancel()
        silenceTimeoutJob = lifecycleScope.launch {
            delay(1000)
            if (isMicActive) {
                Log.i(TAG, "🔇 Silence timeout — stopping STT")
                orchestrator.sttEngine.stopListening()
            }
        }
    }

    private fun cancelSilenceTimer() {
        silenceTimeoutJob?.cancel()
        silenceTimeoutJob = null
    }

    // ── Mic control ───────────────────────────────────────────────────────

    private fun toggleListening(enable: Boolean) {
        isListeningEnabled = enable
        if (enable) startListeningInternal()
        else {
            cancelSilenceTimer()
            orchestrator.sttEngine.stopListening()
            isMicActive = false
        }
        updateStatusUI()
    }

    private fun startListeningInternal() {
        if (!isListeningEnabled || orchestrator.isBotBusy) return
        try { orchestrator.sttEngine.startListening() }
        catch (e: Exception) { Log.e(TAG, "startListening failed", e) }
    }

    private fun startBotStateObserver() {
        lifecycleScope.launch {
            while (isActive) {
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
