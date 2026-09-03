package org.yangtse.hearwrite.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/** Longest image edge after downscaling (alice `OCR_MAX_EDGE`). */
const val OCR_MAX_EDGE = 1600

/** JPEG quality of the compressed image (alice `OCR_JPEG_QUALITY`). */
const val OCR_JPEG_QUALITY = 0.82f

/**
 * Longest edge of the decoded crop-source bitmap (选定识别区域 step). The
 * crop UI needs headroom over the 1600 px OCR cap so a small region still
 * carries real text resolution; decode is power-of-two sampled to ≤ this.
 */
const val OCR_CROP_SOURCE_EDGE = 4096

/** Minimum selectable crop side in source pixels (below this it is noise). */
const val OCR_CROP_MIN_SIDE_PX = 96

/**
 * A crop selection in normalized image coordinates — every edge in [0,1],
 * `left < right`, `top < bottom` (enforced by the crop UI gestures).
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Disclaimer surfaced at every OCR entry point (AGENTS.md). */
const val OCR_DISCLAIMER = "AI 识图可能存在误差，请核对识别结果"

/** In-flight progress copy (alice `OCR_PROGRESS_MESSAGES`). */
const val OCR_PROGRESS_COMPRESSING = "处理图片中…"
const val OCR_PROGRESS_RECOGNIZING = "识别中…"

/** Recognition language for the vision OCR pass (alice `OcrLang`). */
enum class OcrLang(val prompt: String) {
    ENGLISH(ENGLISH_OCR_PROMPT),
    CHINESE(CHINESE_OCR_PROMPT),
}

/** Prompts kept verbatim from `alice/src/lib/ocr.ts` (AGENTS.md); sentences join with no separator. */
private val ENGLISH_OCR_PROMPT = listOf(
    "这是一张包含英文单词列表的图片。",
    "请识别图中所有英文单词或词组。",
    "如果单词旁边标注了词性和中文释义，请一并提取，每行格式：单词 | 词性 | 中文释义",
    "如果图中没有词性或释义信息，只输出单词本身。",
    "像 actor / actress 这样的斜杠词组应作为一整行输出，不要拆开。",
    "不要用逗号连接、不要编号、不要输出其他标点或解释。",
).joinToString("")

/** Prompts kept verbatim from `alice/src/lib/ocr.ts` (AGENTS.md); sentences join with no separator. */
private val CHINESE_OCR_PROMPT = listOf(
    "这是一张语文课本或练习册的图片，里面有需要听写的汉字生字和词语。",
    "请识别图中所有汉字生字和词语，每行输出一个：",
    "单个生字只输出该汉字；词语输出完整词语，不要拆成单个汉字。",
    "忽略拼音、英文单词、数字、页码、题号、笔顺示意图和装饰图案。",
    "如果字词旁边标注了拼音或组词，只输出字词本身。",
    "不要输出“生字”“词语”等栏目标题、序号、标点符号或任何解释。",
    "不要把多个字词合并到一行。",
).joinToString("")

private val JSON = Json { ignoreUnknownKeys = true }
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val TAG = "OcrService"

/** Vision request outcome; nothing here throws into the caller (AGENTS.md). */
sealed interface OcrOutcome {
    data class Success(val linesText: String) : OcrOutcome
    data class Error(val message: String) : OcrOutcome
}

/** Result of a raw JSON POST. */
private sealed interface PostResult {
    data class Done(val code: Int, val body: String) : PostResult
    data object Failed : PostResult
}

/**
 * OpenAI-compatible vision OCR (拍照识词): image compression to a ≤1600 px
 * JPEG 0.82 data URL and the `/chat/completions` call with an `image_url`
 * content part. BYOK only — the provider config comes from the DataStore
 * settings (user's own key, never embedded). The reply is filtered by the
 * language-specific OCR extractor (alice `extractWordsFromOcrText` /
 * `extractChineseWordsFromOcrText`) before the standard line parse: English
 * keeps only ASCII word lines — 音标 and Chinese text never survive; Chinese
 * keeps only 汉字 runs — pinyin, English and digits are dropped. Every
 * failure degrades to a Chinese error message; the caller surfaces it with a
 * retry.
 */
