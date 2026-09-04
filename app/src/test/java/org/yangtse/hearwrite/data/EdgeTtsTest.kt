package org.yangtse.hearwrite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure Microsoft Edge Read-Aloud helpers (AGENTS.md "TTS priority chain",
 * EDGE source): Sec-MS-GEC token, frame bodies, binary frame parsing, rate
 * mapping and cache naming. Golden values were produced with the upstream
 * float arithmetic (rany2/edge-tts drm.py, MIT) via Python 3 on 2026-09-04.
 */
class EdgeTtsTest {

    // ------------------------------------------------------- Sec-MS-GEC

    @Test
    fun `sec ms gec matches python golden vectors`() {
        // python3: f"{ticks:.0f}6A5AA1D4EAFF4E9FB37E23D68491D6F4" sha256 upper
        assertEquals(
            "42301B335578FEFDAE2637DED1ABD614505D432559EC08032B82048483726AFF",
            edgeSecMsGec(1_700_000_000.0),
        )
        assertEquals(
            "81C8AA79A860738D7C6C28578D367A9D88EC6A4F4D98C9FD9F5BC32C4B94CB91",
            edgeSecMsGec(1_750_000_000.0),
        )
        assertEquals(
            "E3422FD0756CECC47CFF24EBB4DFE17D271ED9B9143F991B7A05444093C09D25",
            edgeSecMsGec(1_787_654_321.987),
        )
    }

    @Test
    fun `sec ms gec is stable inside a window and changes across it`() {
        // Windows are 300 s epoch-aligned (WIN_EPOCH is divisible by 300):
        // [1_700_000_000, 1_700_000_100) is one window, +100 the next.
        assertEquals(edgeSecMsGec(1_700_000_000.0), edgeSecMsGec(1_700_000_099.0))
        assertNotEquals(edgeSecMsGec(1_700_000_000.0), edgeSecMsGec(1_700_000_100.0))
        assertEquals(edgeSecMsGec(1_700_000_100.0), edgeSecMsGec(1_700_000_399.0))
    }

    @Test
    fun `ws url carries token connection id and gec params`() {
        val url = edgeWsUrl("abc123", "GECTOKEN")
        assertTrue(url.startsWith("wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?"))
        assertTrue(url.contains("TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4"))
        assertTrue(url.contains("ConnectionId=abc123"))
        assertTrue(url.contains("Sec-MS-GEC=GECTOKEN"))
        assertTrue(url.contains("Sec-MS-GEC-Version=1-143.0.3650.75"))
    }

    @Test
    fun `muid and connection ids are fresh 32-hex uppercase strings`() {
        assertTrue(Regex("^[0-9A-F]{32}$").matches(edgeMuid()))
        assertTrue(Regex("^[0-9a-f]{32}$").matches(edgeConnectionId()))
        assertNotEquals(edgeMuid(), edgeMuid())
        assertNotEquals(edgeConnectionId(), edgeConnectionId())
    }

    // ---------------------------------------------------------- frames

    @Test
    fun `date string is the javascript-style utc form`() {
        // 1_700_000_000 s = 2023-11-14 22:13:20 UTC (a Tuesday).
        assertEquals(
            "Tue Nov 14 2023 22:13:20 GMT+0000 (Coordinated Universal Time)",
            edgeDateString(1_700_000_000_000L),
        )
    }

    @Test
    fun `speech config frame negotiates the mp3 output format`() {
        val frame = edgeSpeechConfigFrame(1_700_000_000_000L)
        assertEquals(
            "X-Timestamp:Tue Nov 14 2023 22:13:20 GMT+0000 (Coordinated Universal Time)\r\n" +
                "Content-Type:application/json; charset=utf-8\r\n" +
                "Path:speech.config\r\n\r\n" +
                """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"true","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}\r\n""",
            frame,
        )
    }

    @Test
    fun `ssml frame carries request id timestamp and xml body`() {
        val frame = edgeSsmlFrame("req1", "<speak>hi</speak>", 1_700_000_000_000L)
        assertEquals(
            "X-RequestId:req1\r\n" +
                "Content-Type:application/ssml+xml\r\n" +
                // The trailing Z after the date is the upstream Edge quirk.
                "X-Timestamp:Tue Nov 14 2023 22:13:20 GMT+0000 (Coordinated Universal Time)Z\r\n" +
                "Path:ssml\r\n\r\n" +
                "<speak>hi</speak>",
            frame,
        )
    }

