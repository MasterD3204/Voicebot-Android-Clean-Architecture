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
    /** Fires once when the entire queued batch is done (no more utterances pending) */
    var onAllSpeechDone: (() -> Unit)?
    /**
     * Fires after the first utterance is synthesized, reporting the pure synthesis
     * time in milliseconds (e.g. Piper's generate() duration). Null if not supported.
     */
    var onSynthesisTime: ((ms: Long) -> Unit)?

    /**
     * Enqueue [text] for synthesis. [utteranceId] is an opaque tag
     * returned in [onSpeechStart]/[onSpeechDone] callbacks.
     */
    fun speak(text: String, utteranceId: String)

    /**
     * Signal that no more utterances will be added to the current batch.
     * The engine uses this to know when onAllSpeechDone can safely fire.
     */
    fun markQueueComplete()

    /** Immediately stop all queued and current speech */
    fun stop()

    /** True while audio is playing */
    fun isSpeaking(): Boolean

    /** Permanently release TTS resources */
    fun shutdown()
}
