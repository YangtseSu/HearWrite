package org.yangtse.hearwrite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
