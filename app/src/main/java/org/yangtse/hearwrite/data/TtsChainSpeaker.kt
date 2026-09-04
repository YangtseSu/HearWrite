package org.yangtse.hearwrite.data

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.yangtse.hearwrite.domain.Speaker
import org.yangtse.hearwrite.domain.TtsSource

/**
 * The word-pass TTS chain (AGENTS.md "TTS priority chain"): three **peer**
 * sources — Youdao, Edge Read-Aloud and the OpenAI-compatible provider —
 * plus the always-available system voice. Sources are mutually exclusive
 * and cache-independent: the active source plays only its own cached clips
 * (never another source's cache), and the system voice is the **sole
 * fallback** when the source cannot serve the text.
 *
 * Link order by source:
 * - [TtsSource.YOUDAO]: Youdao ready clip → system voice.
 * - [TtsSource.EDGE]: Edge Read-Aloud ready clip → system voice (keyless
 *   Microsoft neural voices).
 * - [TtsSource.CUSTOM]: provider ready clip → system voice (the
 *   OpenAI-compatible provider from Settings; unconfigured → system).
 * - [TtsSource.SYSTEM]: everything straight to the system voice.
 *
 * Cold-start behavior: a cache miss waits a bounded time
 * ([SPEAK_FETCH_TIMEOUT_MS]) for the active source's download before the
 * system fallback, so one dictation keeps a single voice instead of opening
 * on the system voice while the source warms up. [prefetch] still warms the
 * cache in the background; the speak-time wait joins the same per-text
 * single flight and never blocks unboundedly.
 *
 * The 组词 phrase pass ALSO rides this chain — except under [TtsSource.YOUDAO],
 * whose dict voice cannot serve sentences: the dictation phrase speaker
 * (`DictationViewModel`) sends the phrase straight to the system zh-CN voice
 * in that case, and phrases are never fetched from the network under Youdao.
 *
 * Clip playback contract (AGENTS.md): `MediaPlayer` guarded by a completion
 * listener **plus a 10 s watchdog** so a stuck clip can never freeze
 * dictation; [stop] interrupts the current clip, the system utterance and
 * the fallback (a stopped session never speaks).
 */
