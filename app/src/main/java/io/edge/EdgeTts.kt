@file:Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")

/*
 * EdgeTts.kt — 单文件 Microsoft Edge 在线 TTS 客户端(Kotlin / Android / Gradle)
 * =============================================================================
 * 协议依据: edge-tts v7.2.8 逆向 + 2026-09 对真实服务抓包验证(见仓库 PROTOCOL_SPEC.md)。
 *
 * 依赖(调用方在模块 build.gradle.kts 提供;本文件不含 Gradle 配置):
 *   implementation("com.squareup.okhttp3:okhttp:4.12.0")          // 含 okio
 *   implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
 *   // org.json: Android SDK 自带;纯 JVM 测试加 implementation("org.json:json:20240303")
 *
 * 本文件不含 Android 专属 API(仅 java.* + okhttp/okio + coroutines),可拷入任意模块直接编译。
 * 无 minSdk 限制(时间戳用 SimpleDateFormat,不用 java.time)。
 *
 * ----------------------------------------------------------------------------
 * 用法(完整示例见文件底部):
 *
 *   val tts = EdgeTts()                                    // 每个实例同一时刻跑一个合成流
 *
 *   // 1) 流式: 音频 + 词/句边界(Compose 里包在 LaunchedEffect / rememberCoroutineScope)
 *   tts.speakFlow("你好,世界", EdgeTts.Config(voice = "zh-CN-XiaoxiaoNeural"))
 *      .collect { c -> when (c) {
 *          is EdgeTts.Chunk.Audio    -> fileOut.write(c.data.toByteArray()) // MP3
 *          is EdgeTts.Chunk.Boundary -> log("${c.offset}ns ${c.text}")
 *      } }
 *
 *   // 2) 一次性合成(挂起,返回完整 MP3)
 *   val mp3: ByteArray = tts.synthesize("Hello")
 *
 *   // 3) 回调式(任意线程发起;回调在后台线程,勿阻塞)
 *   tts.streamAsync("Hello", cfg, object : EdgeTts.Listener {
 *       override fun onChunk(c: EdgeTts.Chunk) { ... }
 *       override fun onError(e: Throwable) { ... }
 *       override fun onCompleted() { ... }
 *   })
 *
 *   // 4) 语音列表(挂起环境包 withContext(Dispatchers.IO))
 *   val voices = EdgeTts().listVoices()
 * =============================================================================
 */

/*
 * Vendored into HearWrite from the standalone Kotlin client at
 * github.com/YangtseSu/edge-tts-kotlin (EdgeTts.kt, the author's rewrite
 * of rany2/edge-tts, LGPLv3). HearWrite is GPL-3.0, so this LGPLv3 file is
 * combined and distributed under GPL-3.0 terms with this notice retained.
 *
 * LGPLv3 — Copyright (c) 2025- rany <rany@riseup.net> and contributors
 * (upstream rany2/edge-tts). This file is free software: you can
 * redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 * See the upstream LICENSE (LGPLv3, with srt_composer.py MIT) for the full
 * text. Sync source of truth: github.com/YangtseSu/edge-tts-kotlin.
 */
package io.edge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.Proxy
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Microsoft Edge 在线 TTS 客户端(纯 JVM/OkHttp 实现,Android 通用)。
 *
 * 线程模型:
 *  - 每个合成任务内部用一条专用线程串行执行「切块 → 建连 → speech.config → ssml → 收流」
 *    循环;每个文本块(chunk)使用一条独立 WebSocket 连接(协议要求)。
 *  - [Listener] / [Chunk] 回调运行在 OkHttp 的 WebSocket 线程上,勿在其中做耗时操作;
 *    [Chunk.Audio] 持有不可变 [ByteString],可安全跨线程转交播放器/文件。
 *  - 同一实例同一时刻只允许一个合成流(重复发起会收到错误);多实例互不影响。
 *  - [cancel] 幂等,任意线程可调。
 */
