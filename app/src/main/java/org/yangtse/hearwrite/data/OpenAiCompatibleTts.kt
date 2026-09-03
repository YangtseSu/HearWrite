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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.yangtse.hearwrite.domain.DEFAULT_SPEECH_RATE

/** Failure with a Chinese message ready for the settings 测试并试听 result. */
class TtsProviderException(message: String) : Exception(message)

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/**
 * OpenAI-compatible TTS client (AGENTS.md "TTS priority chain" link 3,
 * alice `tts.ts` provider half). Two wire shapes picked by [TtsApiKind]:
 * `speech` → `POST {base}/audio/speech` binary audio; `chat` → base64 audio
 * in `choices[0].message.audio.data` (小米 MiMo). Clips are disk-cached
 * under `cacheDir/tts/` keyed by a hash of api|model|voice|format|rate +
 * text, with a per-clip single flight.
 *
 * Config and speech rate follow the DataStore settings repository (the
 * config the user saves in Settings, the rate slider) — the playback chain
 * never blocks on a download: [cachedClip] returns ready audio only and
 * [prefetch] warms the cache in the background. [generateClip] is the
 * explicit settings-test path and throws [TtsProviderException] with a
 * Chinese message instead of degrading silently.
 *
 * The rate is snapshotted once per generation and drives the request body's
 * `speed`, the clip file name and the single-flight key together — a 语速
 * change mid-request can never write audio synthesized at the old speed
 * under the new rate's hash.
 */
