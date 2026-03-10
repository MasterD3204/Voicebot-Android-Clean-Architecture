package com.voicebot.domain.port

/**
 * Port for text normalization before TTS synthesis.
 * Implementations: NumberTextNormalizer, ProductTextNormalizer, CompositeTextNormalizer
 */
interface TextNormalizer {
    fun normalize(text: String): String
}
