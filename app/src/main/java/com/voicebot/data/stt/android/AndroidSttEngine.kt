package com.voicebot.data.stt.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.voicebot.domain.port.SttEngine

class AndroidSttEngine(
    private val context: Context,
    private val language: String = "vi-VN",
    private val silenceAfterSpeechMs: Long = 1500L,
    private val silenceMaybeDoneMs: Long = 1000L
) : SttEngine {

    companion object {
        private const val TAG = "AndroidSttEngine"
    }

    override var onResult: ((String) -> Unit)? = null
    override var onError: ((Int) -> Unit)? = null
    override var onPartialResult: ((String) -> Unit)? = null
    override var onListeningStarted: (() -> Unit)? = null
    override var onListeningStopped: (() -> Unit)? = null

    @Volatile private var isCurrentlyListening = false
    @Volatile private var lastStartTime = 0L
    // Đếm NO_MATCH liên tiếp để phát hiện vòng lặp vô tận
    @Volatile private var consecutiveNoMatch = 0

    fun isListening(): Boolean = isCurrentlyListening

    private val recognizer: SpeechRecognizer by lazy {
        val isAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        val isOnDeviceAvailable = try {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        } catch (e: Throwable) {
            Log.w(TAG, "isOnDeviceRecognitionAvailable() not supported: ${e.message}")
            false
        }
        Log.i(TAG, "🔍 isRecognitionAvailable=$isAvailable, isOnDeviceAvailable=$isOnDeviceAvailable")

        // ✅ On-device chỉ hỗ trợ tiếng Anh — dùng cloud-based cho vi-VN
        val useOnDevice = isOnDeviceAvailable && language.startsWith("en")
        val sr = if (useOnDevice) {
            try {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also {
                    Log.i(TAG, "✅ On-device STT OK")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "❌ On-device failed, fallback to cloud", e)
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        } else {
            Log.i(TAG, "☁️ Cloud-based STT (language=$language)")
            SpeechRecognizer.createSpeechRecognizer(context)
        }

        sr.also { rec ->
            rec.setRecognitionListener(object : RecognitionListener {

                override fun onReadyForSpeech(p: Bundle?) {
                    Log.i(TAG, "🟢 onReadyForSpeech")
                    isCurrentlyListening = true
                    consecutiveNoMatch = 0
                    onListeningStarted?.invoke()
                }

                override fun onBeginningOfSpeech() {
                    Log.i(TAG, "🗣️ onBeginningOfSpeech")
                }

                override fun onRmsChanged(rms: Float) {}
                override fun onBufferReceived(buf: ByteArray?) {}
                override fun onEvent(type: Int, p: Bundle?) {}

                override fun onEndOfSpeech() {
                    val duration = if (lastStartTime > 0) System.currentTimeMillis() - lastStartTime else -1
                    Log.i(TAG, "🔴 onEndOfSpeech (duration=${duration}ms)")
                    isCurrentlyListening = false
                    onListeningStopped?.invoke()
                }

                override fun onPartialResults(partial: Bundle?) {
                    val text = partial
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        Log.i(TAG, "🔄 Partial: \"$text\"")
                        onPartialResult?.invoke(text)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()

                    Log.i(TAG, "✅ onResults: \"$text\"")
                    isCurrentlyListening = false
                    consecutiveNoMatch = 0

                    if (!text.isNullOrBlank()) {
                        onResult?.invoke(text)
                    } else {
                        Log.w(TAG, "⚠️ Kết quả rỗng")
                    }
                    onListeningStopped?.invoke()
                }

                override fun onError(error: Int) {
                    val errorName = when (error) {
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT        -> "NETWORK_TIMEOUT"
                        SpeechRecognizer.ERROR_NETWORK                -> "NETWORK"
                        SpeechRecognizer.ERROR_AUDIO                  -> "AUDIO"
                        SpeechRecognizer.ERROR_SERVER                 -> "SERVER"
                        SpeechRecognizer.ERROR_CLIENT                 -> "CLIENT"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT         -> "SPEECH_TIMEOUT"
                        SpeechRecognizer.ERROR_NO_MATCH               -> "NO_MATCH"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY        -> "RECOGNIZER_BUSY"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
                        else -> "UNKNOWN($error)"
                    }
                    Log.e(TAG, "❌ onError: $errorName (code=$error)")
                    isCurrentlyListening = false

                    when (error) {
                        // NO_MATCH: không nhận ra giọng nói — bỏ qua, callback tiếp tục lắng nghe
                        SpeechRecognizer.ERROR_NO_MATCH -> {
                            consecutiveNoMatch++
                            Log.w(TAG, "⚠️ NO_MATCH #$consecutiveNoMatch — bỏ qua, tiếp tục lắng nghe")
                            // Không gọi onError callback → MainActivity sẽ restart STT bình thường
                            onListeningStopped?.invoke()
                        }
                        // SPEECH_TIMEOUT: không nghe thấy tiếng nào — bỏ qua tương tự
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            Log.w(TAG, "⚠️ SPEECH_TIMEOUT — bỏ qua")
                            onListeningStopped?.invoke()
                        }
                        // Lỗi thật: báo lên trên
                        else -> {
                            consecutiveNoMatch = 0
                            onError?.invoke(error)
                            onListeningStopped?.invoke()
                        }
                    }
                }
            })
        }
    }

    private fun buildRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                silenceAfterSpeechMs
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                silenceMaybeDoneMs
            )
        }

    override fun startListening() {
        if (isCurrentlyListening) {
            Log.w(TAG, "⚠️ startListening() SKIPPED — already listening")
            return
        }
        lastStartTime = System.currentTimeMillis()
        Log.i(TAG, "🎤 startListening()")
        try {
            recognizer.startListening(buildRecognizerIntent())
        } catch (e: Exception) {
            Log.e(TAG, "❌ startListening FAILED", e)
        }
    }

    override fun stopListening() {
        Log.i(TAG, "🛑 stopListening()")
        isCurrentlyListening = false
        try { recognizer.stopListening() }
        catch (e: Exception) { Log.w(TAG, "stopListening error", e) }
    }

    override fun destroy() {
        Log.i(TAG, "💀 destroy()")
        isCurrentlyListening = false
        recognizer.destroy()
    }
}