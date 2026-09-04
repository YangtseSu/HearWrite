package org.yangtse.hearwrite.data

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.yangtse.hearwrite.domain.DEFAULT_SPEECH_RATE
import org.yangtse.hearwrite.domain.MAX_SPEECH_RATE
import org.yangtse.hearwrite.domain.MIN_SPEECH_RATE

/**
 * Microsoft Edge Read-Aloud online TTS — the keyless neural voices behind
 * Edge 朗读 (AGENTS.md "TTS priority chain", the EDGE source). Protocol
 * facts pinned from rany2/edge-tts (MIT) master, 2026-09:
 * - wss://speech.platform.bing.com/.../edge/v1 with `TrustedClientToken`,
 *   `Sec-MS-GEC` (SHA-256 of "winTicks + token", winTicks = unix + 11644473600
 *   floored to 300 s, ×10⁷, float-rounded like upstream) and
 *   `Sec-MS-GEC-Version=1-<chromium>`, an Edge/Chromium User-Agent, an
 *   `Origin: chrome-extension://…` header and a fresh `muid` cookie;
 * - one text message per turn: `Path:speech.config` JSON, then
 *   `Path:ssml` XML (voice + prosody rate); binary frames carry
 *   `Path:audio` MP3 chunks, `Path:turn.end` closes the turn;
 * - a 403 with a `Date` header means clock skew: adjust once and retry.
 *
 * Microsoft has broken this endpoint several times (2023 Sec-MS-GEC, 2025-12
 * MUID/UA/chunk changes); when it breaks again, sync the constants below
 * with upstream changelog (AGENTS.md maintenance ritual). Playback stays
 * failure-degrading: this class only ever fills the ready-clip cache.
 */

/** Fixed browser client token (upstream `TRUSTED_CLIENT_TOKEN`, 2026-09). */
internal const val EDGE_TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

/** `Sec-MS-GEC-Version` — the Edge build the UA/version emulate. */
internal const val EDGE_SEC_MS_GEC_VERSION = "1-143.0.3650.75"

/** WSS host fragment (query params appended per connection). */
internal const val EDGE_WSS_URL =
    "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"

/** Neural voice per text language (fixed defaults; sentence-capable). */
internal const val EDGE_VOICE_ZH = "zh-CN-XiaoxiaoNeural"
internal const val EDGE_VOICE_EN = "en-US-AriaNeural"

private const val EDGE_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" +
        " (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
private const val EDGE_ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"

/** Unix → Windows file time epoch offset (1601-01-01). */
private const val EDGE_WIN_EPOCH_SEC = 11644473600.0

/** Sec-MS-GEC time window length (seconds, upstream `ticks -= ticks % 300`). */
private const val EDGE_GEC_WINDOW_SEC = 300.0

/** Whole-turn synthesis bound (connect + SSML + audio stream). */
private const val EDGE_TURN_TIMEOUT_MS = 30_000L

/** Output format negotiated in `speech.config` (48 kbps CBR MP3). */
private const val EDGE_OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"

/** Voice per text: CJK → zh voice, else en (Youdao/provider convention). */
internal fun edgeVoiceFor(text: String): String =
    if (isCjkText(text)) EDGE_VOICE_ZH else EDGE_VOICE_EN

/** Python-parity JS date string (`time.strftime(..., gmtime)`, edge-tts). */
private val EDGE_DATE_FORMAT = DateTimeFormatter
    .ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
    .withZone(ZoneOffset.UTC)

internal fun edgeDateString(nowEpochMs: Long): String =
    EDGE_DATE_FORMAT.format(Instant.ofEpochMilli(nowEpochMs))

/**
 * Windows file-time ticks for [nowEpochSec], rounded to the 5-minute
 * Sec-MS-GEC window, in 100 ns units. Deliberately mirrors upstream's float
 * arithmetic (`ticks -= ticks % 300; ticks *= 1e7`) so tokens match the
 * Python client bit for bit.
 */