class OcrService(
    private val context: Context,
    private val settings: SettingsRepository,
) {

    /** Vision calls are slow; the settings connection test stays snappy. */
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val testClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** The stored BYOK provider config, or null when unset/incomplete. */
    suspend fun config(): OcrProviderConfig? =
        settings.ocrProviderConfig.firstOrNull()?.takeIf { it.isComplete }

    /**
     * Decode a picked/captured image for the 选定识别区域 step: power-of-two
     * sampled so the decoded longest edge is ≤ [OCR_CROP_SOURCE_EDGE], and
     * rotated per EXIF orientation (BitmapFactory ignores EXIF; camera
     * captures carry it). The caller owns the returned bitmap.
     */
    suspend fun decodeCropSource(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver

            // EXIF orientation — read before any pixel decode; some inputs
            // (PNG, odd streams) have none or throw, treat them as normal.
            val orientation = try {
                resolver.openInputStream(uri)?.use { stream ->
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            // Size probe: inJustDecodeBounds decodes no pixels and returns
            // null, but fills outWidth/outHeight — check the fields, not the
            // (null) return value.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val probe = resolver.openInputStream(uri)
            if (probe == null) {
                Log.d(TAG, "decodeCropSource: openInputStream null for $uri")
                return@withContext null
            }
            try {
                BitmapFactory.decodeStream(probe, null, bounds)
            } finally {
                probe.close()
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.d(TAG, "decodeCropSource: cannot decode bounds for $uri")
                return@withContext null
            }

            // inSampleSize keeps the decode buffer bounded (≤ 4096 edge).
            val maxEdge = max(bounds.outWidth, bounds.outHeight)
            val options = BitmapFactory.Options()
            var sample = 1
            while (maxEdge / sample > OCR_CROP_SOURCE_EDGE) sample *= 2
            options.inSampleSize = sample

            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: run {
                Log.d(TAG, "decodeCropSource: decode failed for $uri")
                return@withContext null
            }

            val rotated = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(decoded, 90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(decoded, 180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(decoded, 270f)
                else -> decoded
            }
            if (rotated !== decoded) decoded.recycle()
            rotated
        } catch (e: Exception) {
            Log.d(TAG, "decodeCropSource failed for $uri", e)
            null
        }
    }

    /**
     * Crop [rect] (normalized over [source]) and encode the result to a
     * `data:image/jpeg;base64,…` URL (≤1600 px, JPEG 0.82 — the same output
     * as the old whole-image path). A full-image rect short-circuits to
     * [source] without copying. Does not recycle [source]; the caller owns
     * it. Returns null on failure or a degenerate rect.
     */
    suspend fun cropToDataUrl(source: Bitmap, rect: NormalizedRect): String? =
        withContext(Dispatchers.IO) {
            try {
                if (rect.left >= rect.right || rect.top >= rect.bottom) {
                    Log.d(TAG, "cropToDataUrl: degenerate rect $rect")
                    return@withContext null
                }
                val l = (rect.left * source.width).roundToInt().coerceIn(0, source.width - 1)
                val r = (rect.right * source.width).roundToInt().coerceIn(l + 1, source.width)
                val t = (rect.top * source.height).roundToInt().coerceIn(0, source.height - 1)
                val b = (rect.bottom * source.height).roundToInt().coerceIn(t + 1, source.height)
                val crop = if (l == 0 && t == 0 && r == source.width && b == source.height) {
                    source
                } else {
                    Bitmap.createBitmap(source, l, t, r - l, b - t)
                }
                try {
                    encodeToDataUrl(crop)
                } finally {
                    if (crop !== source) crop.recycle()
                }
            } catch (e: Exception) {
                Log.d(TAG, "cropToDataUrl failed", e)
                null
            }
        }

    /** Rotate [bitmap] by a 90° multiple, returning a new bitmap. */
    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Scale to ≤ [OCR_MAX_EDGE] and JPEG-encode [bitmap] into a data URL. */
    private fun encodeToDataUrl(bitmap: Bitmap): String {
        val scaled = if (max(bitmap.width, bitmap.height) > OCR_MAX_EDGE) {
            val scale = OCR_MAX_EDGE.toFloat() / max(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt(),
                (bitmap.height * scale).roundToInt(),
                true,
            )
        } else {
            bitmap
        }
        try {
            val out = ByteArrayOutputStream()
            scaled.compress(
                Bitmap.CompressFormat.JPEG,
                (OCR_JPEG_QUALITY * 100).roundToInt(),
                out,
            )
            val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            return "data:image/jpeg;base64,$base64"
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    /**
     * POST the image to `/chat/completions`; the reply passes the
     * language-specific extractor ([extractOcrLines]) into word lines.
     * Errors are Chinese messages ready for the UI.
     */
    suspend fun recognize(dataUrl: String, lang: OcrLang): OcrOutcome {
        val cfg = config()
        if (cfg == null) {
            return OcrOutcome.Error("请先在设置中配置 OCR 服务（需自备 API Key）")
        }
        val body = buildJsonObject {
            put("model", cfg.model.trim())
            put("temperature", 0.1)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", dataUrl) }
                        }
                        addJsonObject {
                            put("type", "text")
                            put("text", lang.prompt)
                        }
                    }
                }
            }
        }

        return when (val result = postJson(client, chatCompletionsUrl(cfg.baseUrl), cfg.apiKey, body.toString())) {
            is PostResult.Failed ->
                OcrOutcome.Error("网络请求失败，请检查网络后重试")
            is PostResult.Done -> {
                if (result.code !in 200..299) {
                    val detail = errorDetail(result.body).ifEmpty { result.body.trim().take(240) }
                    OcrOutcome.Error(
                        if (detail.isEmpty()) "视觉识别服务异常" else "视觉识别失败: $detail"
                    )
                } else {
                    val rawText = parseReplyContent(result.body)
                    val lines = extractOcrLines(rawText, lang)
                    if (lines.isEmpty()) {
                        // Alice distinguishes "the model said nothing" from
                        // "the model answered but nothing usable survived".
                        OcrOutcome.Error(ocrEmptyMessage(lang, unparsed = rawText.isNotBlank()))
                    } else {
                        OcrOutcome.Success(lines.joinToString("\n"))
                    }
                }
            }
        }
    }

    /**
     * Verify a candidate provider config with a minimal text-only
     * chat/completions request (alice `testOcrConfig`). Returns null on
     * success or a Chinese error message.
     */
    suspend fun testConnection(cfg: OcrProviderConfig): String? {
        val body = buildJsonObject {
            put("model", cfg.model.trim())
            put("temperature", 0)
            put("max_tokens", 1)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", "hi")
                }
            }
        }
        return when (
            val result = postJson(testClient, chatCompletionsUrl(cfg.baseUrl), cfg.apiKey, body.toString())
        ) {
            is PostResult.Failed -> "网络请求失败，请检查 URL 与网络"
            is PostResult.Done -> {
                if (result.code in 200..299) {
                    null
                } else {
                    val detail = errorDetail(result.body)
                        .ifEmpty { result.body.trim() }
                        .take(160)
                    val hint = if (detail.isEmpty()) "" else ": $detail"
                    when (result.code) {
                        401, 403 -> "认证失败（${result.code}）$hint"
                        404 -> "未找到接口（404），请检查 URL$hint"
                        else -> "请求失败（${result.code}）$hint"
                    }
                }
            }
        }
    }

    private suspend fun postJson(
        http: OkHttpClient,
        url: String,
        apiKey: String,
        jsonBody: String,
    ): PostResult {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Content-Type", "application/json")
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
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
                        cont.resume(PostResult.Failed)
                    } catch (e2: Exception) {
                        // Already resumed; ignore.
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isCancelled) return
                    try {
                        cont.resume(response.use { res ->
                            PostResult.Done(res.code, res.body?.string() ?: "")
                        })
                    } catch (e: Exception) {
                        cont.resume(PostResult.Failed)
                    }
                }
            })
        }
    }
}