class TtsChainSpeaker(
    private val youdaoTts: YoudaoTts,
    private val provider: OpenAiCompatibleTts,
    private val edge: EdgeTts,
    private val system: SystemSpeaker,
) : Speaker {

    @Volatile
    private var source: TtsSource = TtsSource.YOUDAO

    /** Set by [stop]; suppresses the system fallback after an interrupted clip. */
    @Volatile
    private var interrupted = false

    /** The clip playback currently holding the audio (settled by [stop]). */
    private var activeClip: ActiveClip? = null

    fun setSource(value: TtsSource) {
        source = value
    }

    /** The configured source — the dictation phrase pass routes on it. */
    fun currentSource(): TtsSource = source

    /** Background-warm [text]/[lang] on the active source's cache. */
    suspend fun prefetch(text: String, lang: String) {
        if (text.isBlank()) return
        when (source) {
            TtsSource.YOUDAO -> youdaoTts.prefetch(text, lang)
            TtsSource.CUSTOM -> provider.prefetch(text)
            TtsSource.EDGE -> edge.prefetch(text)
            TtsSource.SYSTEM -> Unit
        }
    }

    override suspend fun speak(text: String, lang: String): Boolean = try {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        interrupted = false

        if (source == TtsSource.SYSTEM) {
            return system.speak(trimmed, lang)
        }

        // Ready cached audio first; a cold-start miss waits a bounded time for
        // the active source's own download (single-flight, cancellable) so
        // the session does not open on the system voice. On failure/timeout
        // the playback never blocks unboundedly — straight to system.
        val clip = cachedClip(trimmed, lang)
            ?: withTimeoutOrNull(SPEAK_FETCH_TIMEOUT_MS) { fetchFromSource(trimmed, lang) }
        if (clip != null) {
            val ok = playClip(clip)
            if (ok || interrupted) {
                // Heard, or stopped by pause/skip/leave: no second attempt.
                return ok
            }
            Log.w(TAG, "clip playback failed, falling back to system: ${clip.name}")
        }
        system.speak(trimmed, lang)
    } catch (e: CancellationException) {
        // The playback run was cancelled (pause/skip/stop/leave); propagate.
        throw e
    } catch (e: Exception) {
        // Defensive audio boundary: never throw into the playback engine.
        Log.w(TAG, "speak failed", e)
        false
    }

    /** Interrupt the current clip and any system utterance. Idempotent. */
    override fun stop() {
        interrupted = true
        activeClip?.finish(false, "stop")
        system.stop()
    }

    /**
     * The active source's own ready clip — cached audio only, and **never
     * another source's cache**: sources are peers by design, so one
     * dictation under EDGE cannot play leftover Youdao clips (a mixed-voice
     * session; the old cross-source fallback).
     */
    private fun cachedClip(trimmed: String, lang: String): File? = when (source) {
        TtsSource.SYSTEM -> null
        TtsSource.YOUDAO -> youdaoTts.cachedClip(trimmed, lang)
        TtsSource.EDGE -> edge.cachedClip(trimmed)
        TtsSource.CUSTOM -> provider.cachedClip(trimmed)
    }

    /**
     * Download [trimmed] from the active source and return its clip, or null
     * on failure. Joins any in-flight prefetch via the per-text single
     * flight. The caller bounds this with [SPEAK_FETCH_TIMEOUT_MS]; a failed
     * fetch degrades to the system fallback.
     */
    private suspend fun fetchFromSource(trimmed: String, lang: String): File? {
        val ready = when (source) {
            TtsSource.YOUDAO -> youdaoTts.prefetch(trimmed, lang)
            TtsSource.EDGE -> edge.prefetch(trimmed)
            TtsSource.CUSTOM -> provider.prefetch(trimmed)
            TtsSource.SYSTEM -> false
        }
        return if (ready) cachedClip(trimmed, lang) else null
    }

    /**
     * Play one clip file outside a dictation session — the settings
     * 测试并试听 path. Same watchdog/cancellation contract as session clips.
     */
    suspend fun playTestClip(file: File): Boolean = playClip(file)

    /**
     * Play one cached clip, suspending until it completes or fails. A 10 s
     * watchdog releases a stuck clip (failure → the chain falls back to
     * system TTS); cancellation (pause/skip/leave) releases it immediately.
     */
    private suspend fun playClip(file: File): Boolean = coroutineScope {
        val clip = ActiveClip(file)
        activeClip = clip

        val watchdog = launch {
            delay(CLIP_TIMEOUT_MS)
            clip.finish(false, "watchdog")
        }

        val result = suspendCancellableCoroutine { cont ->
            clip.attach(cont)
            cont.invokeOnCancellation {
                clip.finish(false, "cancelled")
            }
            val player = clip.player
            try {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                player.setDataSource(file.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "clip open failed: ${file.name}", e)
                clip.finish(false, "open failed")
                return@suspendCancellableCoroutine
            }
            player.setOnPreparedListener { mp ->
                try {
                    mp.start()
                } catch (e: Exception) {
                    Log.w(TAG, "clip start failed: ${file.name}", e)
                    clip.finish(false, "start failed")
                }
            }
            player.setOnCompletionListener {
                clip.finish(true, "done")
            }
            player.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "clip error what=$what extra=$extra on ${file.name}")
                clip.finish(false, "error $what/$extra")
                true
            }
            try {
                player.prepareAsync()
            } catch (e: Exception) {
                Log.w(TAG, "clip prepare failed: ${file.name}", e)
                clip.finish(false, "prepare failed")
            }
        }

        watchdog.cancel()
        if (activeClip === clip) activeClip = null
        result
    }

    /**
     * One in-flight clip playback: player + a single-shot [finish] guarded
     * against listener/watchdog/stop/cancel races (SystemSpeaker pattern).
     */
    private inner class ActiveClip(file: File) {
        val player = MediaPlayer()
        private val settled = AtomicBoolean(false)
        private var continuation: Continuation<Boolean>? = null

        fun attach(cont: Continuation<Boolean>) {
            continuation = cont
        }

        fun finish(ok: Boolean, why: String) {
            if (!settled.compareAndSet(false, true)) return
            try {
                if (player.isPlaying) player.stop()
            } catch (e: Exception) {
                // Released already; nothing to stop.
            }
            try {
                player.release()
            } catch (e: Exception) {
                Log.w(TAG, "clip release failed", e)
            }
            try {
                continuation?.resume(ok)
            } catch (e: IllegalStateException) {
                // The awaiting run was cancelled concurrently; nothing to do.
            }
        }
    }

    companion object {
        private const val TAG = "TtsChainSpeaker"

        /** Watchdog for a single clip; a stuck MediaPlayer must never freeze dictation. */
        private const val CLIP_TIMEOUT_MS = 10_000L

        /**
         * Bounded cold-start wait for the active source's download before the
         * system fallback. Covers typical Youdao (<1 s), Edge and provider
         * (1–3 s) cold turns so one dictation keeps a single voice; a dead
         * network degrades to the system voice within this bound.
         */
        private const val SPEAK_FETCH_TIMEOUT_MS = 4_000L
    }
}
