package com.voicebot.domain.port

/**
 * Port for Speech-to-Text engines.
 * Implementations: AndroidSttEngine, SherpaSttEngine
 */
interface SttEngine {
    /** Called when final transcript is available */
    var onResult: ((text: String) -> Unit)?
    var onPartialResult: ((String) -> Unit)?
    /** Called on recognition error (Android error code) */
    var onError: ((errorCode: Int) -> Unit)?
    /** Called when the microphone is open and ready */
    var onListeningStarted: (() -> Unit)?
    /** Called when recognition attempt ends (success or error) */
    var onListeningStopped: (() -> Unit)?

    fun startListening()
    fun stopListening()
    fun destroy()
}