class EdgeTts {
    private val client: OkHttpClient

    constructor() {
        client = defaultClient(null)
    }

    constructor(proxy: Proxy) {
        client = defaultClient(proxy)
    }

    constructor(customClient: OkHttpClient) {
        client = customClient
    }

    /* ================================================================== */
    /* 1. 对外数据模型                                                     */
    /* ================================================================== */

    sealed class Chunk {
        /** MP3 音频片段;按序拼接即完整 MP3 流。播放器需要 byte[] 时用 [data.toByteArray] */
        data class Audio(val data: ByteString) : Chunk()

        /** 词/句边界;offset/duration 单位为 100ns 刻度(1s = 10_000_000) */
        data class Boundary(
            val type: String,       // "WordBoundary" | "SentenceBoundary"
            val offset: Long,       // 已含跨 chunk 字节补偿
            val duration: Long,
            val text: String,       // 已做 XML 反转义
        ) : Chunk()
    }

    interface Listener {
        fun onChunk(chunk: Chunk)
        fun onError(error: Throwable)
        fun onCompleted()
    }

    /** 合成参数。rate/volume 形如 "+0%"/"-50%";pitch 形如 "+0Hz"/"-50Hz" */
    class Config(
        var voice: String = "en-US-EmmaMultilingualNeural",
        var rate: String = "+0%",
        var volume: String = "+0%",
        var pitch: String = "+0Hz",
        var wordBoundary: Boolean = false, // false = 句子边界(与 edge-tts 默认一致)
    )

    open class EdgeTtsException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    class NoAudioReceivedException(message: String = "no audio received") :
        EdgeTtsException(message)

    class SkewAdjustmentException(message: String, cause: Throwable? = null) :
        EdgeTtsException(message, cause)

    /** 语音列表条目 */
    data class Voice(
        val name: String,
        val shortName: String,
        val gender: String,
        val locale: String,
        val status: String,
    )

    /* ================================================================== */
    /* 2. 常量(edge-tts v7.2.8;若服务端 403,先更新 CHROMIUM_FULL)       */
    /* ================================================================== */

    private object C {
        const val BASE = "speech.platform.bing.com/consumer/speech/synthesize/readaloud"
        const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        const val WSS_PATH = "wss://$BASE/edge/v1"
        const val VOICE_LIST = "https://$BASE/voices/list?trustedclienttoken=$TRUSTED_TOKEN"
        // 微软滚动更新版本号;若 403,抓最新 Edge 的版本同步更新 UA / Sec-CH-UA /
        // Sec-MS-GEC-Version(见 PROTOCOL_SPEC.md §11)。
        const val CHROMIUM_FULL = "143.0.3650.75"
        const val CHROMIUM_MAJOR = "143"
        const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_FULL"
        const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"
        const val WIN_EPOCH = 11_644_473_600L      // 1601-01-01 至 1970-01-01 的秒数
        const val TICKS_PER_SECOND = 10_000_000L   // 1s = 100ns 刻度数
        const val MP3_BITRATE_BPS = 48_000L        // CBR 码率(字节 → 刻度换算)
        const val MAX_CHUNK_BYTES = 4096
        const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$CHROMIUM_MAJOR.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR.0.0.0"
    }

    /* ================================================================== */
    /* 3. 时间 / DRM 令牌 / 时钟矫正                                      */
    /* ================================================================== */

    @Volatile
    private var clockSkewSeconds = 0.0

    /** JS 风格时间串: "Thu Sep 04 2026 12:00:00 GMT+0000 (Coordinated Universal Time)" */
    private fun dateToString(): String {
        val fmt = SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.ENGLISH,
        )
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun nowUnixSeconds(): Double = System.currentTimeMillis() / 1000.0 + clockSkewSeconds