internal fun edgeWinTicks(nowEpochSec: Double): Double {
    var ticks = nowEpochSec + EDGE_WIN_EPOCH_SEC
    ticks -= ticks % EDGE_GEC_WINDOW_SEC
    return ticks * 1e7
}

/**
 * `Sec-MS-GEC` for [nowEpochSec]: SHA-256 of "<%.0f ticks><client token>",
 * uppercase hex (upstream `DRM.generate_sec_ms_gec`). The `%.0f` formatting
 * is Java's HALF_UP vs Python's half-even, but at this magnitude the two
 * only differ on exact .5 values, which a 300 s window never produces.
 */
internal fun edgeSecMsGec(nowEpochSec: Double): String {
    val seed = String.format(Locale.US, "%.0f", edgeWinTicks(nowEpochSec)) + EDGE_TRUSTED_CLIENT_TOKEN
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(seed.toByteArray(Charsets.US_ASCII))
    return digest.joinToString("") { "%02X".format(it) }
}

/** Random 32-hex `muid` cookie value (upstream `DRM.generate_muid`). */
internal fun edgeMuid(): String = UUID.randomUUID().toString().replace("-", "").uppercase()

/** Fresh connection/request id (uuid without dashes, upstream `connect_id`). */
internal fun edgeConnectionId(): String = UUID.randomUUID().toString().replace("-", "")

/** The per-turn WSS URL with the token, connection id and GEC params. */
internal fun edgeWsUrl(connectionId: String, secMsGec: String): String =
    "$EDGE_WSS_URL?TrustedClientToken=$EDGE_TRUSTED_CLIENT_TOKEN" +
        "&ConnectionId=$connectionId&Sec-MS-GEC=$secMsGec&Sec-MS-GEC-Version=$EDGE_SEC_MS_GEC_VERSION"

/**
 * `Path:speech.config` frame body — JSON context negotiating the output
 * format and boundary metadata (upstream `send_command_request`; sentence
 * boundaries stay enabled so the service behaves like the default client).
 */
internal fun edgeSpeechConfigFrame(nowEpochMs: Long): String =
    "X-Timestamp:${edgeDateString(nowEpochMs)}\r\n" +
        "Content-Type:application/json; charset=utf-8\r\n" +
        "Path:speech.config\r\n\r\n" +
        """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"true","wordBoundaryEnabled":"false"},"outputFormat":"$EDGE_OUTPUT_FORMAT"}}}}\r\n"""

/**
 * The service rejects a few control-char ranges (vertical tab etc. from
 * OCR'd text); they become spaces (upstream `remove_incompatible_characters`).
 */
internal fun edgeCleanText(text: String): String = buildString(text.length) {
    for (ch in text) {
        val code = ch.code
        append(if ((code in 0..8) || (code in 11..12) || (code in 14..31)) ' ' else ch)
    }
}

/** XML text escaping for the SSML body (upstream saxutils `escape`). */
internal fun edgeXmlEscape(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * Speech-rate → prosody percentage: 0.5–1.5 maps linearly to -50%..+50%
 * (`+` sign for non-negative; the setting clamps first).
 */
internal fun edgeRatePercent(rate: Float): String {
    val percent = ((rate.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE) - 1f) * 100).roundToInt()
    return if (percent >= 0) "+$percent%" else "$percent%"
}

/** SSML document for one synthesis turn (voice + prosody wrapper). */
internal fun edgeSsml(text: String, voice: String, ratePercent: String): String =
    "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
        "<voice name='$voice'>" +
        "<prosody pitch='+0Hz' rate='$ratePercent' volume='+0%'>" +
        edgeXmlEscape(edgeCleanText(text)) +
        "</prosody></voice></speak>"

/**
 * `Path:ssml` frame body. The `Z` suffix appended to the X-Timestamp date is
 * an upstream quirk faithfully replicated ("This is not a mistake, Microsoft
 * Edge bug" — rany2/edge-tts).
 */
