package org.yangtse.hearwrite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure Youdao voice helpers (AGENTS.md "TTS priority chain"): URL forms,
 * encodeURIComponent-equivalent percent encoding and cache file names. The
 * expectations mirror alice `src/lib/tts.ts` byte for byte.
 */
class YoudaoTtsTest {

    // -------------------------------------------------- percent encoding

    @Test
    fun `encode keeps the encodeURIComponent unreserved set`() {
        // Letters/digits plus - _ . ! ~ * ' ( ) pass through unescaped.
        assertEquals("hello_world-1.2", uriComponentEncode("hello_world-1.2"))
        assertEquals("you're", uriComponentEncode("you're"))
        assertEquals("a(b)!c~d*e'f.g", uriComponentEncode("a(b)!c~d*e'f.g"))
    }

    @Test
    fun `encode percent-encodes spaces and reserved characters`() {
        assertEquals("hello%20world", uriComponentEncode("hello world"))
        assertEquals("New%20York", uriComponentEncode("New York"))
        assertEquals("rock%20%26%20roll", uriComponentEncode("rock & roll"))
        assertEquals("50%25%20off%3F", uriComponentEncode("50% off?"))
    }

    @Test
    fun `encode uses uppercase UTF-8 percent bytes for CJK`() {
        // 月 U+6708 = E6 9C 88 in UTF-8.
        assertEquals("%E6%9C%88", uriComponentEncode("月"))
        assertEquals("a%E6%9C%88b", uriComponentEncode("a月b"))
    }

    // ------------------------------------------------------------ CJK test

    @Test
    fun `cjk detection matches the ideograph range`() {
        assertTrue(isCjkText("月"))
        assertTrue(isCjkText("apple月pie"))
        assertFalse(isCjkText("apple"))
        assertFalse(isCjkText("you're"))
    }

    // --------------------------------------------------------- URL forms

    @Test
    fun `cjk text uses the single le=zh voice`() {
        val urls = youdaoUrls("月", "zh-CN")
        assertEquals(1, urls.size)
        assertEquals("https://dict.youdao.com/dictvoice?audio=%E6%9C%88&le=zh", urls[0])
    }

    @Test
    fun `cjk gloss text also uses le=zh`() {
        assertEquals(
            listOf("https://dict.youdao.com/dictvoice?audio=%E8%8B%B9%E6%9E%9C&le=zh"),
            youdaoUrls("苹果", "zh-CN"),
        )
    }

    @Test
    fun `english text tries type=2 then type=1`() {
        val urls = youdaoUrls("apple", "en-US")
        assertEquals(2, urls.size)
        assertEquals("https://dict.youdao.com/dictvoice?audio=apple&type=2", urls[0])
        assertEquals("https://dict.youdao.com/dictvoice?audio=apple&type=1", urls[1])
    }

    @Test
    fun `english multi-word text percent-encodes the spaces`() {
        val urls = youdaoUrls("New York", "en-US")
        assertEquals(
            listOf(
                "https://dict.youdao.com/dictvoice?audio=New%20York&type=2",
                "https://dict.youdao.com/dictvoice?audio=New%20York&type=1",
            ),
            urls,
        )
    }

    // --------------------------------------------------- cache file names

    @Test
    fun `english cache file is lowercased percent text with mp3 ext`() {
        assertEquals("apple.mp3", ttsCacheFileName("  Apple  ", "en-US"))
        assertEquals("new_20york.mp3", ttsCacheFileName("New York", "en-US"))
        assertEquals("you're.mp3", ttsCacheFileName("You're", "en-US"))
    }

    @Test
    fun `cjk cache file gets the zh suffix`() {
        // 月亮 = %E6%9C%88%E4%BA%AE with % replaced by _.
        assertEquals("_E6_9C_88_E4_BA_AE.zh.mp3", ttsCacheFileName("月亮", "zh-CN"))
        assertEquals("_E6_9C_88.zh.mp3", ttsCacheFileName("月", "zh-CN"))
    }

    @Test
    fun `file name carries the language even for ascii text`() {
        // A zh-CN request for ASCII text caches separately from en-US.
        assertEquals("apple.zh.mp3", ttsCacheFileName("Apple", "zh-CN"))
    }

    // ------------------------------------------------------- flight keys

    @Test
    fun `flight key is trimmed lowercase text plus lowercase lang`() {
        assertEquals("en-us|hello world", ttsCacheKey("  Hello World ", "en-US"))
        assertEquals("zh-cn|月", ttsCacheKey("月", "zh-CN"))
    }
}
