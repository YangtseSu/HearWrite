package org.yangtse.hearwrite.data

/**
 * BYOK OCR provider config (AGENTS.md OCR import) — the user's own key only,
 * no built-in key, no credits/quota (the upstream paid stack is excluded
 * entirely). Stored as one JSON blob under the DataStore settings file, same
 * shape as alice's `alice_ocr_provider_config`. This module has no
 * kotlinx-serialization codegen (AGENTS.md: JSON handled dynamically), so
 * SettingsRepository encodes/decodes the blob field by field.
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

/** One selectable provider preset; mirror of alice's `OCR_PROVIDER_PRESETS`. */
data class OcrProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val model: String,
    val hint: String = "",
)

/**
 * OpenAI-compatible vision providers — all expose `/chat/completions` with
 * `image_url` content parts, so only base URL / key / model differ. The
 * default preset is Zhipu GLM-4V-Flash (free), the AGENTS.md default.
 */
val OCR_PROVIDER_PRESETS: List<OcrProviderPreset> = listOf(
    OcrProviderPreset(
        id = "zhipu",
        label = "智谱 GLM",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        model = "glm-4v-flash",
        hint = "open.bigmodel.cn",
    ),
    OcrProviderPreset(
        id = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4o-mini",
        hint = "platform.openai.com",
    ),
    OcrProviderPreset(
        id = "qwen",
        label = "通义千问 VL",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        model = "qwen-vl-plus",
        hint = "dashscope.aliyuncs.com",
    ),
    OcrProviderPreset(
        id = "moonshot",
        label = "Kimi (Moonshot)",
        baseUrl = "https://api.moonshot.cn/v1",
        model = "moonshot-v1-8k-vision-preview",
        hint = "platform.moonshot.cn",
    ),
    OcrProviderPreset(
        id = "siliconflow",
        label = "硅基流动",
        baseUrl = "https://api.siliconflow.cn/v1",
        model = "Qwen/Qwen2-VL-7B-Instruct",
        hint = "siliconflow.cn",
    ),
    OcrProviderPreset(
        id = "openrouter",
        label = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        model = "google/gemini-flash-1.5",
        hint = "openrouter.ai",
    ),
    OcrProviderPreset(
        id = "ollama",
        label = "Ollama (本地)",
        baseUrl = "http://localhost:11434/v1",
        model = "llama3.2-vision",
        hint = "无需 KEY，需开启本地服务",
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
