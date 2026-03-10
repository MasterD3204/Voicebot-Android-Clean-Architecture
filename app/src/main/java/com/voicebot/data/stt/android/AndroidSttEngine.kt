package com.voicebot.data.stt.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.voicebot.domain.port.SttEngine

/**
 * STT via Android's built-in SpeechRecognizer SDK.
 * Hoàn toàn dựa vào SDK để detect speech & silence.
 */
class AndroidSttEngine(
    private val context: Context,
    private val language: String = "vi-VN",
    private val silenceAfterSpeechMs: Long = 300L,
    private val silenceMaybeDoneMs: Long = 200L
) : SttEngine {

    companion object {
        private const val TAG = "AndroidSttEngine"
    }

    override var onResult: ((String) -> Unit)? = null
    override var onError: ((Int) -> Unit)? = null
    override var onPartialResult: ((String) -> Unit)? = null
    override var onListeningStarted: (() -> Unit)? = null
    override var onListeningStopped: (() -> Unit)? = null

    private val recognizer: SpeechRecognizer by lazy {
        SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {
                    onListeningStarted?.invoke()
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rms: Float) {
                    // Để SDK tự xử lý
                }

                override fun onBufferReceived(buf: ByteArray?) {}

                override fun onEndOfSpeech() {
                    onListeningStopped?.invoke()
                }

                override fun onEvent(type: Int, p: Bundle?) {}

                override fun onPartialResults(partial: Bundle?) {
                    val text = partial
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()

                    if (!text.isNullOrBlank()) {
                        Log.d(TAG, "🔄 Partial: $text")
                        onPartialResult?.invoke(text)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()

                    if (!text.isNullOrBlank()) {
                        Log.i(TAG, "✅ Final: $text")
                        onResult?.invoke(text)
                    } else {
                        Log.w(TAG, "⚠️ Kết quả rỗng, bỏ qua")
                    }

                    onListeningStopped?.invoke()
                }

                override fun onError(error: Int) {
                    Log.w(TAG, "Error code: $error")
                    onError?.invoke(error)
                    onListeningStopped?.invoke()
                }
            })
        }
    }

    private val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

        // ⏱️ Silence timing - SDK tự detect dựa trên các ngưỡng này
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
        try {
            recognizer.startListening(recognizerIntent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
        }
    }

    override fun stopListening() {
        recognizer.stopListening()
    }

    override fun destroy() {
        recognizer.destroy()
    }
}