/**
 * Extract `choices[0].message.content` from a chat/completions reply, trimmed
 * ("" when absent/unparseable).
 */
internal fun parseReplyContent(body: String): String = try {
    val root = JSON.parseToJsonElement(body).jsonObject
    val content = root["choices"]
        ?.jsonArray?.firstOrNull()?.jsonObject
        ?.get("message")?.jsonObject
        ?.get("content")?.jsonPrimitive?.contentOrNull
    content?.trim() ?: ""
} catch (e: Exception) {
    ""
}

/** Provider error message from an error body (`error.message`), else "". */
internal fun errorDetail(body: String): String = try {
    val root = JSON.parseToJsonElement(body).jsonObject
    val message = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
    message?.trim() ?: ""
} catch (e: Exception) {
    ""
}

/**
 * Route a vision reply through the extractor of the recognition language
 * (alice picks `extractChineseWordsFromOcrText` vs `extractWordsFromOcrText`
 * the same way). English keeps only English word lines — 音标 and Chinese
 * never reach the list; Chinese keeps only 汉字 runs — pinyin, English and
 * digits are dropped.
 */
internal fun extractOcrLines(rawText: String, lang: OcrLang): List<String> = when (lang) {
    OcrLang.ENGLISH -> extractEnglishOcrLines(rawText)
    OcrLang.CHINESE -> extractChineseOcrLines(rawText)
}

