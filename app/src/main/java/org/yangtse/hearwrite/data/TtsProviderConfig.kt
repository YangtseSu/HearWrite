package org.yangtse.hearwrite.data

import java.util.Base64
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Wire shape of the configured OpenAI-compatible TTS endpoint (mirror of
 * alice `TtsApiKind` in `ttsConfig.ts`, AGENTS.md "TTS priority chain"):
 * - `SPEECH`: standard `POST {base}/audio/speech` returning binary audio
 *   (OpenAI TTS, 智谱 GLM-TTS, 硅基流动, …).
 * - `CHAT`: `POST {base}/chat/completions` with an `audio` option returning
 *   base64 audio inside `choices[0].message.audio.data` (小米 MiMo).
 */
enum class TtsApiKind(val wire: String) {
    SPEECH("speech"),
    CHAT("chat"),
}

/**
 * BYOK OpenAI-compatible TTS provider config. Stored as one JSON blob under
 * the DataStore settings file, same shape as alice's `alice_tts_provider_config`.
 * This module has no kotlinx-serialization codegen, so the repository
 * encodes/decodes the blob field by field. Empty `voice*` fields mean the
 * provider default voice; `responseFormat` applies to the `speech` wire
 * shape only (default "mp3").
 */
data class TtsProviderConfig(
    val api: TtsApiKind = TtsApiKind.SPEECH,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val voiceEn: String = "",
    val voiceZh: String = "",
    val responseFormat: String? = null,
) {
    /** Usable only when the endpoint, key and model are non-empty (upstream `isTtsProviderConfigSet`). */
    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

/** One selectable provider preset; mirror of alice's `TTS_PROVIDER_PRESETS`. */
data class TtsProviderPreset(
    val id: String,
    val label: String,
    val api: TtsApiKind,
    val baseUrl: String,
    val model: String,
    val voiceEn: String = "",
    val voiceZh: String = "",
    val responseFormat: String? = null,
    val hint: String = "",
)

/**
 * OpenAI-compatible TTS providers. Only MiMo is free at the moment; the rest
 * are pre-filled convenience presets whose fields stay editable. Contents
 * mirror alice `src/lib/ttsConfig.ts` byte for byte.
 */
val TTS_PROVIDER_PRESETS: List<TtsProviderPreset> = listOf(
    TtsProviderPreset(
        id = "mimo",
        label = "小米 MiMo",
        api = TtsApiKind.CHAT,
        baseUrl = "https://api.xiaomimimo.com/v1",
        model = "mimo-v2.5-tts",
        voiceEn = "Chloe",
        voiceZh = "冰糖",
        hint = "限时免费 · mimo.mi.com",
    ),
    TtsProviderPreset(
        id = "zhipu",
        label = "智谱 GLM",
        api = TtsApiKind.SPEECH,
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        model = "glm-tts",
        voiceEn = "tongtong",
        voiceZh = "tongtong",
        responseFormat = "wav",
        hint = "open.bigmodel.cn",
    ),
    TtsProviderPreset(
        id = "siliconflow",
        label = "硅基流动",
        api = TtsApiKind.SPEECH,
        baseUrl = "https://api.siliconflow.cn/v1",
        model = "FunAudioLLM/CosyVoice2-0.5B",
        voiceEn = "FunAudioLLM/CosyVoice2-0.5B:alex",
        voiceZh = "FunAudioLLM/CosyVoice2-0.5B:anna",
        hint = "siliconflow.cn",
    ),
    TtsProviderPreset(
        id = "openai",
        label = "OpenAI",
        api = TtsApiKind.SPEECH,
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4o-mini-tts",
        voiceEn = "alloy",
        voiceZh = "alloy",
        hint = "需可访问 OpenAI 的网络",
    ),
    TtsProviderPreset(id = "custom", label = "自定义", api = TtsApiKind.SPEECH, baseUrl = "", model = ""),
)

/** The default preset shown for a fresh form (小米 MiMo, alice default). */
val DEFAULT_TTS_PRESET: TtsProviderPreset = TTS_PROVIDER_PRESETS.first()

/**
 * Build the `/audio/speech` URL from a base URL. Tolerates base URLs that
 * already end with the path so users can paste a full endpoint if they like
 * (upstream `buildSpeechUrl`).
 */
fun speechUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    return if (trimmed.endsWith("/audio/speech")) trimmed else "$trimmed/audio/speech"
}

/** Voice field for [text]: CJK text → voiceZh, else voiceEn; "" = provider default. */
fun ttsProviderVoiceFor(cfg: TtsProviderConfig, text: String): String =
    (if (isCjkText(text)) cfg.voiceZh else cfg.voiceEn).trim()

/**
 * Effective audio format for [cfg]: the `chat` wire shape always returns wav
 * (MiMo synthesizes wav); `speech` uses responseFormat defaulting to mp3.
 */
fun ttsProviderFormatFor(cfg: TtsProviderConfig): String = when (cfg.api) {
    TtsApiKind.CHAT -> "wav"
    TtsApiKind.SPEECH -> cfg.responseFormat?.trim()?.lowercase()?.ifEmpty { null } ?: "mp3"
}

/**
 * Stable 8-hex hash of api|model|voice|format|rate (rate ×10 rounded) —
 * changing any of them regenerates clips instead of replaying stale audio
 * (alice `providerClipHash`, AGENTS.md clip-cache contract).
 */
fun ttsProviderClipHash(cfg: TtsProviderConfig, text: String, rate: Float): String {
    val rateQ = (rate * 10).roundToInt()
    val seed =
        "${cfg.api.wire}|${cfg.model.trim()}|${ttsProviderVoiceFor(cfg, text)}|${ttsProviderFormatFor(cfg)}|$rateQ"
    var h = 5381
    for (ch in seed) {
        h = ((h shl 5) + h + ch.code)
    }
    // Integer.toHexString renders negative ints as the full unsigned 8 hex
    // digits — the JS `h >>> 0 .toString(16).padStart(8, "0")` equivalent.
    return Integer.toHexString(h).padStart(8, '0')
}

