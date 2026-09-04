package org.yangtse.hearwrite.data

import android.content.Context
import android.util.Log
import io.edge.EdgeTts as EdgeClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.yangtse.hearwrite.domain.DEFAULT_SPEECH_RATE

/** Neural voice per text language (fixed defaults; sentence-capable). */
internal const val EDGE_VOICE_ZH = "zh-CN-XiaoxiaoNeural"
internal const val EDGE_VOICE_EN = "en-US-AriaNeural"

/** Voice per text: CJK → zh voice, else en (Youdao/provider convention). */
internal fun edgeVoiceFor(text: String): String =
    if (isCjkText(text)) EDGE_VOICE_ZH else EDGE_VOICE_EN

/**
 * Speech-rate → prosody percentage: 0.5–1.5 maps linearly to -50%..+50%
 * (`+` sign for non-negative; the setting clamps first).
 */
internal fun edgeRatePercent(rate: Float): String {
    val percent = ((rate.coerceIn(0.5f, 1.5f) - 1f) * 100).roundToInt()
    return if (percent >= 0) "+$percent%" else "$percent%"
}

/**
 * One selectable Edge Read-Aloud voice. [shortName] is the
 * `zh-CN-XiaoxiaoNeural` selection key and also the long-name source for
 * the SSML `voice` attribute (the server derives `Microsoft Server Speech
 * Text to Speech Voice (…)` from it); [friendlyName] is the human label.
 */
data class EdgeVoice(
    val name: String,
    val shortName: String,
    val gender: String,
    val locale: String,
    val friendlyName: String,
    val status: String,
)

/**
 * The curated voice catalog shown in 设置 → Edge 音色, split by the
 * language they speak. Sources: the live voices/list response, curated to
 * the locales this app serves (zh-CN for Chinese dictation, en-US for
 * English — textbook lists are Mainland Chinese + English). Dialects are
 * excluded (粤语/东北话 would mispronounce 普通话 lists) and unreleased
 * voices dropped; zh-HK/zh-TW are traditional-script voices that cannot
 * serve simplified 生字. ShortNames here are the *stable selection keys*.
 */
val EDGE_VOICE_CATALOG: List<EdgeVoice> = listOf(
    EdgeVoice(
        name = "Microsoft Server Speech Text to Speech Voice (zh-CN, XiaoxiaoNeural)",
        shortName = "zh-CN-XiaoxiaoNeural", gender = "Female", locale = "zh-CN",
        friendlyName = "晓晓（女声 · 温暖）", status = "GA",
    ),
    EdgeVoice(
        name = "Microsoft Server Speech Text to Speech Voice (zh-CN, XiaoyiNeural)",
        shortName = "zh-CN-XiaoyiNeural", gender = "Female", locale = "zh-CN",
        friendlyName = "晓伊（女声 · 活泼）", status = "GA",
    ),
    EdgeVoice(
        name = "Microsoft Server Speech Text to Speech Voice (zh-CN, YunxiNeural)",
        shortName = "zh-CN-YunxiNeural", gender = "Male", locale = "zh-CN",
        friendlyName = "云希（男声 · 阳光）", status = "GA",
    ),
    EdgeVoice(
        name = "Microsoft Server Speech Text to Speech Voice (zh-CN, YunjianNeural)",
        shortName = "zh-CN-YunjianNeural", gender = "Male", locale = "zh-CN",
        friendlyName = "云健（男声 · 激情）", status = "GA",
    ),
    EdgeVoice(
        name = "Microsoft Server Speech Text to Speech Voice (zh-CN, YunyangNeural)",
        shortName = "zh-CN-YunyangNeural", gender = "Male", locale = "zh-CN",
        friendlyName = "云扬（男声 · 新闻）", status = "GA",
    ),
    EdgeVoice(
        name = "Microsoft Server Speech Text to Speech Voice (zh-CN, YunxiaNeural)",
        shortName = "zh-CN-YunxiaNeural", gender = "Male", locale = "zh-CN",
        friendlyName = "云夏（童声 · 可爱）", status = "GA",
    ),
    EdgeVoice(
        name = "Microsoft Server Speech Text to Speech Voice (en-US, AriaNeural)",
        shortName = "en-US-AriaNeural", gender = "Female", locale = "en-US",
        friendlyName = "Aria（女声）", status = "GA",
    ),
    EdgeVoice(
        name = "Microsoft Server Speech Text to Speech Voice (en-US, JennyNeural)",
        shortName = "en-US-JennyNeural", gender = "Female", locale = "en-US",
        friendlyName = "Jenny（女声）", status = "GA",
    ),
    EdgeVoice(
        name = "Microsoft Server Speech Text to Speech Voice (en-US, MichelleNeural)",
        shortName = "en-US-MichelleNeural", gender = "Female", locale = "en-US",
        friendlyName = "Michelle（女声）", status = "GA",
    ),
    EdgeVoice(
        name = "Microsoft Server Speech Text to Speech Voice (en-US, GuyNeural)",
        shortName = "en-US-GuyNeural", gender = "Male", locale = "en-US",
        friendlyName = "Guy（男声）", status = "GA",
    ),
    EdgeVoice(
        name = "Microsoft Server Speech Text to Speech Voice (en-US, BrianNeural)",
        shortName = "en-US-BrianNeural", gender = "Male", locale = "en-US",
        friendlyName = "Brian（男声）", status = "GA",
    ),
    EdgeVoice(
        name = "Microsoft Server Speech Text to Speech Voice (en-US, ChristopherNeural)",
        shortName = "en-US-ChristopherNeural", gender = "Male", locale = "en-US",
        friendlyName = "Christopher（男声）", status = "GA",
    ),
    EdgeVoice(
        name = "Microsoft Server Speech Text to Speech Voice (en-US, EricNeural)",
        shortName = "en-US-EricNeural", gender = "Male", locale = "en-US",
        friendlyName = "Eric（男声）", status = "GA",
    ),
)