/** Fenced ``` ``` block wherever it sits in the reply (alice `normalizeOcrLines`). */
private val FENCED_BLOCK_RE = Regex("```[\\s\\S]*?```")
private val FENCE_HEAD_RE = Regex("^```\\w*\\n?")
private val FENCE_TAIL_RE = Regex("\\n?```$")
private val CRLF_RE = Regex("\\r\\n?")

/**
 * Strip markdown fences, normalize newlines, and split into non-empty
 * trimmed lines (alice `normalizeOcrLines`). Fence markers are removed from
 * every fenced block; the block content itself survives.
 */
internal fun normalizeOcrLines(rawText: String): List<String> =
    FENCED_BLOCK_RE.replace(rawText) { block ->
        block.value.replace(FENCE_HEAD_RE, "").replace(FENCE_TAIL_RE, "")
    }
        .replace(CRLF_RE, "\n")
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

/**
 * Real dictation phrases are almost never longer than this many
 * space-separated tokens ("ice cream", "look forward to", "actor / actress");
 * longer runs are treated as a failed one-line dump from the vision model
 * (alice `MAX_PHRASE_TOKENS`).
 */
private const val MAX_PHRASE_TOKENS = 4

/** Strip list markers, wrapping quotes, and trailing punctuation (alice `cleanToken`). */
private val LIST_MARKER_PREFIX_RE = Regex("^[\\d.)\\-•*、]+\\s*")
private val WRAPPING_QUOTES_RE = Regex("^[\"'`“”‘’]+|[\"'`“”‘’]+\$")
private val TRAILING_SENTENCE_PUNCT_RE = Regex("[.。:：]+\$")
private val COMMA_SEP_RE = Regex("[,，;；、]+")
private val WHITESPACE_RE = Regex("\\s+")

/**
 * English word/phrase shape: ASCII letters only, with `'` `/` `-` and spaces
 * (alice `WORD_RE`). IPA symbols and Chinese characters fail it, which is
 * how 音标/中文 are kept out of English scans.
 */
private val WORD_RE = Regex("^[a-zA-Z][a-zA-Z'/\\-\\s]*$")

private fun cleanToken(s: String): String = s
    .replace(LIST_MARKER_PREFIX_RE, "")
    .replace(WRAPPING_QUOTES_RE, "")
    .replace(TRAILING_SENTENCE_PUNCT_RE, "")
    .trim()

/**
 * The leading English token run of [tokens] — the headword phrase. Empty
 * when the row does not begin with English, so a row that opens with Chinese
 * (or pure 音标) is not an English entry at all. A lone `/` keeps the run
 * going only while it is flanked by word tokens, so spaced slash phrases
 * (`actor / actress`) survive whole — the format the OCR prompt asks for —
 * while a trailing `/` or annotation tokens still end the run.
 */
private fun leadingEnglishRun(tokens: List<String>): List<String> {
    val run = mutableListOf<String>()
    var i = 0
    while (i < tokens.size) {
        val token = tokens[i]
        if (WORD_RE.matches(token)) {
            run += token
            i++
        } else if (token == "/" && run.isNotEmpty() && i + 1 < tokens.size && WORD_RE.matches(tokens[i + 1])) {
            run += token
            i++
        } else {
            break
        }
    }
    return run
}

/**
 * English tokens that start a cleaned token run — the headword phrase.
 * Null when the run does not start with English, so a row that begins with
 * Chinese (or pure 音标) is not an English entry at all.
 */
private fun englishPhrase(tokenRun: String): String? {
    val phrase = leadingEnglishRun(tokenRun.split(WHITESPACE_RE).filter { it.isNotEmpty() })
        .take(MAX_PHRASE_TOKENS)
    return phrase.takeIf { it.isNotEmpty() }?.joinToString(" ")
}

/**
 * Candidate words from one cleaned comma-part. Pure-English runs keep
 * alice's behavior: the whole phrase up to [MAX_PHRASE_TOKENS] tokens,
 * longer dumps flattened into single tokens. Mixed runs — the word followed
 * by 音标/词性/中文 (the vision model echoing a textbook row instead of the
 * `word | pos | meaning` format) — yield the leading English phrase; the
 * trailing annotations are noise and never become entries.
 */
private fun englishCandidates(candidate: String): List<String> {
    val tokens = candidate.split(WHITESPACE_RE).filter { it.isNotEmpty() }
    val leading = leadingEnglishRun(tokens)
    if (leading.isEmpty()) return emptyList()
    if (leading.size == tokens.size) {
        return if (leading.size <= MAX_PHRASE_TOKENS) {
            listOf(candidate)
        } else {
            // Over-long pure dump flattened to single tokens; lone "/"
            // separators are noise and never become entries.
            tokens.filter { WORD_RE.matches(it) }
        }
    }
    return if (leading.size <= MAX_PHRASE_TOKENS) listOf(leading.joinToString(" ")) else leading
}

