package com.voicebot.data.tts.piper

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
// ✅ Import từ file SherpaOnnxTts.kt (package com.k2fsa.sherpa.onnx)
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.voicebot.domain.port.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.io.FileOutputStream
import java.io.IOException
class PiperTtsEngine(
    private val context: Context,
    private val modelDir: String   = "vits-piper-vi-huongly",
    private val modelName: String  = "huongly.onnx",
    private val tokensName: String = "tokens.txt",
    private val espDataDir: String = "vits-piper-vi-huongly/espeak-ng-data",
    private val speakerId: Int     = 0,
    private val speed: Float       = 0.7f,
    private val numThreads: Int    = 2
) : TtsEngine {

    companion object {
        private const val TAG = "PiperTtsEngine"
    }

    override var onSpeechStart: (() -> Unit)? = null
    override var onSpeechDone: (() -> Unit)? = null
    override var onAllSpeechDone: (() -> Unit)? = null

    private var tts: OfflineTts? = null

    @Volatile
    private var audioTrack: AudioTrack? = null

    private val isSpeakingFlag = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<Pair<String, String>>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var worker: Job? = null
    private val playLock = Any()

    // ── Queue completion tracking (mirrors AndroidTtsEngine pattern) ──────
    private val lock = Any()
    private val queuedCount = AtomicInteger(0)
    private val doneCount   = AtomicInteger(0)
    private var queueMarkedComplete = false

    // ── Init ──────────────────────────────────────────────────

    fun init(): Boolean {
        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "  PiperTtsEngine.init() BẮT ĐẦU")
        Log.i(TAG, "  modelDir   = $modelDir")
        Log.i(TAG, "  modelName  = $modelName")
        Log.i(TAG, "  espDataDir = $espDataDir")
        Log.i(TAG, "═══════════════════════════════════════")

        return try {
            // BƯỚC 1: Copy espeak-ng-data từ assets ra external storage
            val externalDataDir = copyDataDir(context, espDataDir)
            Log.i(TAG, "espeak-ng-data path: $externalDataDir")

            // BƯỚC 2: Build config
            val vitsConfig = OfflineTtsVitsModelConfig(
                model   = "$modelDir/$modelName",       // → "vits-piper-vi-huongly/huongly.onnx"
                tokens  = "$modelDir/$tokensName",      // → "vits-piper-vi-huongly/tokens.txt"
                dataDir = externalDataDir               // → "/storage/.../vits-piper-vi-huongly/espeak-ng-data"
            )

            val modelConfig = OfflineTtsModelConfig(
                vits       = vitsConfig,
                numThreads = numThreads,
                provider   = "cpu",
                debug      = true
            )

            val config = OfflineTtsConfig(model = modelConfig)

            // BƯỚC 3: Tạo OfflineTts VỚI assetManager
            Log.i(TAG, "⚡ Config:")
            Log.i(TAG, "   model   = ${vitsConfig.model}")
            Log.i(TAG, "   tokens  = ${vitsConfig.tokens}")
            Log.i(TAG, "   dataDir = ${vitsConfig.dataDir}")
            Log.i(TAG, "⚡ Đang gọi OfflineTts(assetManager=...)...")

            tts = OfflineTts(
                assetManager = context.assets,
                config = config
            )
            Log.i(TAG, "✅ OfflineTts tạo thành công!")

            val sr = tts!!.sampleRate()
            Log.i(TAG, "   sampleRate = $sr Hz")
            if (sr <= 0) {
                Log.e(TAG, "❌ sampleRate không hợp lệ")
                return false
            }

            startWorker()
            Log.i(TAG, "  PiperTtsEngine READY ✅")
            true

        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "❌ UnsatisfiedLinkError", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception: ${e::class.qualifiedName}", e)
            false
        }
    }


    // ── Copy espeak-ng-data từ assets ra external storage ────

    private fun copyDataDir(context: Context, dataDir: String): String {
        Log.i(TAG, "Copying data dir: $dataDir")
        copyAssets(context, dataDir)
        val newDataDir = context.getExternalFilesDir(null)!!.absolutePath
        return "$newDataDir/$dataDir"
    }

    private fun copyAssets(context: Context, path: String) {
        try {
            val assetList = context.assets.list(path)
            if (assetList.isNullOrEmpty()) {
                // Đây là file, copy nó
                copyFile(context, path)
            } else {
                // Đây là thư mục, tạo và recurse
                val fullPath = "${context.getExternalFilesDir(null)}/$path"
                File(fullPath).mkdirs()
                for (asset in assetList) {
                    val subPath = if (path.isEmpty()) asset else "$path/$asset"
                    copyAssets(context, subPath)
                }
            }
        } catch (ex: IOException) {
            Log.e(TAG, "Failed to copy $path: $ex")
        }
    }

    private fun copyFile(context: Context, filename: String) {
        try {
            val newFilename = "${context.getExternalFilesDir(null)}/$filename"

            // Skip nếu file đã tồn tại (tránh copy lại mỗi lần init)
            val existingFile = File(newFilename)
            if (existingFile.exists() && existingFile.length() > 0) {
                return
            }

            context.assets.open(filename).use { input ->
                FileOutputStream(newFilename).use { output ->
                    val buffer = ByteArray(4096)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to copy $filename: $ex")
        }
    }

    // ── Phần còn lại giữ nguyên ─────────────────────────────
    // speak(), stop(), isSpeaking(), shutdown()
    // startWorker(), synthesizeAndPlay(), playAudio()
    // (giữ nguyên như code trước)

    override fun speak(text: String, utteranceId: String) {
        if (tts == null) {
            Log.w(TAG, "speak() trước khi init()")
            return
        }
        if (text.isBlank()) return
        Log.d(TAG, "▶ Enqueue [$utteranceId]: \"${text.take(50)}\"")
        queuedCount.incrementAndGet()
        queue.offer(text.trim() to utteranceId)
    }

    override fun stop() {
        queue.clear()
        isSpeakingFlag.set(false)  // Signal write loop to exit
        synchronized(playLock) {
            try {
                audioTrack?.stop()
            } catch (_: IllegalStateException) { }
            audioTrack?.release()
            audioTrack = null
        }
        resetCounters()
        onSpeechDone?.invoke()
    }

    override fun markQueueComplete() {
        val shouldFire = synchronized(lock) {
            queueMarkedComplete = true
            doneCount.get() >= queuedCount.get()
        }
        if (shouldFire) onAllSpeechDone?.invoke()
    }

    override fun isSpeaking(): Boolean = isSpeakingFlag.get()

    override fun shutdown() {
        stop()
        worker?.cancel()
        scope.cancel()
        tts?.free()
        tts = null
        Log.i(TAG, "Piper TTS shutdown")
    }

    private fun notifyDone() {
        onSpeechDone?.invoke()
        val shouldFire = synchronized(lock) {
            val done = doneCount.incrementAndGet()
            queueMarkedComplete && done >= queuedCount.get()
        }
        if (shouldFire) onAllSpeechDone?.invoke()
    }

    private fun resetCounters() {
        synchronized(lock) {
            queuedCount.set(0)
            doneCount.set(0)
            queueMarkedComplete = false
        }
    }

    private fun startWorker() {
        worker = scope.launch {
            while (isActive) {
                val item = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val (text, uttId) = item
                synthesizeAndPlay(text, uttId)
            }
        }
    }

    private fun synthesizeAndPlay(text: String, utteranceId: String) {
        val engine = tts ?: return
        try {
            Log.d(TAG, "🔊 Synthesizing [$utteranceId]: \"${text.take(80)}\"")
            val t0 = System.currentTimeMillis()
            val audio = engine.generate(text = text, sid = speakerId, speed = speed)
            val elapsed = System.currentTimeMillis() - t0
            Log.d(TAG, "  -> ${audio.samples.size} samples @ ${audio.sampleRate}Hz ( ${elapsed}ms)")

            if (audio.samples.isEmpty()) {
                Log.w(TAG, "⚠ Empty audio for $utteranceId")
                notifyDone()
                return
            }
            playAudio(audio.samples, audio.sampleRate, utteranceId)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Synthesis failed [$utteranceId]", e)
            isSpeakingFlag.set(false)
            notifyDone()
        }
    }

    private fun playAudio(samples: FloatArray, sampleRate: Int, utteranceId: String) {
        synchronized(playLock) {
            val shortSamples = ShortArray(samples.size) { i ->
                (samples[i].coerceIn(-1f, 1f) * 32767).toInt().toShort()
            }

            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (minBuf <= 0) {
                Log.e(TAG, "❌ getMinBufferSize=$minBuf")
                notifyDone()
                return
            }

            // ★ KEY FIX: Dùng minBuf (KHÔNG coerceAtLeast với data size)
            // Điều này đảm bảo write() sẽ BLOCK khi buffer đầy
            val bufferSize = minBuf

            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            } catch (e: Exception) {
                Log.e(TAG, "❌ AudioTrack.Builder failed", e)
                notifyDone()
                return
            }

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "❌ AudioTrack NOT initialized!")
                track.release()
                notifyDone()
                return
            }

            audioTrack?.let {
                try { it.stop() } catch (_: Exception) {}
                it.release()
            }
            audioTrack = track

            isSpeakingFlag.set(true)
            onSpeechStart?.invoke()

            Log.d(TAG, "▶ Playing [$utteranceId] sr=$sampleRate, samples=${shortSamples.size}, buf=$bufferSize")
            track.play()

            // ★ write() sẽ BLOCK vì buffer nhỏ hơn data → đợi audio phát
            val chunkSize = 2048
            var offset = 0
            while (offset < shortSamples.size && isSpeakingFlag.get()) {
                val end = minOf(offset + chunkSize, shortSamples.size)
                val written = track.write(shortSamples, offset, end - offset)
                if (written < 0) {
                    Log.e(TAG, "❌ write error: $written")
                    break
                }
                offset += written
            }

            // ★ ĐỢI buffer phát hết trước khi stop
            // Sau khi write xong, buffer vẫn còn data chưa phát
            val remainingMs = (minBuf.toLong() * 1000) / (sampleRate * 2)
            Thread.sleep(remainingMs + 50)

            try {
                if (isSpeakingFlag.get()) track.stop()
            } catch (_: IllegalStateException) { }
            track.release()
            if (audioTrack === track) audioTrack = null
            isSpeakingFlag.set(false)

            notifyDone()
            Log.d(TAG, "✅ Done utterance: $utteranceId")
        }
    }
}

