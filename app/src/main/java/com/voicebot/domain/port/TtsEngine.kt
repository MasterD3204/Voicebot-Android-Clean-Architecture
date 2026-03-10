package com.voicebot.domain.port

/**
 * Port for Text-to-Speech engines.
 * Implementations: AndroidTtsEngine
 */
interface TtsEngine {
    /** Fires when a new utterance starts playing */
    var onSpeechStart: (() -> Unit)?
    /** Fires when an utterance finishes (or errors) */
    var onSpeechDone: (() -> Unit)?

    /**
     * Enqueue [text] for synthesis. [utteranceId] is an opaque tag
     * returned in [onSpeechStart]/[onSpeechDone] callbacks.
     */
    fun speak(text: String, utteranceId: String)

    /** Immediately stop all queued and current speech */
    fun stop()

    /** True while audio is playing */
    fun isSpeaking(): Boolean

    /** Permanently release TTS resources */
    fun shutdown()
}
