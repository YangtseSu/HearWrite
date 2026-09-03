package org.yangtse.hearwrite.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Reject responses smaller than this — youdao error pages/empty audio. */
internal const val MIN_AUDIO_BYTES = 256

private val CJK_TEXT_RE = Regex("[\\u4e00-\\u9fff]")

/**
 * Mobile-browser UA: the desktop UA gets HTML instead of audio (AGENTS.md).
 * Same header value as alice (`DOWNLOAD_HEADERS`).
 */
private val MOBILE_USER_AGENT =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15"

/** Uppercase hex nibbles for the percent encoder. */
private val HEX = "0123456789ABCDEF".toCharArray()

/** encodeURIComponent-equivalent percent-encoding (alice uses it for URLs and cache file names). */
internal fun uriComponentEncode(input: String): String {
    val out = StringBuilder(input.length + input.length / 2)
    for (byte in input.toByteArray(Charsets.UTF_8)) {
        val b = byte.toInt() and 0xFF
        if (b < 0x80 && b.toChar() in UNRESERVED) {
            out.append(b.toChar())
        } else {
            out.append('%').append(HEX[b ushr 4]).append(HEX[b and 0xF])
        }
    }
    return out.toString()
}

private val UNRESERVED =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

/** True when [text] contains any CJK ideograph (voice-language decision). */
internal fun isCjkText(text: String): Boolean = CJK_TEXT_RE.containsMatchIn(text)

/**
 * Youdao dict-voice URL forms (AGENTS.md "TTS priority chain"): CJK goes to
 * the `le=zh` voice (the US/UK `type=2/1` voices cannot speak Chinese — 500 /
 * silent fragment), English tries US (`type=2`) then UK (`type=1`).
 */
internal fun youdaoUrls(text: String, lang: String): List<String> {
    val trimmed = text.trim()
    val q = uriComponentEncode(trimmed)
    return if (isCjkText(trimmed)) {
        listOf("https://dict.youdao.com/dictvoice?audio=$q&le=zh")
    } else {
        listOf(
            "https://dict.youdao.com/dictvoice?audio=$q&type=2",
            "https://dict.youdao.com/dictvoice?audio=$q&type=1",
        )
    }
}

/**
 * Single-flight key for a download (text + lang, AGENTS.md) — two concurrent
 * requests for the same voice never duplicate a download.
 */
internal fun ttsCacheKey(text: String, lang: String): String =
    "${lang.lowercase()}|${text.trim().lowercase()}"

/**
 * Cache file name under `cacheDir/tts/`. Lowercased, percent-encoded text
 * (`%` → `_`) plus a `.zh` language suffix on Chinese clips, mirroring
 * alice: `.zh.mp3` vs `.mp3`. `"unknown"` when the text encodes to nothing.
 */
internal fun ttsCacheFileName(text: String, lang: String): String {
    val base = uriComponentEncode(text.trim().lowercase()).replace('%', '_').ifEmpty { "unknown" }
    val zh = lang.startsWith("zh", ignoreCase = true)
    return if (zh) "$base.zh.mp3" else "$base.mp3"
}

/**
 * Youdao dict-voice MP3 downloader with a disk cache and per-text single
 * flight (AGENTS.md). Downloading happens only on an internal scope — the
 * playback chain never blocks on it: [prefetch] warms the cache in the
 * background and [cachedClip] returns ready audio only. Every failure path
 * degrades to "no clip" (the chain falls back to system TTS); nothing here
 * ever throws into the caller.
 */
class YoudaoTts(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val inFlight = ConcurrentHashMap<String, Deferred<Unit>>()

    private val cacheDir: File
        get() = File(context.cacheDir, "tts")

    /** The cached clip for [text]/[lang], or null when absent/too small. */
    fun cachedClip(text: String, lang: String): File? {
        val file = clipFile(text, lang)
        return if (file.isFile && file.length() >= MIN_AUDIO_BYTES) file else null
    }

    /**
     * Background-warm [text]/[lang]: returns immediately when a valid clip
     * is already cached or a download is in flight (awaiting it); otherwise
     * starts a download on the internal scope and waits for it. The await
     * is cancellable — cancelling never aborts the shared download, it only
     * stops this caller from waiting.
     */
    suspend fun prefetch(text: String, lang: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        cachedClip(trimmed, lang)?.let { return true }

        val key = ttsCacheKey(trimmed, lang)
        val existing = inFlight[key]
        if (existing != null) {
            try {
                existing.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The other download failed; fall through to retry once.
            }
            return cachedClip(trimmed, lang) != null
        }

        val job = scope.async { download(trimmed, lang) }
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
            return cachedClip(trimmed, lang) != null
        }
        try {
            job.await()
        } finally {
            inFlight.remove(key, job)
        }
        return cachedClip(trimmed, lang) != null
    }

    /** Try every URL form in order; first valid response wins. */
    private suspend fun download(text: String, lang: String) {
        for (url in youdaoUrls(text, lang)) {
            try {
                val bytes = httpGetBytes(url) ?: continue
                if (bytes.size < MIN_AUDIO_BYTES) continue
                val dest = clipFile(text, lang)
                // Direct write: single-flight per file name, and a crash can
                // only leave a short file that fails the size guard and gets
                // re-downloaded next time.
                dest.parentFile?.mkdirs()
                dest.writeBytes(bytes)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "download failed for \"$text\": $url", e)
            }
        }
    }

    /** GET [url] returning the body bytes, or null on any failure. */
    private suspend fun httpGetBytes(url: String): ByteArray? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", MOBILE_USER_AGENT)
            .build()
        return suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation {
                try {
                    call.cancel()
                } catch (e: Exception) {
                    // Already finished; nothing to cancel.
                }
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // Cancellation surfaces here too (call.cancel) — the
                    // continuation may already be gone; swallow.
                    safeResume(cont, null)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { res ->
                        if (!res.isSuccessful) {
                            safeResume(cont, null)
                            return
                        }
                        try {
                            safeResume(cont, res.body?.bytes())
                        } catch (e: Exception) {
                            Log.w(TAG, "body read failed: $url", e)
                            safeResume(cont, null)
                        }
                    }
                }
            })
        }
    }

    /** Resume only when the awaiting coroutine is still alive (not cancelled). */
    private fun safeResume(cont: kotlinx.coroutines.CancellableContinuation<ByteArray?>, value: ByteArray?) {
        if (cont.isCancelled) return
        try {
            cont.resume(value)
        } catch (e: IllegalStateException) {
            // Cancelled concurrently between the check and the resume.
        }
    }

    private fun clipFile(text: String, lang: String): File =
        File(cacheDir, ttsCacheFileName(text, lang))

    companion object {
        private const val TAG = "YoudaoTts"
    }
}
