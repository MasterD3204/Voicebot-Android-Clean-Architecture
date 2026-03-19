package com.voicebot.presentation

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages all 3D avatar rendering: SceneView setup, model loading, hologram scan lights,
 * and talk/idle animations.
 *
 * Usage:
 * ```kotlin
 * // In Activity.onCreate():
 * val avatarController = AvatarController(this, binding.sceneView)
 * avatarController.setup()
 *
 * // Wire to orchestrator callbacks:
 * orchestrator.onTtsStartWithLength = { isLong -> avatarController.onTtsStart(isLong) }
 * orchestrator.ttsEngine.onSpeechDone = { avatarController.onTtsDone() }
 * orchestrator.onAllSpeechComplete = { avatarController.onAllSpeechDone() }
 *
 * // In Activity.onDestroy():
 * avatarController.destroy()
 * ```
 *
 * To disable the 3D avatar, simply don't create an AvatarController instance.
 */
class AvatarController(
    private val activity: AppCompatActivity,
    private val sceneView: SceneView,
    private val modelPath: String = DEFAULT_MODEL_PATH
) {
    companion object {
        private const val TAG = "AvatarController"
        const val DEFAULT_MODEL_PATH = "model/model_demo.glb"
    }

    // ── 3D Scene nodes ────────────────────────────────────────────────────
    private var modelNode: ModelNode? = null
    private var parentNode: Node? = null

    // ── Hologram scan lights ──────────────────────────────────────────────
    private var scanLight1: LightNode? = null
    private var scanLight2: LightNode? = null
    private var scanLight3: LightNode? = null
    private var scanAnimator: ValueAnimator? = null
    private var isScanningActive = false

    // ── Talk animation state ──────────────────────────────────────────────
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

    // ── Rotation state ────────────────────────────────────────────────────
    private var talkSwayAnimator: ValueAnimator? = null
    private var currentRotationX = 0f
    private var currentRotationY = 0f
    private var currentRotationZ = 0f
    private var touchLastX = 0f
    private var isTouching = false
    private val TOUCH_ROTATION_SENSITIVITY = 0.4f

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Initialises SceneView rendering options, sets up touch rotation,
     * loads the 3D model, and schedules hologram lights.
     * Call once from Activity.onCreate() after setContentView().
     */
    fun setup() {
        setupSceneView()
        loadModel(modelPath)
        // Start hologram scan after a delay to let the model load
        sceneView.postDelayed({
            setupHologramScanLights()
            startHologramScan()
        }, 1000)
    }

    /**
     * Triggers talk animation when TTS starts playing.
     * @param isLong true if the utterance is long (≥8 words)
     */
    fun onTtsStart(isLong: Boolean) {
        activity.runOnUiThread { resumeTalkAnimation(isLong) }
    }

    /**
     * Pauses talk animation when a single TTS utterance finishes.
     */
    fun onTtsDone() {
        activity.runOnUiThread { pauseTalkAnimation() }
    }

    /**
     * Stops talk animation when the entire speech queue is done.
     */
    fun onAllSpeechDone() {
        activity.runOnUiThread { stopTalkAnimation() }
    }

    /**
     * Releases all animation resources. Call from Activity.onDestroy().
     */
    fun destroy() {
        stopHologramScan()
        talkSwayAnimator?.cancel()
        talkSwayAnimator = null
    }

    // ── SceneView setup ───────────────────────────────────────────────────

    private fun setupSceneView() {
        try {
            sceneView.setZOrderOnTop(true)
            sceneView.holder.setFormat(PixelFormat.TRANSLUCENT)
            sceneView.scene.skybox = null

            sceneView.post {
                try {
                    sceneView.renderer?.clearOptions = Renderer.ClearOptions().apply {
                        clearColor = floatArrayOf(0f, 0f, 0f, 0f)
                        clear = true
                    }
                    sceneView.view.blendMode = com.google.android.filament.View.BlendMode.TRANSLUCENT
                    sceneView.view.bloomOptions = com.google.android.filament.View.BloomOptions().apply {
                        enabled = true
                        strength = 1.5f
                        levels = 6
                        blendMode = com.google.android.filament.View.BloomOptions.BlendMode.ADD
                        threshold = true
                        highlight = 1000f
                    }
                    sceneView.view.antiAliasing = com.google.android.filament.View.AntiAliasing.FXAA
                    Log.i(TAG, "✅ SceneView post-configuration done")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ SceneView post-config error", e)
                }
            }
            Log.i(TAG, "✅ SceneView configured with transparent background")
        } catch (e: Exception) {
            Log.e(TAG, "❌ setupSceneView error", e)
        }
        setupModelTouchRotation()
    }

    private fun setupModelTouchRotation() {
        sceneView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchLastX = event.x
                    isTouching = true
                    talkSwayAnimator?.pause()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isTouching) {
                        val dx = event.x - touchLastX
                        touchLastX = event.x
                        currentRotationY += dx * TOUCH_ROTATION_SENSITIVITY
                        parentNode?.rotation = Rotation(currentRotationX, currentRotationY, currentRotationZ)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isTouching = false
                    if (isTalkAnimating && !isTalkPaused) talkSwayAnimator?.resume()
                    true
                }
                else -> false
            }
        }
    }

    // ── Model loading ─────────────────────────────────────────────────────

    private fun loadModel(assetPath: String) {
        Log.i(TAG, "🔄 Loading 3D model: $assetPath")
        activity.lifecycleScope.launch {
            try {
                val modelInstance = sceneView.modelLoader.createModelInstance(assetPath)
                if (modelInstance == null) {
                    Log.e(TAG, "❌ createModelInstance returned null for: $assetPath")
                    return@launch
                }
                Log.i(TAG, "✅ ModelInstance created successfully")

                withContext(Dispatchers.Main) {
                    parentNode = Node(engine = sceneView.engine).apply {
                        position = Position(x = 0f, y = -0.8f, z = -1.5f)
                        rotation = Rotation(currentRotationX, currentRotationY, currentRotationZ)
                        sceneView.addChildNode(this)
                    }
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
                    Log.i(TAG, "✅ ModelNode created with scale=$currentScale")

                    modelInstance.animator?.let { animator ->
                        Log.i(TAG, "📽️ Animator found, animationCount=${animator.animationCount}")
                        if (animator.animationCount > 0) {
                            for (i in 0 until animator.animationCount) {
                                Log.i(TAG, "  Animation $i: '${animator.getAnimationName(i)}' (${animator.getAnimationDuration(i)}s)")
                            }
                            playAnimation(0)
                        }
                    }
                    setupAnimationLoop()
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading 3D model: $assetPath", e)
            }
        }
    }

    // ── Animation ─────────────────────────────────────────────────────────

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

    private fun setupAnimationLoop() {
        var lastFrameTime = System.nanoTime()
        sceneView.onFrame = { _ ->
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
            Log.i(TAG, "🔇 stopTalkAnimation applied → animation 0")
            return
        }
        if (pendingPauseTalk) {
            pendingPauseTalk = false
            isTalkPaused = true
            elapsedTime = 0f
            Log.i(TAG, "⏸️ pauseTalkAnimation applied → holding animation $currentAnimationIndex")
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

    // ── Hologram lights ───────────────────────────────────────────────────

    private fun setupHologramScanLights() {
        sceneView.post {
            scanLight1 = LightNode(engine = sceneView.engine, type = LightManager.Type.POINT) {
                color(0f, 1f, 1f)
                intensity(300000f)
                falloff(3f)
                castShadows(false)
            }
            scanLight1?.position = Position(-2f, 0f, -2f)
            sceneView.addChildNode(scanLight1!!)

            scanLight2 = LightNode(engine = sceneView.engine, type = LightManager.Type.POINT) {
                color(0f, 1f, 1f)
                intensity(180000f)
                falloff(2.5f)
                castShadows(false)
            }
            scanLight2?.position = Position(-2.3f, 0f, -2f)
            sceneView.addChildNode(scanLight2!!)

            scanLight3 = LightNode(engine = sceneView.engine, type = LightManager.Type.POINT) {
                color(0f, 1f, 1f)
                intensity(90000f)
                falloff(2f)
                castShadows(false)
            }
            scanLight3?.position = Position(-2.6f, 0f, -2f)
            sceneView.addChildNode(scanLight3!!)

            Log.i(TAG, "✨ Hologram scan lights created with trailing effect")
        }
    }

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

    private fun stopHologramScan() {
        try {
            if (!isScanningActive) return
            isScanningActive = false
            scanAnimator?.cancel()
            scanAnimator = null
            scanLight1?.let { sceneView.removeChildNode(it) }
            scanLight2?.let { sceneView.removeChildNode(it) }
            scanLight3?.let { sceneView.removeChildNode(it) }
            scanLight1 = null
            scanLight2 = null
            scanLight3 = null
            Log.i(TAG, "🛑 Hologram scan animation stopped")
        } catch (e: Exception) {
            Log.e(TAG, "stopHologramScan error", e)
        }
    }
}
