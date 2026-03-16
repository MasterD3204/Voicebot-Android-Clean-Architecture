package com.voicebot.presentation

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
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
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.voicebot.databinding.ActivitySecondBinding
import com.voicebot.domain.model.BotConfig
import com.voicebot.domain.model.LlmType
import com.voicebot.domain.model.RagType
import com.voicebot.domain.model.SttType
import com.voicebot.domain.model.TtsType
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.compareTo
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import kotlinx.coroutines.*


class SecondActivity : AppCompatActivity() {

    companion object { private const val TAG = "SecondActivity"
        private const val MODEL_PATH = "model/model_demo.glb"}

    private lateinit var binding: ActivitySecondBinding
    private lateinit var orchestrator: VoiceBotOrchestrator
    private lateinit var chatAdapter: ChatAdapter

    private var isListeningEnabled = false
    private var isMicActive        = false
    private var isInitialized      = false
    private var isInitializing     = false
    private var silenceTimeoutJob: Job? = null

    // ===== 3D Model Variables =====
    private var modelNode: ModelNode? = null
    private var parentNode: Node? = null

    // Auto rotation
    private var autoRotationAnimator: ValueAnimator? = null
    private var isAutoRotating = false
    private var currentRotationX = 0f
    private var currentRotationY = 0f
    private var currentRotationZ = 0f

    // Hologram scan lights
    private var scanLight1: LightNode? = null
    private var scanLight2: LightNode? = null
    private var scanLight3: LightNode? = null
    private var scanAnimator: ValueAnimator? = null
    private var isScanningActive = false

    // Animation
    private var currentAnimationIndex = 0
    private var elapsedTime = 0f
    private var currentScale = 1.6f
    private var pendingAnimationIndex: Int? = null
    private val talkAnimationSequence  = listOf(7, 7)
    private val talkAnimationSequence2 = listOf(9, 9)
    private val talkAnimationLong      = listOf(8, 8)
    private var activeSequence = talkAnimationSequence
    private var talkAnimationPos = 0
    private var isTalkAnimating = false
    private var isTalkPaused = false
    private var pendingStopTalk = false
    private var pendingPauseTalk = false

    // Talk sway rotation
    private var talkSwayAnimator: ValueAnimator? = null
    //End model 3d
    

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

        setupRecyclerView()
        setupButtons()
        startBotStateObserver()

        if (hasStoragePermission()) {
            setupOrchestrator()
            initOrchestrator()
        } else {
            requestStoragePermission()
        }

        // Setup 3D SceneView
        setupSceneView()

        // Load 3D Model
        loadModel(MODEL_PATH)