internal fun edgeSsmlFrame(requestId: String, ssml: String, nowEpochMs: Long): String =
    "X-RequestId:$requestId\r\n" +
        "Content-Type:application/ssml+xml\r\n" +
        "X-Timestamp:${edgeDateString(nowEpochMs)}Z\r\n" +
        "Path:ssml\r\n\r\n" +
        ssml

/** `Path:` value of a text frame ("" when absent). */
internal fun edgeTextPath(text: String): String {
    val headerEnd = text.indexOf("\r\n\r\n")
    val head = if (headerEnd >= 0) text.substring(0, headerEnd) else text
    for (line in head.split("\r\n")) {
        if (line.startsWith("Path:")) return line.substring(5)
    }
    return ""
}

/**
 * Parse one binary audio frame: first two bytes big-endian header length,
 * then `Key:value` header lines, `\r\n`, then the payload (upstream
 * `get_headers_and_data`). Null when the frame is too short to be valid.
 */
internal fun edgeParseBinaryFrame(data: ByteArray): Pair<Map<String, String>, ByteArray>? {
    if (data.size < 2) return null
    val headerLength = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
    val payloadStart = 2 + headerLength + 2 // header block + trailing \r\n
    if (payloadStart > data.size) return null
    val headers = mutableMapOf<String, String>()
    val head = data.copyOfRange(2, 2 + headerLength).toString(Charsets.US_ASCII)
    for (line in head.split("\r\n")) {
        if (line.isEmpty()) continue
        val colon = line.indexOf(':')
        if (colon > 0) headers[line.substring(0, colon)] = line.substring(colon + 1)
    }
    return headers to data.copyOfRange(payloadStart, data.size)
}

/** Stable 8-hex cache hash binding a clip to voice + rate (djb2, provider pattern). */
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
 * (`<text>.<hash>.mp3`); the hash binds voice + rate so a voice/rate change
 * regenerates instead of replaying stale audio.
 */
internal fun edgeClipFileName(text: String, voice: String, rate: Float): String {
    val safe = uriComponentEncode(text.trim().lowercase()).replace("%", "_").ifEmpty { "unknown" }
    return "edge-$safe.${edgeClipHash(voice, rate)}.mp3"
}

/**
 * Edge Read-Aloud clip fetcher with a disk cache and per-clip single flight
 * (Youdao/provider pattern, AGENTS.md). Downloads run on an internal scope —
 * [prefetch] warms the cache in the background and [cachedClip] returns
 * ready audio only; the playback chain never blocks on a download. Every
 * failure degrades to "no clip"; nothing here throws into the caller.
 */