    /**
     * Sec-MS-GEC: unix秒 → Windows文件时间(加 WIN_EPOCH) → 向下取整到 5 分钟窗口 → ×1e7
     * → "无小数ticks + TrustedClientToken" 拼串 → SHA256 大写 hex。
     * 服务端只接受当前 5 分钟窗口;本机时钟偏差大会 403。
     */
    private fun secMsGec(): String {
        var t = nowUnixSeconds() + C.WIN_EPOCH
        t -= t % 300.0
        val ticks100ns = Math.round(t * 1e7)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((ticks100ns.toString() + C.TRUSTED_TOKEN).toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun muid(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02X".format(it) }
    }

    /** 解析 RFC 1123 Date 头,返回 (server − 本地) 秒偏差;失败抛 [SkewAdjustmentException] */
    private fun deltaFromDateHeader(dateHeader: String?): Double {
        if (dateHeader == null) throw SkewAdjustmentException("no Date header in response")
        return try {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
            fmt.parse(dateHeader)!!.time / 1000.0 - nowUnixSeconds()
        } catch (t: Throwable) {
            throw SkewAdjustmentException("cannot parse server Date: $dateHeader", t)
        }
    }

    /* ================================================================== */
    /* 4. HTTP: 语音列表                                                   */
    /* ================================================================== */

    /** 同步拉取语音列表(含 403 → 时钟矫正 → 一次重试)。协程里用 withContext(IO) 包裹。 */
    fun listVoices(): List<Voice> {
        var retried = false
        while (true) {
            val url = C.VOICE_LIST +
                "&Sec-MS-GEC=${secMsGec()}&Sec-MS-GEC-Version=${C.SEC_MS_GEC_VERSION}"
            client.newCall(voiceRequest(url)).execute().use { resp ->
                if (resp.code == 403 && !retried) {
                    clockSkewSeconds += deltaFromDateHeader(resp.header("Date"))
                    retried = true
                    return@use // 携修正时钟重试
                }
                if (!resp.isSuccessful) throw EdgeTtsException("listVoices HTTP ${resp.code}")
                val body = resp.body?.string() ?: throw EdgeTtsException("empty voice list body")
                val arr = JSONArray(body)
                return buildList {
                    for (i in 0 until arr.length()) {
                        val v = arr.getJSONObject(i)
                        add(
                            Voice(
                                name = v.optString("Name"),
                                shortName = v.optString("ShortName"),
                                gender = v.optString("Gender"),
                                locale = v.optString("Locale"),
                                status = v.optString("Status"),
                            )
                        )
                    }
                }
            }
        }
    }

    private fun voiceRequest(url: String): Request = Request.Builder().url(url).apply {
        header("User-Agent", C.UA)
        header("Accept-Encoding", "gzip, deflate, br, zstd")
        header("Accept-Language", "en-US,en;q=0.9")
        header("Authority", "speech.platform.bing.com")
        header("Sec-CH-UA", buildSecChUa())
        header("Sec-CH-UA-Mobile", "?0")
        header("Accept", "*/*")
        header("Sec-Fetch-Site", "none")
        header("Sec-Fetch-Mode", "cors")
        header("Sec-Fetch-Dest", "empty")
        header("Cookie", "muid=${muid()};")
    }.build()

    /* ================================================================== */
    /* 5. 文本清理 / XML 转义 / 字节切分                                   */
    /* ================================================================== */

    /** C0 控制字符(除 \t \n \r)替换为空格 —— OCR 文本的 \x0b 是服务端报错重灾区 */
    private fun cleanControlChars(s: String): String = buildString(s.length) {
        for (ch in s) {
            val code = ch.code
            append(if ((code in 0..8) || (code in 11..12) || (code in 14..31)) ' ' else ch)
        }
    }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun xmlUnescape(s: String): String =
        s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")

    /**
     * 把(已 XML 转义)文本按 ≤limit 字节切块 —— 等价 edge-tts split_text_by_byte_length:
     * 优先在 \n / 空格断;否则退到合法 UTF-8 边界;绝不把 &…; 实体拦腰切断。
     * 4096 字节为服务端单块上限。
     */
    internal fun splitByByteLength(escaped: String, limit: Int): List<String> {
        val out = ArrayList<String>()
        var text = escaped.toByteArray(Charsets.UTF_8)
        while (text.size > limit) {
            var splitAt = lastIndexOf(text, '\n'.code.toByte(), 0, limit)
            if (splitAt < 0) splitAt = lastIndexOf(text, ' '.code.toByte(), 0, limit)
            if (splitAt < 0) splitAt = safeUtf8Split(text)
            splitAt = adjustForXmlEntity(text, splitAt)
            require(splitAt >= 0) { "max byte length too small for this text" }
            val chunk = String(text, 0, splitAt, Charsets.UTF_8).trim()
            if (chunk.isNotEmpty()) out.add(chunk)
            text = text.copyOfRange(if (splitAt > 0) splitAt else 1, text.size)
        }
        val rest = String(text, Charsets.UTF_8).trim()
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    private fun lastIndexOf(b: ByteArray, target: Byte, from: Int, until: Int): Int {
        var i = until - 1
        while (i >= from) {
            if (b[i] == target) return i
            i--
        }
        return -1
    }

    /** 从尾部向前找最长合法 UTF-8 前缀长度(找不到返回 0) */
    private fun safeUtf8Split(text: ByteArray): Int {
        var splitAt = text.size
        while (splitAt > 0) {
            if (isValidUtf8Prefix(text, splitAt)) return splitAt
            splitAt--
        }
        return 0
    }

    private fun isValidUtf8Prefix(b: ByteArray, n: Int): Boolean {
        var i = 0
        while (i < n) {
            val u = b[i].toInt() and 0xFF
            val need = when {
                u < 0x80 -> 1
                u and 0xE0 == 0xC0 -> 2
                u and 0xF0 == 0xE0 -> 3
                u and 0xF8 == 0xF0 -> 4
                else -> return false
            }
            if (i + need > n) return false
            for (j in i + 1 until i + need) {
                if ((b[j].toInt() and 0xC0) != 0x80) return false
            }
            i += need
        }
        return true
    }

    /** 若切点前有未闭合的 &…(到切点无 ;),切点退到该 & 之前 */
    private fun adjustForXmlEntity(text: ByteArray, splitAtIn: Int): Int {
        var splitAt = splitAtIn
        while (splitAt > 0) {
            var amp = -1
            for (i in 0 until splitAt) if (text[i] == '&'.code.toByte()) amp = i
            if (amp < 0) break
            var closed = false
            for (i in amp + 1 until splitAt) {
                if (text[i] == ';'.code.toByte()) { closed = true; break }
            }
            if (closed) break
            splitAt = amp
        }
        return splitAt
    }

    /* ================================================================== */
    /* 6. SSML 构造                                                        */
    /* ================================================================== */

    /** ShortName → 服务端长名;已是长名或无法识别则原样返回 */
    private fun voiceFullName(voice: String): String {
        if (voice.startsWith("Microsoft Server Speech Text to Speech Voice")) return voice
        val m = Regex("^([a-z]{2,})-([A-Z]{2,})-(.+Neural)$").find(voice) ?: return voice
        var region = m.groupValues[2]
        var name = m.groupValues[3]
        val dash = name.indexOf('-')
        if (dash != -1) {
            region = "$region-${name.substring(0, dash)}"
            name = name.substring(dash + 1)
        }
        return "Microsoft Server Speech Text to Speech Voice (${m.groupValues[1]}-$region, $name)"
    }

    private fun buildSsml(cfg: Config, escapedText: String): String =
        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='${voiceFullName(cfg.voice)}'>" +
            "<prosody pitch='${cfg.pitch}' rate='${cfg.rate}' volume='${cfg.volume}'>" +
            escapedText +
            "</prosody></voice></speak>"

    /* ================================================================== */
    /* 7. WebSocket 帧构建 / 解析                                         */
    /* ================================================================== */

    private fun speechConfigFrame(ts: String, wordBoundary: Boolean): String {
        val wd = if (wordBoundary) "true" else "false"
        val sd = if (wordBoundary) "false" else "true"
        return "X-Timestamp:$ts\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
            "{\"sentenceBoundaryEnabled\":\"$sd\",\"wordBoundaryEnabled\":\"$wd\"}," +
            "\"outputFormat\":\"${C.OUTPUT_FORMAT}\"}}}}\r\n"
    }

    private fun ssmlFrame(ts: String, ssml: String): String =
        "X-RequestId:${connectId()}\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${ts}Z\r\n" + // 末尾 Z 为原仓库刻意保留(Edge bug 兼容),勿删
            "Path:ssml\r\n\r\n" +
            ssml

    /** 文本帧: 按首个 \r\n\r\n 切分 → (头字段, body) */
    private fun parseTextFrame(data: String): Pair<Map<String, String>, String> {
        val idx = data.indexOf("\r\n\r\n")
        if (idx < 0) throw EdgeTtsException("text frame missing CRLFCRLF")
        return parseHeaderLines(data.substring(0, idx)) to data.substring(idx + 4)
    }

    /**
     * 二进制音频帧(实测布局):
     *   [0..2)    帧头长度 F(大端 uint16)
     *   [2..2+F)  帧头块(CRLF 分隔的 Key:Value 行)
     *   [2+F..]   音频数据 —— 紧接帧头,无多余 CRLF(见 PROTOCOL_SPEC.md §3.2)
     * 返回 (头字段, 音频 ByteString)。ByteString.substring 共享底层字节,零拷贝。
     */
    private fun parseBinaryFrame(frame: ByteString): Pair<Map<String, String>, ByteString> {
        if (frame.size < 2) throw EdgeTtsException("binary frame shorter than 2 bytes")
        val f = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
        if (f > frame.size) throw EdgeTtsException("binary frame header length invalid")
        var payload = frame.substring(2 + f)
        // 兼容分支: 个别历史样例在帧头与音频间多一个 CRLF
        if (payload.size >= 2 &&
            payload[0] == '\r'.code.toByte() &&
            payload[1] == '\n'.code.toByte()
        ) {
            payload = payload.substring(2)
        }
        return parseHeaderLines(frame.substring(2, 2 + f).utf8()) to payload
    }

    private fun parseHeaderLines(headers: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (line in headers.split("\r\n")) {
            val c = line.indexOf(':')
            if (c > 0) map[line.substring(0, c)] = line.substring(c + 1)
        }
        return map
    }

    private fun buildSecChUa(): String =
        "\" Not;A Brand\";v=\"99\", \"Microsoft Edge\";v=\"${C.CHROMIUM_MAJOR}\", " +
            "\"Chromium\";v=\"${C.CHROMIUM_MAJOR}\""

    /* ================================================================== */
    /* 8. 元数据解析                                                       */
    /* ================================================================== */

    private fun parseMetadata(body: String, offsetBase: Long): List<Chunk.Boundary> {
        val out = ArrayList<Chunk.Boundary>()
        val arr = JSONObject(body).getJSONArray("Metadata")
        for (i in 0 until arr.length()) {
            val meta = arr.getJSONObject(i)
            when (val type = meta.getString("Type")) {
                "WordBoundary", "SentenceBoundary" -> {
                    val d = meta.getJSONObject("Data")
                    out.add(
                        Chunk.Boundary(
                            type = type,
                            offset = d.getLong("Offset") + offsetBase,
                            duration = d.getLong("Duration"),
                            text = xmlUnescape(d.getJSONObject("text").getString("Text")),
                        )
                    )
                }
                "SessionEnd" -> Unit
                else -> throw EdgeTtsException("unknown metadata type: $type")
            }
        }
        return out
    }

    /* ================================================================== */
    /* 9. 单 chunk 会话 & 驱动循环                                         */
    /* ================================================================== */

    /** 403(时钟偏差): 携带应累加的偏差秒;由驱动层矫正后重试一次 */
    private class Http403Skew(val deltaSeconds: Double, cause: Throwable) :
        EdgeTtsException("HTTP 403, clock skew ${deltaSeconds}s", cause)

    private class Session {
        @Volatile var ws: WebSocket? = null
        val sawAudio = AtomicBoolean(false)
        val chunkBytes = AtomicLong(0)
        val error = AtomicReference<Throwable?>()
        val http403 = AtomicReference<Http403Skew?>()
        val turnEndReceived = AtomicBoolean(false)
        val latch = CountDownLatch(1)
    }

    private val streamInUse = AtomicBoolean(false)

    @Volatile
    private var activeCancel: AtomicBoolean? = null

    @Volatile
    private var activeSession: Session? = null

    @Volatile
    private var offsetCompensation = 0L    // 跨 chunk 基准偏移(100ns 刻度)
    @Volatile
    private var cumulativeAudioBytes = 0L  // 此前所有 chunk 收到的音频字节

    /** CBR 字节 → 刻度: ticks = bytes × 8 × 10_000_000 / 48_000(整数除法,精确) */
    private fun compensate(chunkBytes: Long) {
        cumulativeAudioBytes += chunkBytes
        offsetCompensation = cumulativeAudioBytes * 8 * C.TICKS_PER_SECOND / C.MP3_BITRATE_BPS
    }

    /** 文本预处理: 清控制字符 → XML 转义 → 按 4096 字节切块(顺序同 edge-tts) */
    internal fun prepareChunks(text: String): List<String> =
        splitByByteLength(xmlEscape(cleanControlChars(text)), C.MAX_CHUNK_BYTES)

    /**
     * 单 chunk: 建连 → speech.config → ssml → 收流至 turn.end/关闭。
     * 阻塞直到本轮结束;成功返回本 chunk 音频字节数。
     * 异常: [Http403Skew](由驱动层矫正重试)/ [EdgeTtsException] / [CancellationException]。
     */
    private fun runChunk(
        cfg: Config,
        escapedText: String,
        cancelled: AtomicBoolean,
        emit: (Chunk) -> Unit,
    ): Long {
        val session = Session()
        activeSession = session
        val ts = dateToString()
        val url = C.WSS_PATH +
            "?TrustedClientToken=${C.TRUSTED_TOKEN}" +
            "&ConnectionId=${connectId()}" +
            "&Sec-MS-GEC=${secMsGec()}" +
            "&Sec-MS-GEC-Version=${C.SEC_MS_GEC_VERSION}"
        val req = Request.Builder().url(url).apply {
            header("User-Agent", C.UA)
            header("Accept-Encoding", "gzip, deflate, br, zstd")
            header("Accept-Language", "en-US,en;q=0.9")
            header("Pragma", "no-cache")
            header("Cache-Control", "no-cache")
            header("Origin", C.ORIGIN)
            header("Sec-WebSocket-Version", "13")
            header("Cookie", "muid=${muid()};")
        }.build()

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (cancelled.get()) { ws.close(1000, "cancelled"); return }
                try {
                    ws.send(speechConfigFrame(ts, cfg.wordBoundary))
                    ws.send(ssmlFrame(ts, buildSsml(cfg, escapedText)))
                } catch (t: Throwable) {
                    session.error.set(t)
                    ws.close(1000, "client error")
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (cancelled.get()) { ws.close(1000, "cancelled"); return }
                try {
                    val (head, body) = parseTextFrame(text)
                    when (head["Path"]) {
                        "audio.metadata" ->
                            for (b in parseMetadata(body, offsetCompensation)) emit(b)
                        "turn.end" -> {
                            session.turnEndReceived.set(true)
                            ws.close(1000, "done")
                        }
                        "turn.start", "response" -> Unit
                        else -> throw EdgeTtsException("unknown text path: ${head["Path"]}")
                    }
                } catch (t: Throwable) {
                    session.error.set(t)
                    ws.close(1000, "client error")
                }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                if (cancelled.get()) { ws.close(1000, "cancelled"); return }
                try {
                    val (head, payload) = parseBinaryFrame(bytes)
                    if (head["Path"] != "audio") throw EdgeTtsException("unexpected binary path")
                    val ct = head["Content-Type"]
                    if (ct != null && ct != "audio/mpeg")
                        throw EdgeTtsException("unexpected Content-Type: $ct")
                    if (payload.size == 0) return // 流尾空帧(无 Content-Type 空数据),正常信号
                    session.chunkBytes.addAndGet(payload.size.toLong())
                    session.sawAudio.set(true)
                    emit(Chunk.Audio(payload))
                } catch (t: Throwable) {
                    session.error.set(t)
                    ws.close(1000, "client error")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                try {
                    if (response != null && response.code == 403) {
                        // 握手阶段 403: 用 Date 头算偏差 → 上层重试一次
                        try {
                            session.http403.set(
                                Http403Skew(deltaFromDateHeader(response.header("Date")), t)
                            )
                        } catch (se: SkewAdjustmentException) {
                            session.error.set(se)
                        }
                    } else {
                        session.error.set(t)
                    }
                } finally {
                    response?.close()
                    session.latch.countDown()
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                session.latch.countDown()
            }
        }

        val ws = client.newWebSocket(req, listener)
        session.ws = ws
        try {
            if (!session.latch.await(90, TimeUnit.SECONDS)) {
                ws.cancel()
                throw EdgeTtsException("turn did not end within 90s")
            }
        } catch (ie: InterruptedException) {
            ws.cancel()
            Thread.currentThread().interrupt()
            throw EdgeTtsException("interrupted", ie)
        } finally {
            activeSession = null
        }

        if (cancelled.get()) throw CancellationException("cancelled")
        session.http403.get()?.let { throw it }
        session.error.get()?.let { throw it }
        if (!session.sawAudio.get()) throw NoAudioReceivedException()
        if (!session.turnEndReceived.get()) throw EdgeTtsException("connection closed before turn.end")
        return session.chunkBytes.get()
    }

    /** 驱动整段文本: 逐 chunk 合成;chunk 间 CBR 补偿;403 每 chunk 至多重试一次 */
    private fun drive(
        cfg: Config,
        chunks: List<String>,
        cancelled: AtomicBoolean,
        emit: (Chunk) -> Unit,
    ) {
        for (escaped in chunks) {
            if (cancelled.get()) return
            var attempts = 0
            while (true) {
                try {
                    val bytes = runChunk(cfg, escaped, cancelled, emit)
                    compensate(bytes) // 只统计成功 chunk
                    break
                } catch (e: Http403Skew) {
                    if (attempts >= 1) throw e
                    attempts++
                    clockSkewSeconds += e.deltaSeconds
                    // 重连重发本 chunk(握手即 403,尚未产出音频)
                }
            }
        }
    }

    /* ================================================================== */
    /* 10. 对外 API                                                        */
    /* ================================================================== */

    /** 取消当前合成(幂等;任意线程可调) */
    fun cancel() {
        activeCancel?.set(true)
        activeSession?.ws?.cancel()
    }

    /**
     * 流式合成,返回协程 [Flow]。chunk 在 OkHttp 线程产出,经 channel 桥接;
     * 全部完成后正常结束;出错/取消则终止。取消入口: 收集协程取消或 [cancel]。
     * 注意: 消费须快于网络生产,否则 [trySendBlocking] 会反压到 WebSocket 读线程;
     * 需要慢消费时请先落地到文件/队列再异步处理。
     */
    fun speakFlow(text: String, cfg: Config = Config()): Flow<Chunk> = callbackFlow {
        if (!streamInUse.compareAndSet(false, true)) {
            close(EdgeTtsException("a stream is already running on this EdgeTts instance"))
            return@callbackFlow
        }
        val cancelled = AtomicBoolean(false)
        activeCancel = cancelled
        val exec = Executors.newSingleThreadExecutor()
        exec.execute {
            try {
                drive(cfg, prepareChunks(text), cancelled) { chunk ->
                    runCatching { trySendBlocking(chunk) } // 消费者已取消则丢弃,不崩溃
                }
                close()
            } catch (t: Throwable) {
                if (cancelled.get()) close() else close(t)
            } finally {
                streamInUse.set(false)
                activeCancel = null
                exec.shutdown()
            }
        }
        awaitClose { cancelled.set(true); activeSession?.ws?.cancel() }
    }

    /** 一次性合成(挂起),返回完整 MP3 [ByteArray] */
    suspend fun synthesize(text: String, cfg: Config = Config()): ByteArray {
        val out = ByteArrayOutputStream()
        speakFlow(text, cfg).collect { chunk ->
            if (chunk is Chunk.Audio) out.write(chunk.data.toByteArray())
        }
        return out.toByteArray()
    }

    /** 同步阻塞一次性合成(勿在 Android 主线程调用),返回完整 MP3 [ByteArray] */
    fun synthesizeBlocking(text: String, cfg: Config = Config()): ByteArray {
        val out = ByteArrayOutputStream()
        val error = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)
        streamAsync(
            text, cfg,
            object : Listener {
                override fun onChunk(chunk: Chunk) {
                    if (chunk is Chunk.Audio) out.write(chunk.data.toByteArray())
                }

                override fun onError(err: Throwable) {
                    error.set(err)
                    latch.countDown()
                }

                override fun onCompleted() = latch.countDown()
            },
        )
        try {
            if (!latch.await(300, TimeUnit.SECONDS)) {
                cancel()
                throw EdgeTtsException("synthesizeBlocking timeout (300s)")
            }
        } catch (ie: InterruptedException) {
            cancel()
            Thread.currentThread().interrupt()
            throw EdgeTtsException("interrupted", ie)
        }
        error.get()?.let { throw it }
        return out.toByteArray()
    }

    /** 异步回调式合成;回调运行在后台线程,勿阻塞。取消后可能收到 [CancellationException]。 */
    fun streamAsync(text: String, cfg: Config = Config(), listener: Listener) {
        if (!streamInUse.compareAndSet(false, true)) {
            listener.onError(EdgeTtsException("a stream is already running on this EdgeTts instance"))
            return
        }
        val cancelled = AtomicBoolean(false)
        activeCancel = cancelled
        val exec = Executors.newSingleThreadExecutor()
        exec.execute {
            try {
                drive(cfg, prepareChunks(text), cancelled) { chunk -> listener.onChunk(chunk) }
                if (cancelled.get()) listener.onError(CancellationException("cancelled"))
                else listener.onCompleted()
            } catch (t: Throwable) {
                listener.onError(t)
            } finally {
                streamInUse.set(false)
                activeCancel = null
                exec.shutdown()
            }
        }
    }

    companion object {
        /** 便捷: 一次调用合成短文本 → MP3 ByteArray(挂起) */
        suspend fun quickSpeak(text: String, cfg: Config = Config()): ByteArray =
            EdgeTts().synthesize(text, cfg)

        private fun defaultClient(proxy: Proxy?): OkHttpClient =
            OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()

        /** 32 位无连字符 hex(ConnectionId / X-RequestId 用) */
        fun connectId(): String = UUID.randomUUID().toString().replace("-", "")
    }
}