/** Default Edge voice shortName for [lang] ("" → zh voice). */
internal fun edgeDefaultVoiceFor(lang: String): String =
    if (lang.startsWith("zh", ignoreCase = true)) EDGE_VOICE_ZH else EDGE_VOICE_EN

/**
 * The curated shortNames for [lang]: zh-* → the Mainland zh-CN voices,
 * otherwise en-US.
 */
internal fun edgeCatalogShortNames(lang: String): List<String> =
    if (lang.startsWith("zh", ignoreCase = true)) {
        EDGE_VOICE_CATALOG.filter { it.locale.startsWith("zh-CN") }.map { it.shortName }
    } else {
        EDGE_VOICE_CATALOG.filter { it.locale == "en-US" }.map { it.shortName }
    }

/**
 * Stable 8-hex cache hash binding a clip to voice + rate (djb2, provider
 * pattern) — changing either regenerates instead of replaying stale audio.
 */
internal fun edgeClipHash(voice: String, rate: Float): String {
    val rateQ = (rate * 10).roundToInt()
    var h = 5381
    for (ch in "edge|$voice|$rateQ") {
        h = ((h shl 5) + h + ch.code)
    }
    return Integer.toHexString(h).padStart(8, '0')
}

/**
 * Cache file name under `cacheDir/tts/`. The `edge-` prefix keeps the flat
 * cache namespace disjoint from Youdao (`<text>.mp3`) and provider clips
 * (`<text>.<hash>.mp3`); the hash binds voice + rate.
 */
internal fun edgeClipFileName(text: String, voice: String, rate: Float): String {
    val safe = uriComponentEncode(text.trim().lowercase()).replace("%", "_").ifEmpty { "unknown" }
    return "edge-$safe.${edgeClipHash(voice, rate)}.mp3"
}

/**
 * Edge Read-Aloud clip fetcher with a disk cache and per-clip single flight
 * (Youdao/provider pattern, AGENTS.md). The synthesis protocol is delegated
 * to the vendored [EdgeClient] (`io/edge/EdgeTts.kt`, the standalone
 * rany2/edge-tts-aligned client that speaks the service's current wire
 * protocol); this class only decides which voice to use, warms the cache in
 * the background and serves ready clips — the playback chain never blocks
 * on a download. Every failure degrades to "no clip"; nothing here throws
 * into the caller.
 *
 * Voice selection (设置 → Edge 音色): per-language shortNames follow the
 * persisted settings (`edge_voice_zh` / `edge_voice_en`) with the built-in
 * neural defaults as the fallback. Clip cache keys bind voice + rate, so
 * switching a voice regenerates instead of replaying stale audio.
 */