/**
 * English-mode extraction (alice `extractWordsFromOcrText` + headword
 * salvage): per non-empty line, a `|`-enriched entry is kept with its meta
 * columns; a 音标 run trailing the headword in the word column is dropped.
 * Plain lines are comma/semicolon-split; each part yields English only —
 * either the whole phrase (≤ [MAX_PHRASE_TOKENS] tokens), individual tokens
 * of an over-long dump, or the leading English phrase of a row that also
 * carries 音标/词性/中文. Chinese text never becomes an entry. Dedupe is
 * case-insensitive, first occurrence wins.
 */
internal fun extractEnglishOcrLines(rawText: String): List<String> {
    val seen = mutableSetOf<String>()
    val lines = mutableListOf<String>()
    for (line in normalizeOcrLines(rawText)) {
        val pipeIdx = line.indexOf('|')
        if (pipeIdx != -1) {
            // Enriched entry: clean & validate the word part, preserve meta.
            val wordPart = englishPhrase(cleanToken(line.substring(0, pipeIdx)))
            if (wordPart == null || !seen.add(wordPart.lowercase())) continue
            val metaParts = line.substring(pipeIdx + 1)
                .split('|')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            lines += (listOf(wordPart) + metaParts).joinToString(" | ")
        } else {
            // Plain line: split by commas/semicolons (the vision model may
            // ignore one-per-line).
            for (candidate in line.split(COMMA_SEP_RE).map(::cleanToken).filter { it.isNotEmpty() }) {
                for (word in englishCandidates(candidate)) {
                    if (!seen.add(word.lowercase())) continue
                    lines += word
                }
            }
        }
    }
    return lines
}

/** Continuous 汉字 run: pinyin/English/digits/punctuation split runs apart. */
private val CJK_RUN_RE = Regex("[\\u4e00-\\u9fff]+")
private val CJK_CHAR_RE = Regex("[\\u4e00-\\u9fff]")
/** Fullwidth and ASCII pipes delimit annotation columns on one line. */
private val OCR_PIPE_RE = Regex("[|｜]")

/** Textbook-page column names/section headers the model emits as entries. */
private val CJK_STOPWORDS = setOf(
    "生字", "词语", "拼音", "识字", "生字表", "词语表", "识字表", "写字表",
    "我会认", "我会写", "读一读", "写一写", "认一认", "练一练", "语文园地", "日积月累",
)

/**
 * Chinese-mode extraction (alice `extractChineseWordsFromOcrText`): keeps
 * only runs of 汉字 — latin (拼音/English), digits and punctuation are
 * dropped. For `|`-delimited meta lines only the first segment that holds a
 * 汉字 and is not a column label is taken (later columns are annotations,
 * not entries). Lines ignoring "one per line" split naturally into separate
 * runs. 生字/词语 headers are skipped; dedupe, first occurrence wins.
 */
internal fun extractChineseOcrLines(rawText: String): List<String> {
    val seen = mutableSetOf<String>()
    val words = mutableListOf<String>()
    for (line in normalizeOcrLines(rawText)) {
        // Headword segment: first pipe segment holding a 汉字 that is not a
        // column label (e.g. `词语 | 月亮` → 月亮).
        val head = line
            .split(OCR_PIPE_RE)
            .firstOrNull { seg -> CJK_CHAR_RE.containsMatchIn(seg) && seg.trim() !in CJK_STOPWORDS }
            ?: continue
        for (run in CJK_RUN_RE.findAll(head)) {
            val word = run.value
            if (word in CJK_STOPWORDS || !seen.add(word)) continue
            words += word
        }
    }
    return words
}

/**
 * OCR empty-result messages per recognition language (alice
 * `OCR_OUTCOME_MESSAGES`): with `unparsed` = the model answered but nothing
 * usable survived the language filter (e.g. an English page full of 音标-only
 * rows); without it = no recognizable reply at all.
 */
internal fun ocrEmptyMessage(lang: OcrLang, unparsed: Boolean): String = when {
    lang == OcrLang.ENGLISH && unparsed -> "未能从识别结果中提取英文单词，请换一张更清晰的单词列表再试"
    lang == OcrLang.ENGLISH -> "未识别到英文单词，请换一张更清晰的图片再试"
    lang == OcrLang.CHINESE && unparsed -> "未能从识别结果中提取中文生字或词语，请换一张更清晰的生字/词语表再试"
    else -> "未识别到中文生字或词语，请换一张更清晰的图片再试"
}