        // Start hologram scan + auto rotation after a delay for model to load
        binding.sceneView.postDelayed({
            setupHologramScanLights()
            startHologramScan()
        }, 1000)
    }

    // ===========================
    // 3D Model / SceneView Methods
    // ===========================

    /**
     * Configure SceneView with transparent background + hologram effects
     */
    private fun setupSceneView() {
        try {
            // Set transparent background
            binding.sceneView.setZOrderOnTop(true)
            binding.sceneView.holder.setFormat(PixelFormat.TRANSLUCENT)

            // Remove skybox for transparency
            binding.sceneView.scene.skybox = null

            binding.sceneView.post {
                try {
                    binding.sceneView.renderer?.clearOptions = Renderer.ClearOptions().apply {
                        clearColor = floatArrayOf(0f, 0f, 0f, 0f)
                        clear = true
                    }

                    binding.sceneView.view.blendMode = com.google.android.filament.View.BlendMode.TRANSLUCENT

                    // Enable bloom effect for hologram glow
                    binding.sceneView.view.bloomOptions = com.google.android.filament.View.BloomOptions().apply {
                        enabled = true
                        strength = 1.5f
                        levels = 6
                        blendMode = com.google.android.filament.View.BloomOptions.BlendMode.ADD
                        threshold = true
                        highlight = 1000f
                    }

                    // Anti-aliasing
                    binding.sceneView.view.antiAliasing = com.google.android.filament.View.AntiAliasing.FXAA

                    Log.i(TAG, "✅ SceneView post-configuration done")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ SceneView post-config error", e)
                }
            }

            Log.i(TAG, "✅ SceneView configured with transparent background")
        } catch (e: Exception) {
            Log.e(TAG, "❌ setupSceneView error", e)
        }
    }

    /**
     * Load 3D model from assets
     */
    private fun loadModel(assetPath: String) {
        Log.i(TAG, "🔄 Loading 3D model: $assetPath")

        lifecycleScope.launch {
            try {
                val modelInstance = binding.sceneView.modelLoader.createModelInstance(assetPath)

                if (modelInstance == null) {
                    Log.e(TAG, "❌ createModelInstance returned null for: $assetPath")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SecondActivity, "Model returned null: $assetPath", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                Log.i(TAG, "✅ ModelInstance created successfully")

                withContext(Dispatchers.Main) {
                    // Create parent node at center as rotation pivot
                    parentNode = Node(engine = binding.sceneView.engine).apply {
                        position = Position(x = 0f, y = -0.8f, z = -1.5f)
                        rotation = Rotation(currentRotationX, currentRotationY, currentRotationZ)
                        binding.sceneView.addChildNode(this)
                    }

                    Log.i(TAG, "✅ Parent node created at position (0, -0.5, -1.5)")

                    // Create ModelNode as child of parent
                    modelNode = ModelNode(
                        modelInstance = modelInstance,
                        scaleToUnits = 1.0f
                    ).apply {
                        position = Position(x = 0f, y = 0f, z = 0f)
                        rotation = Rotation(0f, 0f, 0f)
                        scale = io.github.sceneview.math.Scale(currentScale)
                        isShadowCaster = true
                        isShadowReceiver = true
                        parentNode?.addChildNode(this)
                    }

                    Log.i(TAG, "✅ ModelNode created with scale=$currentScale, added to parent")
                    Log.i(TAG, "✅ SceneView childNodes count: ${binding.sceneView.childNodes.size}")

                    // Setup animations
                    modelInstance.animator?.let { animator ->
                        Log.i(TAG, "📽️ Animator found, animationCount=${animator.animationCount}")
                        if (animator.animationCount > 0) {
                            for (i in 0 until animator.animationCount) {
                                val name = animator.getAnimationName(i)
                                val duration = animator.getAnimationDuration(i)
                                Log.i(TAG, "  Animation $i: '$name' (${duration}s)")
                            }
                            playAnimation(0)
                        } else {
                            Log.i(TAG, "ℹ️ Model has no animations")
                        }
                    } ?: Log.w(TAG, "⚠️ Model animator is null")

                    // Setup animation loop
                    setupAnimationLoop()
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading 3D model: $assetPath", e)
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SecondActivity, "Error loading 3D model: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Play animation by index
     */
    private fun playAnimation(index: Int) {
        modelNode?.modelInstance?.animator?.let { animator ->
            if (index in 0 until animator.animationCount) {
                elapsedTime = 0f
                animator.apply {
                    applyAnimation(index, 0f)
                    updateBoneMatrices()
                }
                currentAnimationIndex = index
                Log.i(TAG, "🎬 Playing animation index: $index")
            }
        }
    }

    /**
     * Setup animation loop for continuous playback
     */
    private fun setupAnimationLoop() {
        var lastFrameTime = System.nanoTime()

        binding.sceneView.onFrame = { _ ->
            modelNode?.modelInstance?.animator?.let { animator ->
                if (animator.animationCount > 0 && currentAnimationIndex < animator.animationCount) {
                    val currentTime = System.nanoTime()
                    val deltaTime = (currentTime - lastFrameTime) / 1_000_000_000f
                    lastFrameTime = currentTime

                    elapsedTime += deltaTime

                    val duration = animator.getAnimationDuration(currentAnimationIndex)
                    if (elapsedTime >= duration) {
                        elapsedTime = 0f
                        onAnimationCycleComplete()
                    }

                    animator.apply {
                        applyAnimation(currentAnimationIndex, elapsedTime)
                        updateBoneMatrices()
                    }
                }
            }
        }
        Log.i(TAG, "🔄 Animation loop setup for continuous playback")
    }

    private fun onAnimationCycleComplete() {
        if (pendingStopTalk) {
            pendingStopTalk = false
            pendingPauseTalk = false
            isTalkAnimating = false
            isTalkPaused = false
            currentAnimationIndex = 0
            elapsedTime = 0f
            Log.i(TAG, "🔇 stopTalkAnimation applied after cycle complete → animation 0")
            return
        }
        if (pendingPauseTalk) {
            pendingPauseTalk = false
            isTalkPaused = true
            elapsedTime = 0f
            Log.i(TAG, "⏸️ pauseTalkAnimation applied after cycle complete → holding animation $currentAnimationIndex")
            return
        }
        if (isTalkAnimating && !isTalkPaused) {
            talkAnimationPos = (talkAnimationPos + 1) % activeSequence.size
            currentAnimationIndex = activeSequence[talkAnimationPos]
            elapsedTime = 0f
            return
        }
        pendingAnimationIndex?.let { next ->
            pendingAnimationIndex = null
            currentAnimationIndex = next
        }
    }

    /**
     * Setup 3 hologram scan lights with trailing effect
     */
    private fun setupHologramScanLights() {
        binding.sceneView.post {
            // Main scan light (100% intensity)
            scanLight1 = LightNode(
                engine = binding.sceneView.engine,
                type = LightManager.Type.POINT
            ) {
                color(0f, 1f, 1f) // Cyan
                intensity(300000f)
                falloff(3f)
                castShadows(false)
            }
            scanLight1?.position = Position(-2f, 0f, -2f)
            binding.sceneView.addChildNode(scanLight1!!)

            // Trail light 1 (60% intensity)
            scanLight2 = LightNode(
                engine = binding.sceneView.engine,
                type = LightManager.Type.POINT
            ) {
                color(0f, 1f, 1f)
                intensity(180000f)
                falloff(2.5f)
                castShadows(false)
            }
            scanLight2?.position = Position(-2.3f, 0f, -2f)
            binding.sceneView.addChildNode(scanLight2!!)

            // Trail light 2 (30% intensity)
            scanLight3 = LightNode(
                engine = binding.sceneView.engine,
                type = LightManager.Type.POINT
            ) {
                color(0f, 1f, 1f)
                intensity(90000f)
                falloff(2f)
                castShadows(false)
            }
            scanLight3?.position = Position(-2.6f, 0f, -2f)
            binding.sceneView.addChildNode(scanLight3!!)

            Log.i(TAG, "✨ Hologram scan lights created with trailing effect")
        }
    }

    /**
     * Start hologram scan animation
     */
    private fun startHologramScan() {
        if (isScanningActive) return

        isScanningActive = true

        val startX = -2f
        val endX = 2f
        val trailOffset1 = 0.3f
        val trailOffset2 = 0.6f

        scanAnimator = ValueAnimator.ofFloat(startX, endX).apply {
            duration = 5000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART

            addUpdateListener { animator ->
                val currentX = animator.animatedValue as Float
                scanLight1?.position = Position(currentX, 0f, -2f)
                scanLight2?.position = Position(currentX - trailOffset1, 0f, -2f)
                scanLight3?.position = Position(currentX - trailOffset2, 0f, -2f)
            }

            start()
        }

        Log.i(TAG, "✨ Hologram scan animation started")
    }

    /**
     * Stop hologram scan animation
     */
    private fun stopHologramScan() {
        try {
            if (!isScanningActive) return

            isScanningActive = false
            scanAnimator?.cancel()
            scanAnimator = null

            scanLight1?.let { binding.sceneView.removeChildNode(it) }
            scanLight2?.let { binding.sceneView.removeChildNode(it) }
            scanLight3?.let { binding.sceneView.removeChildNode(it) }

            scanLight1 = null
            scanLight2 = null
            scanLight3 = null

            Log.i(TAG, "🛑 Hologram scan animation stopped")
        }catch (e: Exception){
            e.printStackTrace()
        }

    }

    private fun talkAnimation(isLong: Boolean = false) {
        pendingStopTalk = false
        isTalkAnimating = true
        talkAnimationPos = 0
        activeSequence = if (isLong) talkAnimationLong
                         else listOf(talkAnimationSequence, talkAnimationSequence2).random()
        currentAnimationIndex = activeSequence[0]
        elapsedTime = 0f
        startTalkSway()
        Log.i(TAG, "🗣️ talkAnimation started → animation ${activeSequence[0]} (isLong=$isLong)")
    }

    private fun stopTalkAnimation() {
        pendingStopTalk = true
        pendingPauseTalk = false
        isTalkPaused = false
        stopTalkSway()
        Log.i(TAG, "🔇 stopTalkAnimation → pending until current cycle complete")
    }

    private fun pauseTalkAnimation() {
        if (!isTalkAnimating || isTalkPaused) return
        pendingPauseTalk = true
        talkSwayAnimator?.pause()
        Log.i(TAG, "⏸️ pauseTalkAnimation")
    }

    private fun resumeTalkAnimation(isLong: Boolean = false) {
        if (!isTalkAnimating) {
            talkAnimation(isLong)
            return
        }
        activeSequence = if (isLong) talkAnimationLong
                         else listOf(talkAnimationSequence, talkAnimationSequence2).random()
        isTalkPaused = false
        pendingPauseTalk = false
        pendingStopTalk = false
        talkSwayAnimator?.resume()
        Log.i(TAG, "▶️ resumeTalkAnimation (isLong=$isLong)")
    }

    private fun startTalkSway() {
        talkSwayAnimator?.cancel()
        talkSwayAnimator = ValueAnimator.ofFloat(-12f, 12f).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                currentRotationY = animator.animatedValue as Float
                parentNode?.rotation = Rotation(currentRotationX, currentRotationY, currentRotationZ)
            }
            start()
        }
    }

    private fun stopTalkSway() {
        talkSwayAnimator?.cancel()
        talkSwayAnimator = null
        ValueAnimator.ofFloat(currentRotationY, 0f).apply {
            duration = 400
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { animator ->
                currentRotationY = animator.animatedValue as Float
                parentNode?.rotation = Rotation(currentRotationX, currentRotationY, currentRotationZ)
            }
            start()
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
        releaseOrchestrator()
        stopHologramScan()
        talkSwayAnimator?.cancel()
        talkSwayAnimator = null
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

        binding.btnToggleMic.isEnabled  = false
        binding.btnClear.isEnabled      = false
        binding.btnSettings.isEnabled   = false
        binding.tvStatus.text           = "⏳ Đang giải phóng tài nguyên..."

        lifecycleScope.launch {
            releaseOrchestrator()
            startActivity(
                Intent(this@SecondActivity, SetupActivity::class.java).apply {
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
            layoutManager = LinearLayoutManager(this@SecondActivity).apply { stackFromEnd = true }
            adapter = chatAdapter
        }
    }

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

        orchestrator.onLog = { msg, _ ->
            runOnUiThread {
                when {
                    msg.startsWith("User:") -> binding.tvUserTalk.text = msg.removePrefix("User:").trim()
                    msg.startsWith("Bot:")  -> binding.tvBotTalk.text  = msg.removePrefix("Bot:").trim()
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
        orchestrator.onBotBusyChanged = { busy ->
            runOnUiThread {
                updateStatusUI()
            }
        }

        orchestrator.onTtsStartWithLength = { isLong ->
            runOnUiThread { resumeTalkAnimation(isLong) }
        }
        orchestrator.ttsEngine.onSpeechDone = {
            runOnUiThread { pauseTalkAnimation() }
        }
        orchestrator.onAllSpeechComplete = {
            runOnUiThread {
                stopTalkAnimation()
                binding.tvBotTalk.text        = ""
                binding.tvUserTalk.text        = ""
            }
        }

    }
//        orchestrator.onTtsStop  = {playAnimation(0)}
//    }

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
                Toast.makeText(this@SecondActivity, "Lỗi khởi tạo: ${e.message}", Toast.LENGTH_LONG).show()
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
            binding.tvUserTalk.text = ""
            binding.tvBotTalk.text  = ""
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
