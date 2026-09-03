package org.yangtse.hearwrite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** chatCompletionsUrl + config completeness semantics (alice ocrConfig parity). */
class OcrProviderConfigTest {

    @Test
    fun chatCompletionsUrl_plainBase_appendsPath() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            chatCompletionsUrl("https://open.bigmodel.cn/api/paas/v4"),
        )
    }

    @Test
    fun chatCompletionsUrl_trailingSlashes_areTrimmed() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            chatCompletionsUrl("https://api.openai.com/v1///"),
        )
    }

    @Test
    fun chatCompletionsUrl_fullEndpoint_isKept() {
        assertEquals(
            "https://example.com/v1/chat/completions",
            chatCompletionsUrl("https://example.com/v1/chat/completions"),
        )
        assertEquals(
            "https://example.com/v1/chat/completions",
            chatCompletionsUrl("https://example.com/v1/chat/completions/"),
        )
    }

    @Test
    fun defaultPreset_isZhipuGlm4vFlash() {
        assertEquals("zhipu", DEFAULT_OCR_PRESET.id)
        assertEquals("https://open.bigmodel.cn/api/paas/v4", DEFAULT_OCR_PRESET.baseUrl)
        assertEquals("glm-4v-flash", DEFAULT_OCR_PRESET.model)
    }

    @Test
    fun config_complete_onlyWhenAllFieldsFilled() {
        val full = OcrProviderConfig("https://a.b/v1", "key", "glm-4v-flash")
        assertTrue(full.isComplete)
        assertFalse(OcrProviderConfig("https://a.b/v1", "", "glm-4v-flash").isComplete)
        assertFalse(OcrProviderConfig("", "key", "glm-4v-flash").isComplete)
        assertFalse(OcrProviderConfig("https://a.b/v1", "key", "").isComplete)
        assertFalse(OcrProviderConfig("   ", "key", "glm-4v-flash").isComplete)
    }

    @Test
    fun `config codec round-trips and trims stored values`() {
        val cfg = OcrProviderConfig(" https://a.b/v1 ", " k ", " m ")
        assertEquals(
            OcrProviderConfig("https://a.b/v1", "k", "m"),
            decodeOcrConfig(encodeOcrConfig(cfg)),
        )
    }

    @Test
    fun `config codec rejects garbage`() {
        assertNull(decodeOcrConfig("not json"))
        assertNull(decodeOcrConfig("""{"baseUrl":5,"apiKey":"k","model":"m"}"""))
    }

    @Test
    fun `config map round-trips one entry per preset`() {
        val map = mapOf(
            "zhipu" to OcrProviderConfig("https://open.bigmodel.cn/api/paas/v4", "k1", "glm-4v-flash"),
            "siliconflow" to OcrProviderConfig("https://api.siliconflow.cn/v1", "k2", "Qwen/Qwen2-VL-7B-Instruct"),
        )
        assertEquals(map, decodeOcrConfigMap(encodeOcrConfigMap(map)))
    }

    @Test
    fun `config map drops corrupt entries instead of failing the store`() {
        val raw = """
            {"zhipu":{"baseUrl":"https://x","apiKey":"k","model":"m"},
             "broken":{"baseUrl":5,"apiKey":"k","model":"m"},
             "not-an-object":true}
        """.trimIndent()
        val decoded = decodeOcrConfigMap(raw)
        assertEquals(setOf("zhipu"), decoded.keys)
        assertEquals("https://x", decoded["zhipu"]!!.baseUrl)
    }

    @Test
    fun `config map tolerates garbage and empty objects`() {
        assertEquals(emptyMap<String, OcrProviderConfig>(), decodeOcrConfigMap("not json"))
        assertEquals(emptyMap<String, OcrProviderConfig>(), decodeOcrConfigMap("{}"))
    }

    @Test
    fun `legacy preset matching pairs baseUrl and model, else custom`() {
        assertEquals(
            "zhipu",
            legacyOcrPresetId(OcrProviderConfig("https://open.bigmodel.cn/api/paas/v4", "k", "glm-4v-flash")),
        )
        assertEquals(
            "custom",
            legacyOcrPresetId(OcrProviderConfig("https://elsewhere/v1", "k", "m")),
        )
    }
}