/**
 * Provider cache file name under `cacheDir/tts/`: percent-encoded lowercase
 * text (`%` → `_`) + `.` + the [ttsProviderClipHash] + `.` + format. The
 * hash binds the clip to its voice/format/rate so stale audio never replays.
 */
fun ttsProviderClipFileName(text: String, cfg: TtsProviderConfig, rate: Float): String {
    val safe = uriComponentEncode(text.trim().lowercase()).replace("%", "_").ifEmpty { "unknown" }
    return "$safe.${ttsProviderClipHash(cfg, text, rate)}.${ttsProviderFormatFor(cfg)}"
}

/** Single-flight key for one provider clip (hash:lowercased text, alice `pendingProviderDownloads`). */
fun ttsProviderFlightKey(text: String, cfg: TtsProviderConfig, rate: Float): String =
    "${ttsProviderClipHash(cfg, text, rate)}:${text.trim().lowercase()}"

/**
 * Decode a base64 audio payload. Tolerates a data-URL prefix
 * (`data:audio/wav;base64,…`) and embedded whitespace (MimeDecoder); any
 * malformed input degrades to an empty array (→ "no audio").
 */
fun ttsProviderBase64Decode(input: String): ByteArray {
    val trimmed = input.trim()
    val marker = "base64,"
    val markerIndex = trimmed.indexOf(marker, ignoreCase = true)
    val payload = if (markerIndex >= 0) trimmed.substring(markerIndex + marker.length) else trimmed
    return try {
        Base64.getMimeDecoder().decode(payload)
    } catch (e: Exception) {
        ByteArray(0)
    }
}

/**
 * Extract `choices[0].message.audio.data` (base64 string) from a
 * chat-completions reply; "" when absent/unparseable. Every field must
 * exist and the data must be a string per the wire contract (AGENTS.md) —
 * numeric/object data counts as missing (alice `typeof` check).
 */
fun ttsProviderAudioData(body: String): String = try {
    val root = ttsJson.parseToJsonElement(body).jsonObject
    val data = root["choices"]
        ?.jsonArray?.firstOrNull()?.jsonObject
        ?.get("message")?.jsonObject
        ?.get("audio")?.jsonObject
        ?.get("data") as? JsonPrimitive
    data?.takeIf { it.isString }?.content?.trim().orEmpty()
} catch (e: Exception) {
    ""
}

/**
 * `POST /audio/speech` request body (AGENTS.md): model/input/response_format
 * plus `speed` = speech rate clamped to [0.25, 4]; an empty voice omits the
 * `voice` field so the provider default applies.
 */
fun ttsSpeechRequestBody(cfg: TtsProviderConfig, text: String, rate: Float): String = buildJsonObject {
    put("model", cfg.model.trim())
    put("input", text)
    put("response_format", ttsProviderFormatFor(cfg))
    put("speed", rate.coerceIn(0.25f, 4f))
    val voice = ttsProviderVoiceFor(cfg, text)
    if (voice.isNotEmpty()) put("voice", voice)
}.toString()

/**
 * `POST /chat/completions` request body (AGENTS.md): assistant message with
 * the synthesis text plus an `audio` option (`format` fixed "wav" on this
 * shape; `voice` omitted when empty).
 */
fun ttsChatRequestBody(cfg: TtsProviderConfig, text: String): String = buildJsonObject {
    put("model", cfg.model.trim())
    putJsonArray("messages") {
        addJsonObject {
            put("role", "assistant")
            put("content", text)
        }
    }
    putJsonObject("audio") {
        put("format", "wav")
        val voice = ttsProviderVoiceFor(cfg, text)
        if (voice.isNotEmpty()) put("voice", voice)
    }
}.toString()

/** `{"api":…,"baseUrl":…,…}` — same shape as alice's stored TTS provider config. */
internal fun encodeTtsProviderConfig(cfg: TtsProviderConfig): String = buildJsonObject {
    put("api", cfg.api.wire)
    put("baseUrl", cfg.baseUrl)
    put("apiKey", cfg.apiKey)
    put("model", cfg.model)
    put("voiceEn", cfg.voiceEn)
    put("voiceZh", cfg.voiceZh)
    if (cfg.responseFormat != null) put("responseFormat", cfg.responseFormat)
}.toString()

/** Decode a stored provider config blob; null when absent/invalid (alice `loadTtsSettings`). */
internal fun decodeTtsProviderConfig(raw: String): TtsProviderConfig? = try {
    val obj = ttsJson.parseToJsonElement(raw).jsonObject
    val api = when (obj["api"]?.jsonPrimitive?.contentOrNull) {
        "speech" -> TtsApiKind.SPEECH
        "chat" -> TtsApiKind.CHAT
        else -> return null
    }
    TtsProviderConfig(
        api = api,
        baseUrl = obj["baseUrl"]?.jsonPrimitive?.contentOrNull?.trim() ?: "",
        apiKey = obj["apiKey"]?.jsonPrimitive?.contentOrNull?.trim() ?: "",
        model = obj["model"]?.jsonPrimitive?.contentOrNull?.trim() ?: "",
        voiceEn = obj["voiceEn"]?.jsonPrimitive?.contentOrNull?.trim() ?: "",
        voiceZh = obj["voiceZh"]?.jsonPrimitive?.contentOrNull?.trim() ?: "",
        responseFormat = obj["responseFormat"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
    )
} catch (e: Exception) {
    null
}

internal val ttsJson = Json { ignoreUnknownKeys = true }
