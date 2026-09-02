package org.yangtse.hearwrite.domain

/**
 * Text-to-speech boundary used by [DictationEngine]. Implementations are the
 * playback chain links (system `TextToSpeech`, later Youdao/custom providers)
 * and must be failure-degrading: [speak] returns `false` on any failure and
 * never throws into the caller (AGENTS.md "TTS priority chain").
 *
 * [lang] is an IETF tag the implementation may not support — treat an
 * unsupported locale as a failure, not a silent wrong-voice utterance.
 */
interface Speaker {
    /**
     * Speak [text] in [lang] (e.g. "en-US", "zh-CN"), suspending until the
     * utterance finishes or fails. Returns `false` when nothing was spoken
     * (init failure, missing language data, cancelled).
     */
    suspend fun speak(text: String, lang: String): Boolean

    /** Stop the current utterance and release audio immediately. Idempotent. */
    fun stop()
}

/** Playback defaults shared by the engine, settings and UI (AGENTS.md). */
const val DEFAULT_INTERVAL_SEC = 7.0
const val MIN_INTERVAL_SEC = 1.0
const val MAX_INTERVAL_SEC = 10.0
const val INTERVAL_STEP = 0.5
const val DEFAULT_SPEECH_RATE = 0.9f
const val MIN_SPEECH_RATE = 0.5f
const val MAX_SPEECH_RATE = 1.5f