class OpenAiCompatibleTts(
    private val context: Context,
    settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Per-clip generation single flight (hash:text key → shared download). */
    private val inFlight = ConcurrentHashMap<String, Deferred<Result<File>>>()

    @Volatile
    private var storedConfig: TtsProviderConfig? = null

    /** Speech-rate ×10 feeds the clip hash; follows the persisted 语速. */
    @Volatile
    private var rate: Float = DEFAULT_SPEECH_RATE

    init {
        scope.launch { settings.ttsProviderConfig.collect { storedConfig = it } }
        scope.launch { settings.speechRate.collect { rate = it } }
    }

    private val cacheDir: File
        get() = File(context.cacheDir, "tts")

    /** The stored provider config, or null when unset/incomplete. */
    fun activeConfig(): TtsProviderConfig? = storedConfig?.takeIf { it.isComplete }

    /** The ready cached clip for [text], or null when absent/too small/no config. */
    fun cachedClip(text: String): File? {
        val cfg = activeConfig() ?: return null
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val file = clipFile(trimmed, cfg, rate)
        return if (file.isFile && file.length() >= MIN_AUDIO_BYTES) file else null
    }

    /**
     * Background-warm [text]: returns immediately when a valid clip is
     * cached or a generation is in flight (awaiting it); otherwise starts a
     * download on the internal scope and waits for it. The await is
     * cancellable — cancelling never aborts the shared download (YoudaoTts
     * pattern). Every failure degrades to false; never throws.
     */
    suspend fun prefetch(text: String): Boolean {
        val cfg = activeConfig() ?: return false
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        cachedClip(trimmed)?.let { return true }
        val result = flightResult(trimmed, cfg, rate)
        if (result.isFailure) {
            Log.w(TAG, "provider download failed for \"$trimmed\"", result.exceptionOrNull())
        }
        return result.isSuccess
    }

    /**
     * Generate [text] with the given (possibly unsaved) [cfg] and cache the
     * clip — the settings 测试并试听 path (alice `downloadProviderAudio`).
     * Joins the same per-clip single flight as playback prefetch, so a
     * background generation for the same text/config/rate is never
     * duplicated. Throws [TtsProviderException] with a Chinese message on
     * any failure; the settings test surfaces it directly.
     */
    suspend fun generateClip(text: String, cfg: TtsProviderConfig): File {
        val trimmed = text.trim()
        val result = flightResult(trimmed, cfg, rate)
        return result.getOrThrow()
    }

    /** Await a shared generation, rethrowing the awaiter's cancellation. */
    private suspend fun awaitFlight(flight: Deferred<Result<File>>): Result<File> = try {
        flight.await()
    } catch (e: CancellationException) {
        throw e
    }

    /** A clip file that actually holds audio (mirror of [cachedClip]). */
    private fun isValidClip(file: File): Boolean =
        file.isFile && file.length() >= MIN_AUDIO_BYTES

    /**
     * Fetch-and-cache under the per-clip single flight (hash:text key): joins
     * an in-flight generation instead of duplicating the request (settings
     * 试听 must not double-charge a background prefetch). A failed shared
     * winner is retried once with our own flight. Cancellation of the
     * awaiter rethrows; a raced-away job completes on its own.
     */
    private suspend fun flightResult(trimmed: String, cfg: TtsProviderConfig, r: Float): Result<File> {
        val key = ttsProviderFlightKey(trimmed, cfg, r)
        val existing = inFlight[key]
        if (existing != null) {
            val file = awaitFlight(existing).getOrNull()?.takeIf(::isValidClip)
            if (file != null) return Result.success(file)
            // The shared attempt failed; fall through to one retry of our own.
        }
        val job = scope.async {
            try {
                Result.success(fetchAndWrite(trimmed, cfg, r))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        val winner = inFlight.putIfAbsent(key, job)
        if (winner != null) {
            job.cancel()
            val file = awaitFlight(winner).getOrNull()?.takeIf(::isValidClip)
            return if (file != null) {
                Result.success(file)
            } else {
                Result.failure(TtsProviderException("音频生成失败，请检查接口地址、密钥和模型"))
            }
        }
        val result = try {
            awaitFlight(job)
        } finally {
            inFlight.remove(key, job)
        }
        val file = result.getOrNull()?.takeIf(::isValidClip)
        return if (file != null) {
            Result.success(file)
        } else {
            Result.failure(
                result.exceptionOrNull() ?: TtsProviderException("音频数据无效，请检查接口地址与模型"),
            )
        }
    }

    /**
     * One generation at a fixed rate [r]: the same snapshot feeds the
     * request body's speed, the destination file name and the in-flight key.
     * Throws [TtsProviderException] with a Chinese message on any failure.
     */
    private suspend fun fetchAndWrite(trimmed: String, cfg: TtsProviderConfig, r: Float): File {
        val bytes = try {
            fetchBytes(trimmed, cfg, r)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TtsProviderException) {
            throw e
        } catch (e: Exception) {
            throw TtsProviderException("网络请求失败，请检查 URL 与网络")
        }
        if (bytes.size < MIN_AUDIO_BYTES) {
            throw TtsProviderException("音频数据无效，请检查接口地址与模型")
        }
        val dest = clipFile(trimmed, cfg, r)
        try {
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
        } catch (e: Exception) {
            throw TtsProviderException("音频缓存写入失败")
        }
        return dest
    }

    /**
     * POST [text] through the configured wire shape and return the audio
     * bytes. HTTP/parse failures throw [TtsProviderException]; non-OK
     * responses surface `error.message` when present, else `HTTP <status>`
     * (AGENTS.md).
     */
    private suspend fun fetchBytes(text: String, cfg: TtsProviderConfig, r: Float): ByteArray {
        val chat = cfg.api == TtsApiKind.CHAT
        val url = if (chat) chatCompletionsUrl(cfg.baseUrl) else speechUrl(cfg.baseUrl)
        val body = if (chat) ttsChatRequestBody(cfg, text) else ttsSpeechRequestBody(cfg, text, r)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey.trim()}")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return when (val result = postBytes(request)) {
            is TtsPostResult.Failed -> throw TtsProviderException("网络请求失败，请检查 URL 与网络")
            is TtsPostResult.Done -> {
                if (result.code !in 200..299) {
                    throw TtsProviderException(
                        ttsHttpErrorMessage(result.code, result.bytes.toString(Charsets.UTF_8)),
                    )
                }
                if (chat) {
                    val audioData = ttsProviderAudioData(result.bytes.toString(Charsets.UTF_8))
                    if (audioData.isEmpty()) {
                        throw TtsProviderException("响应中没有音频数据")
                    }
                    val decoded = ttsProviderBase64Decode(audioData)
                    if (decoded.isEmpty()) {
                        throw TtsProviderException("音频数据解码失败")
                    }
                    decoded
                } else {
                    result.bytes
                }
            }
        }
    }

    private suspend fun postBytes(request: Request): TtsPostResult =
        suspendCancellableCoroutine { cont ->
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
                    if (cont.isCancelled) return
                    try {
                        cont.resume(TtsPostResult.Failed)
                    } catch (e2: Exception) {
                        // Already resumed; ignore.
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    // Read and close the body unconditionally — a response
                    // that raced a cancellation must not leak its stream.
                    try {
                        response.use { res ->
                            val done = TtsPostResult.Done(res.code, res.body.bytes())
                            if (!cont.isCancelled) {
                                cont.resume(done)
                            }
                        }
                    } catch (e: Exception) {
                        if (!cont.isCancelled) {
                            try {
                                cont.resume(TtsPostResult.Failed)
                            } catch (e2: Exception) {
                                // Already resumed; ignore.
                            }
                        }
                    }
                }
            })
        }

    private fun clipFile(text: String, cfg: TtsProviderConfig, r: Float): File =
        File(cacheDir, ttsProviderClipFileName(text, cfg, r))

    private sealed interface TtsPostResult {
        data class Done(val code: Int, val bytes: ByteArray) : TtsPostResult
        data object Failed : TtsPostResult
    }

    companion object {
        private const val TAG = "OpenAiCompatibleTts"
    }
}
