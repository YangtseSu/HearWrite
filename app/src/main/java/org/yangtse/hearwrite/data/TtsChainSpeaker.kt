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
import org.yangtse.hearwrite.domain.Speaker
import org.yangtse.hearwrite.domain.TtsSource

/**
 * The word-pass TTS chain (AGENTS.md "TTS priority chain"): **ready cached
 * clips only** play through [MediaPlayer] — [speak] never waits for a
 * download. Link order by source:
 * - [TtsSource.YOUDAO]: Youdao ready clip → system voice.
 * - [TtsSource.CUSTOM]: provider ready clip → Youdao ready clip → system
 *   voice (the OpenAI-compatible provider from Settings; unconfigured or
 *   failing links fall straight through).
 * - [TtsSource.SYSTEM]: everything straight to the system voice.
 *
 * The 组词 phrase pass stays pinned to the system speaker (engine
 * `phraseSpeaker`). Clip playback contract (AGENTS.md): `MediaPlayer`
 * guarded by a completion listener **plus a 10 s watchdog** so a stuck clip
 * can never freeze dictation; [stop] interrupts the current clip, the
 * system utterance and the fallback (a stopped session never speaks).
 */
class TtsChainSpeaker(
    private val youdaoTts: YoudaoTts,
    private val provider: OpenAiCompatibleTts,
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

    /** Background-warm [text]/[lang] on the active source's cache. */
    suspend fun prefetch(text: String, lang: String) {
        if (text.isBlank()) return
        when (source) {
            TtsSource.YOUDAO -> youdaoTts.prefetch(text, lang)
            TtsSource.CUSTOM -> provider.prefetch(text)
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

        // Ready-cached audio only — a miss degrades instantly, never blocks.
        for (clip in cachedClips(trimmed, lang)) {
            val ok = playClip(clip)
            if (ok || interrupted) {
                // Heard, or stopped by pause/skip/leave: no second attempt.
                return ok
            }
            Log.w(TAG, "clip playback failed, trying the next link: ${clip.name}")
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
     * Ready cached clips for [trimmed] in link order (see the class KDoc).
     * The provider link only exists when its config is set — an
     * unconfigured custom source degrades to youdao → system.
     */
    private fun cachedClips(trimmed: String, lang: String): List<File> = when (source) {
        TtsSource.SYSTEM -> emptyList()
        TtsSource.CUSTOM ->
            listOfNotNull(provider.cachedClip(trimmed), youdaoTts.cachedClip(trimmed, lang))
        TtsSource.YOUDAO -> listOfNotNull(youdaoTts.cachedClip(trimmed, lang))
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
            if (why != "stop") Log.i(TAG, "clip end ($why) ok=$ok")
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
    }
}