class EdgeTts(
    private val context: Context,
    settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The vendored synthesis client (one stream at a time — the reference
     * client's `streamInUse` guard rejects concurrent streams on the same
     * instance). All synthesis (downloads + previews) shares this instance,
     * so every call is serialized through [synthLock]; the per-text single
     * flight above only de-dupes identical texts.
     */
    private val client = EdgeClient()
    private val synthLock = kotlinx.coroutines.sync.Mutex()

    private val inFlight = ConcurrentHashMap<String, Deferred<Unit>>()

    /** Speech-rate ×10 feeds the cache hash; follows the persisted 语速. */
    @Volatile
    private var rate: Float = DEFAULT_SPEECH_RATE

    /** The persisted voice per language (zh/en shortNames); live-followed. */
    @Volatile
    private var voiceZh: String = EDGE_VOICE_ZH
    @Volatile
    private var voiceEn: String = EDGE_VOICE_EN

    init {
        scope.launch { settings.speechRate.collect { rate = it } }
        scope.launch { settings.edgeVoiceZh.collect { voiceZh = it.ifBlank { EDGE_VOICE_ZH } } }
        scope.launch { settings.edgeVoiceEn.collect { voiceEn = it.ifBlank { EDGE_VOICE_EN } } }
    }

    private val cacheDir: File
        get() = File(context.cacheDir, "tts")

    /** The voice shortName currently selected for [text]'s language. */
    fun selectedVoice(text: String): String {
        val trimmed = text.trim()
        return if (isCjkText(trimmed)) voiceZh else voiceEn
    }

    /** The ready cached clip for [text], or null when absent/too small. */
    fun cachedClip(text: String): File? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val file = clipFile(trimmed, selectedVoice(trimmed), rate)
        return if (file.isFile && file.length() >= MIN_AUDIO_BYTES) file else null
    }

    /**
     * Background-warm [text]: returns immediately when a valid clip is
     * already cached or a download is in flight (awaiting it); otherwise
     * starts a download on the internal scope and waits for it. The await
     * is cancellable — cancelling never aborts the shared download.
     */
    suspend fun prefetch(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        cachedClip(trimmed)?.let { return true }

        val key = edgeFlightKey(trimmed, selectedVoice(trimmed), rate)
        val existing = inFlight[key]
        if (existing != null) {
            try {
                existing.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The other download failed; fall through to retry once.
            }
            return cachedClip(trimmed) != null
        }

        val job = scope.async { download(trimmed, rate) }
        val winner = inFlight.putIfAbsent(key, job)
        if (winner != null) {
            job.cancel()
            try {
                winner.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Failed download; report the cache state below.
            }
            return cachedClip(trimmed) != null
        }
        try {
            job.await()
        } finally {
            inFlight.remove(key, job)
        }
        return cachedClip(trimmed) != null
    }

    private fun edgeFlightKey(text: String, voice: String, r: Float): String =
        "edge:${edgeClipHash(voice, r)}:${text.trim().lowercase()}"

    /** Synthesize and write the clip; any failure just leaves no file. */
    private suspend fun download(trimmed: String, r: Float) {
        try {
            val voice = selectedVoice(trimmed)
            val bytes = synthOnce(trimmed, voice, r) ?: return
            if (bytes.size < MIN_AUDIO_BYTES) return
            val dest = clipFile(trimmed, voice, r)
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "download failed for \"$trimmed\"", e)
        }
    }

    private fun clipFile(text: String, voice: String, r: Float): File =
        File(cacheDir, edgeClipFileName(text, voice, r))

    /**
     * Synthesize [sample] with the explicit [voice] shortName (not the
     * persisted one) and return the MP3 bytes — the 设置试听 path for a voice
     * that is not yet selected. Any failure returns null.
     */
    suspend fun previewVoice(voice: String, sample: String): ByteArray? = try {
        synthLock.withLock {
            withTimeout(SYNTH_TIMEOUT_MS) {
                client.synthesize(sample, EdgeClient.Config(voice = voice))
            }
        }
    } catch (e: TimeoutCancellationException) {
        Log.w(TAG, "edge preview timed out for $voice")
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "voice preview failed for $voice", e)
        null
    }

    /** One full synthesis of [trimmed] in [voice] at [r]; null on any failure. */
    private suspend fun synthOnce(trimmed: String, voice: String, r: Float): ByteArray? = try {
        synthLock.withLock {
            withTimeout(SYNTH_TIMEOUT_MS) {
                client.synthesize(
                    trimmed,
                    EdgeClient.Config(
                        voice = voice,
                        rate = edgeRatePercent(r),
                    ),
                )
            }
        }
    } catch (e: TimeoutCancellationException) {
        Log.w(TAG, "edge synth timed out for \"$trimmed\"")
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "edge synth failed for \"$trimmed\"", e)
        null
    }

    companion object {
        private const val TAG = "EdgeTts"

        /**
         * Bound on one synthesis turn (the vendored client's own sink waits
         * up to 300 s; a dead network must not pin the download coroutine —
         * the old in-house client used a 30 s watchdog).
         */
        private const val SYNTH_TIMEOUT_MS = 30_000L
    }
}
