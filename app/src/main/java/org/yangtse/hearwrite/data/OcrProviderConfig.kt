package org.yangtse.hearwrite.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * BYOK OCR provider config (AGENTS.md OCR import) — the user's own key only,
 * no built-in key, no credits/quota (the upstream paid stack is excluded
 * entirely). Stored **per 服务商 preset** as `{"<presetId>":{…}}` under the
 * DataStore settings file, so each provider keeps its own URL/key/model.
 * This module has no kotlinx-serialization codegen (AGENTS.md: JSON handled
 * dynamically), so the blob codec lives here next to the model.
 */
data class OcrProviderConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
) {
    /** Usable only when all three fields are non-blank (upstream `isCustomOcrConfigSet`). */
    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

/** One selectable provider preset (label shown in the picker chips). */
data class OcrProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val model: String,
)

/**
 * OpenAI-compatible vision providers — all expose `/chat/completions` with
 * `image_url` content parts, so only base URL / key / model differ. The
 * default preset is Zhipu GLM-4V-Flash (free), the AGENTS.md default. The
 * RN predecessor's OpenAI / 通义千问 / Kimi / 硅基流动 / Ollama presets were
 * dropped by the author's choice — 自定义 covers any remaining endpoint.
 */
val OCR_PROVIDER_PRESETS: List<OcrProviderPreset> = listOf(
    OcrProviderPreset(
        id = "zhipu",
        label = "智谱 GLM",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        model = "glm-4v-flash",
    ),
    OcrProviderPreset(
        id = "zen",
        label = "OpenCode Zen",
        baseUrl = "https://opencode.ai/zen/v1",
        model = "mimo-v2.5-free",
    ),
    OcrProviderPreset(
        id = "vercel",
        label = "Vercel",
        baseUrl = "https://ai-gateway.vercel.sh/v1",
        model = "xiaomi/mimo-v2.5",
    ),
    OcrProviderPreset(
        id = "commandcode",
        label = "Command Code",
        baseUrl = "https://api.commandcode.ai/provider/v1",
        model = "xiaomi/mimo-v2.5",
    ),
    OcrProviderPreset(
        id = "openrouter",
        label = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        model = "qwen/qwen3.8-flash",
    ),
    OcrProviderPreset(id = "custom", label = "自定义", baseUrl = "", model = ""),
)

/** The AGENTS.md default preset (Zhipu GLM-4V-Flash, BYOK). */
val DEFAULT_OCR_PRESET: OcrProviderPreset = OCR_PROVIDER_PRESETS.first()

/**
 * Build the `/chat/completions` URL from a base URL, tolerating base URLs
 * that already end with the path (upstream `buildChatCompletionsUrl`) so users
 * can paste a full endpoint if they like.
 */
fun chatCompletionsUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
}

internal val ocrJson = Json { ignoreUnknownKeys = true }

/** `{"baseUrl":…,"apiKey":…,"model":…}` — same shape as alice's stored config. */
internal fun encodeOcrConfig(cfg: OcrProviderConfig): String = buildJsonObject {
    put("baseUrl", cfg.baseUrl)
    put("apiKey", cfg.apiKey)
    put("model", cfg.model)
}.toString()

/**
 * Decode a stored config blob; null when absent/unparseable or when a
 * required field is not a JSON string (alice typeof check — a type-coerced
 * blob can never look complete).
 */
internal fun decodeOcrConfig(raw: String): OcrProviderConfig? = try {
    val obj = ocrJson.parseToJsonElement(raw).jsonObject
    fun str(key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    OcrProviderConfig(
        baseUrl = str("baseUrl")?.trim() ?: return null,
        apiKey = str("apiKey")?.trim() ?: return null,
        model = str("model")?.trim() ?: return null,
    )
} catch (e: Exception) {
    null
}

internal fun legacyOcrPresetId(cfg: OcrProviderConfig): String =
    OCR_PROVIDER_PRESETS.firstOrNull {
        it.id != "custom" && it.baseUrl == cfg.baseUrl.trim() && it.model == cfg.model.trim()
    }?.id ?: "custom"

/** `{"<presetId>":{…}}` — one stored config per provider preset. */
internal fun encodeOcrConfigMap(map: Map<String, OcrProviderConfig>): String = buildJsonObject {
    map.forEach { (id, cfg) -> put(id, ocrJson.parseToJsonElement(encodeOcrConfig(cfg))) }
}.toString()

/**
 * Decode the per-preset map; corrupt entries (non-object or non-decodable
 * values) are dropped so one bad blob never takes the whole store down.
 */
internal fun decodeOcrConfigMap(raw: String): Map<String, OcrProviderConfig> = try {
    ocrJson.parseToJsonElement(raw).jsonObject.entries.mapNotNull { (id, el) ->
        (el as? JsonObject)?.let { decodeOcrConfig(it.toString()) }?.let { id to it }
    }.toMap()
} catch (e: Exception) {
    emptyMap()
}
