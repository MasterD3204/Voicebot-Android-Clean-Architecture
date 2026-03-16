package com.voicebot.data.tts.android

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.voicebot.domain.port.TtsEngine
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class AndroidTtsEngine(
    context: Context,
    private val language: String = "vi-VN"
) : TtsEngine {

    companion object { private const val TAG = "AndroidTtsEngine" }

    override var onSpeechStart: (() -> Unit)? = null
    override var onSpeechDone: (() -> Unit)? = null
    override var onAllSpeechDone: (() -> Unit)? = null

    private var tts: TextToSpeech? = null
    private var initialized = false

    private val lock = Any()
    private val queuedCount = AtomicInteger(0)
    private val doneCount   = AtomicInteger(0)
    private var queueMarkedComplete = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS init failed: $status"); return@TextToSpeech
            }
            val parts = language.split("-")
            val locale = if (parts.size >= 2) Locale(parts[0], parts[1]) else Locale(parts[0])
            val result = tts?.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language '$language' not supported on this device")
                return@TextToSpeech
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { onSpeechStart?.invoke() }
                override fun onDone(id: String?) {
                    onSpeechDone?.invoke()
                    notifyDone()
                }
                @Deprecated("Deprecated in API 21")
                override fun onError(id: String?) {
                    onSpeechDone?.invoke()
                    notifyDone()
                }
            })
            initialized = true
            Log.i(TAG, "Android TTS initialized for '$language'")
        }
    }

    private fun notifyDone() {
        val shouldFire = synchronized(lock) {
            val done = doneCount.incrementAndGet()
            queueMarkedComplete && done >= queuedCount.get()
        }
        if (shouldFire) onAllSpeechDone?.invoke()
    }

    override fun speak(text: String, utteranceId: String) {
        if (!initialized) { Log.w(TAG, "speak() called before init"); return }
        queuedCount.incrementAndGet()
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    override fun markQueueComplete() {
        val shouldFire = synchronized(lock) {
            queueMarkedComplete = true
            doneCount.get() >= queuedCount.get()
        }
        if (shouldFire) onAllSpeechDone?.invoke()
    }

    override fun stop() {
        tts?.stop()
        resetCounters()
    }

    override fun isSpeaking() = tts?.isSpeaking ?: false

    override fun shutdown() {
        tts?.shutdown()
        tts = null
        initialized = false
        resetCounters()
    }

    private fun resetCounters() {
        synchronized(lock) {
            queuedCount.set(0)
            doneCount.set(0)
            queueMarkedComplete = false
        }
    }
}
