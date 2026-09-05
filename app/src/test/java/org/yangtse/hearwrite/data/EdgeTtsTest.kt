package org.yangtse.hearwrite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure Edge voice/catalog/cache helpers (AGENTS.md "TTS priority chain",
 * EDGE source). The wire protocol itself is vendored and pinned upstream
 * (`io/edge/EdgeTts.kt` — rany2/edge-tts-aligned client); this class pins
 * the app's own additions: the curated voice catalog, the language-split
 * rule and the voice+rate-bound cache naming.
 */
class EdgeTtsTest {

    // ------------------------------------------- Edge voice catalog

    @Test
    fun `default voice per language is the built-in neural pair`() {
        assertEquals(EDGE_VOICE_ZH, edgeDefaultVoiceFor("zh"))
        assertEquals(EDGE_VOICE_ZH, edgeDefaultVoiceFor("zh-CN"))
        assertEquals(EDGE_VOICE_EN, edgeDefaultVoiceFor("en"))
        assertEquals(EDGE_VOICE_EN, edgeDefaultVoiceFor("en-US"))
    }

    @Test
    fun `cjk text always uses the default voice`() {
        assertEquals("zh-CN-YunxiNeural", resolveEdgeVoice("月亮", "zh-CN-YunxiNeural", "en-US-GuyNeural", false))
        assertEquals("zh-CN-YunxiNeural", resolveEdgeVoice("月亮", "zh-CN-YunxiNeural", "en-US-GuyNeural", true))
        assertEquals("zh-CN-YunxiNeural", resolveEdgeVoice("  苹果 apple ", "zh-CN-YunxiNeural", "en-GB-RyanNeural", false))
    }

    @Test
    fun `english text uses the dedicated voice only when default is off`() {
        assertEquals("en-US-GuyNeural", resolveEdgeVoice("apple", "zh-CN-XiaoxiaoNeural", "en-US-GuyNeural", false))
        assertEquals("zh-CN-XiaoxiaoNeural", resolveEdgeVoice("apple", "zh-CN-XiaoxiaoNeural", "en-US-GuyNeural", true))
        assertEquals("zh-CN-XiaoxiaoNeural", resolveEdgeVoice("  hello ", "zh-CN-XiaoxiaoNeural", "en-GB-RyanNeural", true))
    }

    @Test
    fun `english region defaults and detection`() {
        assertEquals(EDGE_VOICE_EN, edgeDefaultEnVoice(EDGE_EN_REGION_US))
        assertEquals(EDGE_VOICE_EN_GB, edgeDefaultEnVoice(EDGE_EN_REGION_GB))
        assertEquals(EDGE_EN_REGION_GB, edgeEnRegionOf("en-GB-RyanNeural"))
        assertEquals(EDGE_EN_REGION_US, edgeEnRegionOf("en-US-GuyNeural"))
        assertEquals(EDGE_EN_REGION_US, edgeEnRegionOf(""))
    }

    @Test
    fun `stored english voice normalizes to its region default`() {
        assertEquals("en-US-GuyNeural", normalizeEnVoice("en-US-GuyNeural"))
        assertEquals("en-GB-RyanNeural", normalizeEnVoice("en-GB-RyanNeural"))
        assertEquals(EDGE_VOICE_EN, normalizeEnVoice(""))
        assertEquals(EDGE_VOICE_EN, normalizeEnVoice("en-US-RemovedNeural"))
        assertEquals(EDGE_VOICE_EN_GB, normalizeEnVoice("en-GB-RemovedNeural"))
    }

    @Test
    fun `catalog splits voices into zh and per-region en lists`() {
        val zh = edgeCatalogShortNamesZh()
        val us = edgeCatalogShortNames(EDGE_EN_REGION_US)
        val gb = edgeCatalogShortNames(EDGE_EN_REGION_GB)
        assertTrue(zh.isNotEmpty())
        assertTrue(us.isNotEmpty())
        assertTrue(gb.isNotEmpty())
        assertTrue(zh.none { !it.startsWith("zh-CN") })
        assertTrue(us.none { !it.startsWith("en-US") })
        assertTrue(gb.none { !it.startsWith("en-GB") })
        assertTrue(zh.contains(EDGE_VOICE_ZH))
        assertTrue(us.contains(EDGE_VOICE_EN))
        assertTrue(gb.contains(EDGE_VOICE_EN_GB))
    }

    @Test
    fun `catalog entries carry distinct short names and long ssml names`() {
        val shorts = EDGE_VOICE_CATALOG.map { it.shortName }
        assertEquals(shorts.size, shorts.distinct().size)
        EDGE_VOICE_CATALOG.forEach { v ->
            assertTrue(v.name.startsWith("Microsoft Server Speech Text to Speech Voice ("))
            assertTrue(v.name.endsWith(")"))
            assertTrue(v.friendlyName.isNotBlank())
        }
    }

    // --------------------------------------------------- rate mapping

    @Test
    fun `speech rate maps linearly to prosody percent with sign`() {
        assertEquals("-50%", edgeRatePercent(0.5f))
        assertEquals("-10%", edgeRatePercent(0.9f))
        assertEquals("+0%", edgeRatePercent(1.0f))
        assertEquals("+50%", edgeRatePercent(1.5f))
    }

    @Test
    fun `rate percent clamps out of range values`() {
        assertEquals("-50%", edgeRatePercent(0.1f))
        assertEquals("+50%", edgeRatePercent(4f))
    }

    // ------------------------------------------------ cache file naming

    @Test
    fun `clip hash is 8 hex and binds voice and rate`() {
        assertTrue(Regex("^[0-9a-f]{8}$").matches(edgeClipHash(EDGE_VOICE_ZH, 0.9f)))
        assertEquals(edgeClipHash(EDGE_VOICE_ZH, 0.9f), edgeClipHash(EDGE_VOICE_ZH, 0.9f))
        assertNotEquals(edgeClipHash(EDGE_VOICE_ZH, 0.9f), edgeClipHash(EDGE_VOICE_EN, 0.9f))
        assertNotEquals(edgeClipHash(EDGE_VOICE_ZH, 0.9f), edgeClipHash(EDGE_VOICE_ZH, 1.0f))
    }

    @Test
    fun `clip file name is prefixed and keeps sources disjoint`() {
        val name = edgeClipFileName("Apple pie", EDGE_VOICE_EN, 0.9f)
        val hash = edgeClipHash(EDGE_VOICE_EN, 0.9f)
        assertTrue(name.startsWith("edge-apple_20pie."))
        assertTrue(name.endsWith(".$hash.mp3"))
        assertTrue(edgeClipFileName("苹果", EDGE_VOICE_ZH, 0.9f).startsWith("edge-_E8_8B_B9_E6_9E_9C."))
        assertEquals("unknown", edgeClipFileName("   ", EDGE_VOICE_EN, 0.9f)
            .removePrefix("edge-").substringBefore('.'))
    }

    @Test
    fun `voice and rate changes never collide with the same text`() {
        val a = edgeClipFileName("word", EDGE_VOICE_EN, 0.9f)
        val b = edgeClipFileName("word", EDGE_VOICE_EN, 1.5f)
        val c = edgeClipFileName("word", EDGE_VOICE_ZH, 0.9f)
        assertEquals(setOf(a, b, c).size, 3)
    }
}
