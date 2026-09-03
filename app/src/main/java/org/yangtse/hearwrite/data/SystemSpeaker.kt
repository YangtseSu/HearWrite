package org.yangtse.hearwrite.data

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import org.yangtse.hearwrite.domain.DEFAULT_SPEECH_RATE
import org.yangtse.hearwrite.domain.MAX_SPEECH_RATE
import org.yangtse.hearwrite.domain.MIN_SPEECH_RATE
import org.yangtse.hearwrite.domain.Speaker
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * System `android.speech.tts.TextToSpeech` wrapper — the always-available
 * fallback link of the TTS priority chain (AGENTS.md). All Android-only
 * concerns live here, behind the domain [Speaker] contract.
 *
 * Contract details that must not regress:
 * - Async init (`onInit`) is wrapped in a suspension resumed from the init
 *   callback; the first [speak] waits for it. Init failure is reported as
 *   `false`, never thrown.
 * - `UtteranceProgressListener` releases the pending continuation from both
 *   `onDone` and `onError` (missing onError = permanent hang).
 * - A watchdog (`max(4000, text.length * 250)` ms, same as upstream) releases
 *   the continuation even if the engine never reports — assume success, the
 *   next utterance flushes the queue. Playback must never freeze on a mute
 *   utterance.
 * - Cancelling the awaiting coroutine (pause/skip/stop/leave) stops the
 *   current utterance via `invokeOnCancellation`.
 * - Unsupported locale (missing data/language) is a failure, never a
 *   silently wrong-voice utterance.
 */
class SystemSpeaker(context: Context) : Speaker {

    private val appContext = context.applicationContext

    /** Speech rate (0.5–1.5, default 0.9); applied before every utterance. */
    @Volatile
    private var speechRate: Float = DEFAULT_SPEECH_RATE

    private val lock = Any()
    private var engine: TextToSpeech? = null
    private var initStarted = false
    private val initResult = CompletableDeferred<Boolean>()
    private val initMutex = kotlinx.coroutines.sync.Mutex()
    private val utteranceCounter = AtomicLong(0)

    /** Set the speech rate for subsequent utterances (persisted setting). */
    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
    }

    /**
     * Wait for the shared engine, creating it once. A failed init stays
     * failed for the process (the chain falls back / reports false).
     */
    private suspend fun ensureEngine(): TextToSpeech? {
        var current = synchronized(lock) { engine }
        if (current == null) {
            initMutex.withLock {
                current = synchronized(lock) { engine }
                if (current == null) {
                    synchronized(lock) {
                        if (!initStarted) {
                            initStarted = true
                            var holder: TextToSpeech? = null
                            holder = TextToSpeech(appContext) { status ->
                                val ok = status == TextToSpeech.SUCCESS
                                Log.i(TAG, "init ${if (ok) "ok" else "failed ($status)"}")
                                if (ok) {
                                    synchronized(lock) { engine = holder }
                                }
                                initResult.complete(ok)
                            }
                        }
                    }
                }
            }
            // Suspend until onInit fires (success or failure) — the init
            // callback is the only thing that completes this.
            initResult.await()
            current = synchronized(lock) { engine }
        }
        return current
    }

    override suspend fun speak(text: String, lang: String): Boolean = try {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val tts = ensureEngine() ?: return false

        val locale = Locale.forLanguageTag(lang)
        val langResult = tts.setLanguage(locale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA ||
            langResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            Log.w(TAG, "locale $lang unsupported ($langResult) for \"$trimmed\"")
            return false
        }
        tts.setSpeechRate(speechRate)

        val utteranceId = "hw-${utteranceCounter.incrementAndGet()}"
        val watchdogMs = maxOf(4_000L, trimmed.length * 250L)
        val settled = AtomicBoolean(false)

        val outcome = coroutineScope {
            var continuation: Continuation<Boolean>? = null

            val watchdog = launch {
                delay(watchdogMs)
                continuation?.let { settle(it, settled, true, "watchdog") }
            }

            val result = suspendCancellableCoroutine { cont ->
                continuation = cont
                val listener = object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(id: String?) {
                        if (id == utteranceId) {
                            settle(cont, settled, true, "onDone")
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {
                        if (id == utteranceId) {
                            settle(cont, settled, false, "onError")
                        }
                    }

                    override fun onError(id: String?, errorCode: Int) {
                        if (id == utteranceId) {
                            settle(cont, settled, false, "onError($errorCode)")
                        }
                    }

                    override fun onStop(id: String?, interrupted: Boolean) {
                        // Stopped by our own stop()/a newer utterance; release
                        // so the chain never hangs on a stop.
                        if (id == utteranceId) {
                            settle(cont, settled, true, "onStop")
                        }
                    }
                }
                tts.setOnUtteranceProgressListener(listener)

                // Cancel the awaiting run (pause/skip/stop/leave): stop audio.
                cont.invokeOnCancellation {
                    try {
                        tts.stop()
                    } catch (e: Exception) {
                        Log.w(TAG, "stop-on-cancel failed", e)
                    }
                }

                val result = tts.speak(trimmed, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                if (result == TextToSpeech.ERROR) {
                    settle(cont, settled, false, "speak returned ERROR")
                } else {
                }
            }
            watchdog.cancel()
            result
        }
        outcome
    } catch (e: CancellationException) {
        // The playback run was cancelled; propagate, never swallow.
        throw e
    } catch (e: Exception) {
        // Defensive audio boundary: never throw into the playback engine.
        Log.w(TAG, "speak failed", e)
        false
    }

    /** Kill the current utterance (pause/skip/leave screen). Idempotent. */
    override fun stop() {
        synchronized(lock) {
            try {
                engine?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "stop failed", e)
            }
        }
    }

    /** Single-shot resume guarded against listener/cancel/watchdog races. */
    private fun settle(
        cont: Continuation<Boolean>,
        settled: AtomicBoolean,
        ok: Boolean,
        why: String,
    ) {
        if (settled.compareAndSet(false, true)) {
            try {
                cont.resume(ok)
            } catch (e: IllegalStateException) {
                // The awaiting run was cancelled concurrently; nothing to do.
            }
        }
    }

    companion object {
        private const val TAG = "SystemSpeaker"
    }
}