    @Test
    fun `text path is extracted from a frame header`() {
        assertEquals("ssml", edgeTextPath("X-RequestId:r\r\nPath:ssml\r\n\r\nbody"))
        assertEquals("", edgeTextPath("no header here"))
        assertEquals("turn.end", edgeTextPath("Path:turn.end\r\n\r\n"))
    }

    @Test
    fun `control characters are cleaned before ssml`() {
        val clean = edgeCleanText("a\u000bb\u0000c\u001fd\n e")
        assertEquals("a b c d\n e", clean) // vertical tab, NUL, unit sep → space
    }

    @Test
    fun `xml escape covers amp lt gt in order`() {
        assertEquals("a&amp;b&lt;c&gt;d", edgeXmlEscape("a&b<c>d"))
        // The original ampersand of a written entity must not double-escape.
        assertEquals("&amp;amp;", edgeXmlEscape("&amp;"))
    }

    @Test
    fun `ssml wraps the escaped text in voice and prosody`() {
        val ssml = edgeSsml("月 & 月", EDGE_VOICE_ZH, "-10%")
        assertEquals(
            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                "<voice name='zh-CN-XiaoxiaoNeural'>" +
                "<prosody pitch='+0Hz' rate='-10%' volume='+0%'>" +
                "月 &amp; 月" +
                "</prosody></voice></speak>",
            ssml,
        )
    }

    @Test
    fun `voice follows the text language`() {
        assertEquals(EDGE_VOICE_ZH, edgeVoiceFor("月亮"))
        assertEquals(EDGE_VOICE_EN, edgeVoiceFor("apple"))
        assertEquals(EDGE_VOICE_EN, edgeVoiceFor("  hello "))
    }

    // ------------------------------------------- Edge voice catalog

    @Test
    fun `default voice per language is the built-in neural pair`() {
        assertEquals(EDGE_VOICE_ZH, edgeDefaultVoiceFor("zh"))
        assertEquals(EDGE_VOICE_ZH, edgeDefaultVoiceFor("zh-CN"))
        assertEquals(EDGE_VOICE_EN, edgeDefaultVoiceFor("en"))
        assertEquals(EDGE_VOICE_EN, edgeDefaultVoiceFor("en-US"))
    }

    @Test
    fun `catalog splits voices into zh and en lists`() {
        val zh = edgeCatalogShortNames("zh")
        val en = edgeCatalogShortNames("en")
        assertTrue(zh.isNotEmpty())
        assertTrue(en.isNotEmpty())
        assertTrue(zh.none { !it.startsWith("zh-CN") })
        assertTrue(en.none { !it.startsWith("en-US") })
        assertTrue(zh.contains(EDGE_VOICE_ZH))
        assertTrue(en.contains(EDGE_VOICE_EN))
    }

    @Test
    fun `catalog entries carry distinct short names and long ssml names`() {
        val shorts = EDGE_VOICE_CATALOG.map { it.shortName }
        assertEquals(shorts.size, shorts.distinct().size)
        // Long name must embed the shortName's voice part for the SSML.
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

    // --------------------------------------------- binary frame parsing

    @Test
    fun `binary audio frame splits headers from payload`() {
        // Real service layout: the header block (length F) includes its
        // trailing \r\n; the MP3 payload starts right at [2 + F].
        val header = "Path:audio\r\nContent-Type:audio/mpeg\r\n".toByteArray(Charsets.US_ASCII)
        val payload = byteArrayOf(0x49, 0x44, 0x33, 0x01)
        val frame = byteArrayOf(
            ((header.size shr 8) and 0xFF).toByte(),
            (header.size and 0xFF).toByte(),
        ) + header + payload

        val parsed = edgeParseBinaryFrame(frame)!!
        assertEquals("audio", parsed.first["Path"])
        assertEquals("audio/mpeg", parsed.first["Content-Type"])
        assertTrue(parsed.second.contentEquals(payload))
    }

    @Test
    fun `empty termination frame parses with no payload`() {
        val header = "Path:audio\r\n".toByteArray(Charsets.US_ASCII)
        val frame = byteArrayOf(
            ((header.size shr 8) and 0xFF).toByte(),
            (header.size and 0xFF).toByte(),
        ) + header
        val parsed = edgeParseBinaryFrame(frame)!!
        assertEquals(0, parsed.second.size)
    }

    @Test
    fun `truncated frames yield null`() {
        assertNull(edgeParseBinaryFrame(byteArrayOf(0x00, 0x7F)))
        assertNull(edgeParseBinaryFrame(byteArrayOf()))
        assertNull(edgeParseBinaryFrame(byteArrayOf(0x00)))
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
        // CJK text percent-encodes; unknown when nothing remains.
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