class EdgeTts(
    private val context: Context,
    settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val inFlight = ConcurrentHashMap<String, Deferred<Unit>>()

    /** Speech-rate ×10 feeds the cache hash; follows the persisted 语速. */
    @Volatile
    private var rate: Float = DEFAULT_SPEECH_RATE

    /** Server-clock offset (seconds) learned from a 403 `Date` header. */
    @Volatile
    private var clockSkewSec = 0.0

    init {
        scope.launch { settings.speechRate.collect { rate = it } }
    }

    private val cacheDir: File
        get() = File(context.cacheDir, "tts")

    /** The ready cached clip for [text], or null when absent/too small. */
    fun cachedClip(text: String): File? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val file = clipFile(trimmed, edgeVoiceFor(trimmed), rate)
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

        val key = edgeFlightKey(trimmed, edgeVoiceFor(trimmed), rate)
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
            val voice = edgeVoiceFor(trimmed)
            val bytes = synthTurn(trimmed, voice, r) ?: return
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
     * One full WebSocket turn for [text]; null on any failure. A 403 with a
     * parseable `Date` header is treated as clock skew and retried once
     * with the offset applied (upstream `DRM.handle_client_response_error`).
     */
    private suspend fun synthTurn(trimmed: String, voice: String, r: Float): ByteArray? {
        var attempt = 0
        var failureCode = 0
        var serverDate: String? = null
        while (attempt < 2) {
            attempt++
            failureCode = 0
            serverDate = null
            val bytes = synthOnce(trimmed, voice, r) { code, date ->
                failureCode = code
                serverDate = date
            }
            if (bytes != null) return bytes
            // Clock-skew correction applies to the 403 path only (upstream
            // `DRM.handle_client_response_error`); other failures are final.
            val date = serverDate
            if (failureCode != 403 || date == null || attempt > 1) return null
            val skew = try {
                java.time.ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli() / 1000.0 - System.currentTimeMillis() / 1000.0
            } catch (e: DateTimeParseException) {
                null
            }
            if (skew == null) return null
            clockSkewSec = skew
            Log.w(TAG, "403 clock skew ${clockSkewSec}s, retrying once")
        }
        return null
    }

    /**
     * One WebSocket turn: connect with token/muid headers, send the
     * speech.config + ssml frames, collect `Path:audio` MP3 chunks until
     * `Path:turn.end`. `onFailure` records the HTTP status/Date for the
     * skew retry. Bounded by [EDGE_TURN_TIMEOUT_MS]; cancellation closes
     * the socket.
     */
    private suspend fun synthOnce(
        trimmed: String,
        voice: String,
        r: Float,
        onFailure: (code: Int, date: String?) -> Unit,
    ): ByteArray? {
        val url = edgeWsUrl(edgeConnectionId(), edgeSecMsGec(System.currentTimeMillis() / 1000.0 + clockSkewSec))
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", EDGE_USER_AGENT)
            .header("Origin", EDGE_ORIGIN)
            .header("Cookie", "muid=${edgeMuid()};")
            .build()

        return try {
            withTimeout(EDGE_TURN_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val audio = ByteArrayOutputStream()
                    var finished = false

                    fun complete(bytes: ByteArray?) {
                        if (finished) return
                        finished = true
                        if (cont.isCancelled) return
                        cont.resume(bytes)
                    }

                    val webSocket = client.newWebSocket(request, object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            val now = System.currentTimeMillis()
                            webSocket.send(edgeSpeechConfigFrame(now))
                            webSocket.send(edgeSsmlFrame(edgeConnectionId(), edgeSsml(trimmed, voice, edgeRatePercent(r)), now))
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            when (edgeTextPath(text)) {
                                "turn.end" -> complete(audio.toByteArray().takeIf { it.isNotEmpty() })
                                "audio.metadata", "turn.start", "response" -> Unit
                                else -> {
                                    Log.w(TAG, "unexpected text path: ${edgeTextPath(text)}")
                                    complete(null)
                                }
                            }
                        }

                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                            val parsed = edgeParseBinaryFrame(bytes.toByteArray())
                            if (parsed == null) {
                                Log.w(TAG, "malformed audio frame")
                                complete(null)
                                return
                            }
                            val (headers, payload) = parsed
                            if (headers["Path"] != "audio") {
                                Log.w(TAG, "unexpected binary path: ${headers["Path"]}")
                                complete(null)
                                return
                            }
                            // The terminal frame carries no Content-Type and no data.
                            if (payload.isNotEmpty()) {
                                if (headers["Content-Type"] != "audio/mpeg") {
                                    Log.w(TAG, "unexpected audio content-type: ${headers["Content-Type"]}")
                                    complete(null)
                                    return
                                }
                                audio.write(payload)
                            }
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            Log.w(TAG, "ws failure", t)
                            onFailure(response?.code ?: 0, response?.header("Date"))
                            complete(null)
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            if (!finished) complete(null)
                        }
                    })

                    cont.invokeOnCancellation {
                        try {
                            webSocket.cancel()
                        } catch (e: Exception) {
                            // Already closed; nothing to cancel.
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "edge turn timed out for \"$trimmed\"")
            null
        } catch (e: CancellationException) {
            throw e
        }
    }

    companion object {
        private const val TAG = "EdgeTts"
    }
}
