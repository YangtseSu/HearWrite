package org.yangtse.hearwrite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure OpenAI-compatible TTS provider helpers (AGENTS.md "TTS priority
 * chain" link 3): presets, URL builders, wire request bodies, clip hash /
 * cache names, base64 audio decoding and the stored-config JSON codec. The
 * expectations mirror alice `src/lib/ttsConfig.ts` / `src/lib/tts.ts`.
 */
class TtsProviderConfigTest {

    private val speechCfg = TtsProviderConfig(
        api = TtsApiKind.SPEECH,
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        apiKey = "k",
        model = "glm-tts",
        voiceEn = "tongtong",
        voiceZh = "tongtong",
        responseFormat = "wav",
    )
    private val chatCfg = TtsProviderConfig(
        api = TtsApiKind.CHAT,
        baseUrl = "https://api.xiaomimimo.com/v1",
        apiKey = "k",
        model = "mimo-v2.5-tts",
        voiceEn = "Chloe",
        voiceZh = "冰糖",
    )

    // ------------------------------------------------------------ presets

    @Test
    fun `presets are mimo and custom with mimo first`() {
        // 智谱 GLM / 硅基流动 / OpenAI were removed from the preset list by
        // the author's choice — 自定义 covers their endpoints.
        assertEquals(
            listOf("mimo", "custom"),
            TTS_PROVIDER_PRESETS.map { it.id },
        )
        val mimo = TTS_PROVIDER_PRESETS[0]
        assertEquals("小米 MiMo", mimo.label)
        assertEquals(TtsApiKind.CHAT, mimo.api)
        assertEquals("https://api.xiaomimimo.com/v1", mimo.baseUrl)
        assertEquals("mimo-v2.5-tts", mimo.model)
        assertEquals("Chloe", mimo.voiceEn)
        assertEquals("冰糖", mimo.voiceZh)
        val custom = TTS_PROVIDER_PRESETS[1]
        assertEquals("自定义", custom.label)
        assertEquals(TtsApiKind.SPEECH, custom.api)
        assertEquals("", custom.baseUrl)
        assertEquals("", custom.model)
        assertEquals("mimo", DEFAULT_TTS_PRESET.id)
    }

    @Test
    fun `preset fields stay editable - config completeness gates only url key model`() {
        val cfg = TtsProviderConfig(
            api = TtsApiKind.CHAT,
            baseUrl = " https://api.xiaomimimo.com/v1 ",
            apiKey = " sk-abc ",
            model = " mimo-v2.5-tts ",
            voiceEn = "",
            voiceZh = "",
        )
        assertTrue(cfg.isComplete)
        assertFalse(cfg.copy(baseUrl = "").isComplete)
        assertFalse(cfg.copy(apiKey = "  ").isComplete)
        assertFalse(cfg.copy(model = "").isComplete)
    }

    // ----------------------------------------------------------- URL forms

    @Test
    fun `speech url appends audio speech to a plain base`() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/audio/speech",
            speechUrl("https://open.bigmodel.cn/api/paas/v4"),
        )
    }

    @Test
    fun `speech url tolerates a trailing slash`() {
        assertEquals(
            "https://api.openai.com/v1/audio/speech",
            speechUrl("https://api.openai.com/v1/"),
        )
    }

    @Test
    fun `speech url passes through a full endpoint`() {
        assertEquals(
            "https://example.com/audio/speech",
            speechUrl("https://example.com/audio/speech"),
        )
    }

    // ------------------------------------------------------ voice + format

    @Test
    fun `mimo catalog holds the 8 official voices with labels`() {
        assertEquals(
            listOf("冰糖", "茉莉", "苏打", "白桦", "Mia", "Chloe", "Milo", "Dean"),
            MIMO_VOICES.map { it.first },
        )
        MIMO_VOICES.forEach { (id, label) ->
            assertTrue(label.startsWith(id))
            assertTrue(label.contains("女声") || label.contains("男声"))
        }
    }

    @Test
    fun `english text uses the default voice unless useDefaultEn is off`() {
        // Default on: one voice serves the whole list.
        assertEquals("冰糖", ttsProviderVoiceFor(chatCfg, "apple"))
        assertEquals("冰糖", ttsProviderVoiceFor(chatCfg, "苹果"))
        assertEquals("冰糖", ttsProviderVoiceFor(chatCfg, "苹果的英语怎么说"))
        val split = chatCfg.copy(useDefaultEn = false)
        assertEquals("Chloe", ttsProviderVoiceFor(split, "apple"))
        assertEquals("冰糖", ttsProviderVoiceFor(split, "苹果"))
        assertEquals("冰糖", ttsProviderVoiceFor(split, "苹果的英语怎么说"))
    }

    @Test
    fun `empty voice fields mean the provider default`() {
        val cfg = chatCfg.copy(voiceEn = "  ", voiceZh = "")
        assertEquals("", ttsProviderVoiceFor(cfg.copy(useDefaultEn = false), "apple"))
        assertEquals("", ttsProviderVoiceFor(cfg, "苹果"))
    }

    @Test
    fun `chat format is fixed wav regardless of responseFormat`() {
        assertEquals("wav", ttsProviderFormatFor(chatCfg))
        assertEquals("wav", ttsProviderFormatFor(chatCfg.copy(responseFormat = "mp3")))
    }

    @Test
    fun `speech format defaults to mp3 and lowercases the config value`() {
        assertEquals("wav", ttsProviderFormatFor(speechCfg))
        assertEquals("mp3", ttsProviderFormatFor(speechCfg.copy(responseFormat = null)))
        assertEquals("mp3", ttsProviderFormatFor(speechCfg.copy(responseFormat = "  ")))
        assertEquals("mp3", ttsProviderFormatFor(speechCfg.copy(responseFormat = "MP3")))
    }

    // ------------------------------------------------------- clip key hash

    @Test
    fun `clip hash is 8 hex digits and stable for identical inputs`() {
        val h1 = ttsProviderClipHash(chatCfg, "Apple", 0.9f)
        val h2 = ttsProviderClipHash(chatCfg, "apple", 0.9f)
        assertEquals(h1, h2)
        assertTrue(Regex("^[0-9a-f]{8}$").matches(h1))
    }

    @Test
    fun `clip hash changes when any seed component changes`() {
        // useDefaultEn off so the English text exercises voiceEn.
        fun hash(cfg: TtsProviderConfig = chatCfg.copy(useDefaultEn = false), text: String = "apple", rate: Float = 0.9f) =
            ttsProviderClipHash(cfg, text, rate)

        val base = hash()
        // The seed covers api, model, en voice (full/empty) and rate (×10);
        // the text itself never enters it (it rides the file name), and
        // baseUrl/responseFormat (chat ignores it) do not either. voiceZh
        // only enters CJK-text seeds (asserted in the next test).
        val alternatives = listOf(
            hash(cfg = chatCfg.copy(useDefaultEn = false, api = TtsApiKind.SPEECH)),
            hash(cfg = chatCfg.copy(useDefaultEn = false, model = "mimo-v1-tts")),
            hash(cfg = chatCfg.copy(useDefaultEn = false, voiceEn = "Aria")),
            hash(cfg = chatCfg.copy(useDefaultEn = false, voiceEn = "")),
            hash(rate = 1.0f),
        )
        assertTrue("every seed component must change the hash", alternatives.all { it != base })
        // Same voice + same config → same hash for a different text: the
        // text distinguishes clips via the file name, not the hash.
        assertEquals(base, hash(text = "pear"))
    }

    @Test
    fun `clip hash uses the language voice - same text hash differs per voice`() {
        val split = chatCfg.copy(useDefaultEn = false)
        val cjk = ttsProviderClipHash(split, "苹果", 0.9f)
        val en = ttsProviderClipHash(split, "apple", 0.9f)
        val enWordZhVoice = ttsProviderClipHash(split.copy(voiceEn = "Chloe"), "apple", 0.9f)
        assertEquals(en, enWordZhVoice) // unchanged voice → unchanged hash
        assertNotEquals(cjk, en)
        val cjkWithoutZhVoice = ttsProviderClipHash(split.copy(voiceZh = ""), "苹果", 0.9f)
        assertNotEquals(cjk, cjkWithoutZhVoice) // zh voice change → CJK clip regenerates
        // Default on: the English clip rides the default voice.
        assertEquals(cjk, ttsProviderClipHash(chatCfg, "apple", 0.9f))
    }

    // -------------------------------------------------- cache file naming

    @Test
    fun `clip file name is encoded text hash and format`() {
        // The space encodes as %20 like any reserved byte, then % → _.
        val name = ttsProviderClipFileName("Apple pie", chatCfg, 0.9f)
        val hash = ttsProviderClipHash(chatCfg, "Apple pie", 0.9f)
        assertEquals("apple_20pie.$hash.wav", name)
    }

    @Test
    fun `clip file name percent-encodes cjk text and falls back to unknown`() {
        val name = ttsProviderClipFileName("苹果", chatCfg, 0.9f)
        assertTrue(name.startsWith("_E8_8B_B9_E6_9E_9C."))
        assertTrue(name.endsWith(".wav"))
        assertEquals("unknown", ttsProviderClipFileName("   ", chatCfg, 0.9f).substringBefore('.'))
    }

    @Test
    fun `flight key is the hash plus lowercased text`() {
        assertEquals(
            "${ttsProviderClipHash(chatCfg, "Apple", 0.9f)}:apple",
            ttsProviderFlightKey("Apple", chatCfg, 0.9f),
        )
    }

    // ------------------------------------------------------ base64 decode

    @Test
    fun `base64 decode returns the raw bytes`() {
        val payload = java.util.Base64.getEncoder().encodeToString("hi".toByteArray())
        assertEquals("hi", String(ttsProviderBase64Decode(payload)))
    }

    @Test
    fun `base64 decode tolerates whitespace and a data url prefix`() {
        val wrapped = "YWJj\nZGVm\r\nZw=="
        assertEquals("abcdefg", String(ttsProviderBase64Decode(wrapped)))
        val dataUrl = "data:audio/wav;base64,aGVsbG8="
        assertEquals("hello", String(ttsProviderBase64Decode(dataUrl)))
    }

    @Test
    fun `malformed base64 degrades to an empty array`() {
        assertEquals(0, ttsProviderBase64Decode("!!!not base64!!!").size)
        assertEquals(0, ttsProviderBase64Decode("").size)
    }

    // ------------------------------------------ chat reply audio extraction

    @Test
    fun `audio data extracted from choices zero message audio`() {
        val reply = """{"choices":[{"message":{"audio":{"data":"aGVsbG8="}}}],"usage":{}}"""
        assertEquals("aGVsbG8=", ttsProviderAudioData(reply))
    }

    @Test
    fun `missing empty or malformed audio data yields empty string`() {
        assertEquals("", ttsProviderAudioData("""{"choices":[{"message":{"audio":{}}}]}"""))
        assertEquals("", ttsProviderAudioData("""{"choices":[]}"""))
        assertEquals("", ttsProviderAudioData("""{"error":"boom"}"""))
        assertEquals("", ttsProviderAudioData("not json"))
        assertEquals("", ttsProviderAudioData("""{"choices":[{"message":{"audio":{"data":123}}}]}"""))
    }

    // ------------------------------------------------------- request bodies

    @Test
    fun `speech body carries model input format speed and voice`() {
        val body = ttsSpeechRequestBody(speechCfg, "apple", 0.9f)
        assertEquals(
            """{"model":"glm-tts","input":"apple","response_format":"wav","speed":0.9,"voice":"tongtong"}""",
            body,
        )
    }

    @Test
    fun `speech speed clamps to the 0_25-4 range and blank voice is omitted`() {
        val cfg = speechCfg.copy(voiceEn = "", voiceZh = "", responseFormat = null)
        val slow = ttsSpeechRequestBody(cfg, "apple", 0.1f)
        assertEquals("""{"model":"glm-tts","input":"apple","response_format":"mp3","speed":0.25}""", slow)
        val fast = ttsSpeechRequestBody(cfg, "apple", 5f)
        assertEquals("""{"model":"glm-tts","input":"apple","response_format":"mp3","speed":4.0}""", fast)
    }

    @Test
    fun `chat body carries the assistant message and wav audio option`() {
        val body = ttsChatRequestBody(chatCfg, "苹果，一种很常见的水果")
        assertEquals(
            """{"model":"mimo-v2.5-tts","messages":[{"role":"assistant","content":"苹果，一种很常见的水果"}],"audio":{"format":"wav","voice":"冰糖"}}""",
            body,
        )
    }

    @Test
    fun `chat voice is omitted when empty and format stays wav`() {
        val cfg = chatCfg.copy(voiceZh = "", voiceEn = "  ")
        assertEquals(
            """{"model":"mimo-v2.5-tts","messages":[{"role":"assistant","content":"apple"}],"audio":{"format":"wav"}}""",
            ttsChatRequestBody(cfg, "apple"),
        )
    }

    // ------------------------------------------------ stored-config codec

    @Test
    fun `config codec round-trips every field`() {
        val cfg = TtsProviderConfig(
            api = TtsApiKind.SPEECH,
            baseUrl = "https://x.example/v1/",
            apiKey = "sk-abc",
            model = "m-1",
            voiceEn = "alice",
            voiceZh = "bob",
            useDefaultEn = false,
            responseFormat = "wav",
        )
        assertEquals(cfg, decodeTtsProviderConfig(encodeTtsProviderConfig(cfg)))
    }

    @Test
    fun `config codec defaults useDefaultEn on for legacy blobs`() {
        val raw = """{"api":"chat","baseUrl":"https://x","apiKey":"k","model":"m","future":1}"""
        assertEquals(true, decodeTtsProviderConfig(raw)!!.useDefaultEn)
    }

    @Test
    fun `config codec defaults the optional fields and tolerates unknown keys`() {
        val raw = """{"api":"chat","baseUrl":"https://x","apiKey":"k","model":"m","future":1}"""
        val decoded = decodeTtsProviderConfig(raw)!!
        assertEquals(TtsApiKind.CHAT, decoded.api)
        assertEquals("", decoded.voiceEn)
        assertEquals("", decoded.voiceZh)
        assertNull(decoded.responseFormat)
    }

    @Test
    fun `config codec rejects invalid api and garbage`() {
        assertNull(decodeTtsProviderConfig("""{"api":"sms","baseUrl":"https://x","apiKey":"k","model":"m"}"""))
        assertNull(decodeTtsProviderConfig("not json"))
        assertNull(decodeTtsProviderConfig("{}"))
    }

    @Test
    fun `config codec rejects non-string required fields instead of coercing them`() {
        // A numeric baseUrl must not decode to the string "5" (alice typeof check).
        assertNull(decodeTtsProviderConfig("""{"api":"speech","baseUrl":5,"apiKey":"k","model":"m"}"""))
        assertNull(decodeTtsProviderConfig("""{"api":"speech","baseUrl":"https://x","apiKey":7,"model":"m"}"""))
        assertNull(decodeTtsProviderConfig("""{"api":"chat","baseUrl":"https://x","apiKey":"k","model":[]}"""))
        assertNull(decodeTtsProviderConfig("""{"api":"speech","apiKey":"k","model":"m"}"""))
        // Optional voice fields coerce to their defaults instead of failing the blob.
        val decoded = decodeTtsProviderConfig(
            """{"api":"speech","baseUrl":"https://x","apiKey":"k","model":"m","voiceEn":5,"voiceZh":{"a":1}}""",
        )!!
        assertEquals("", decoded.voiceEn)
        assertEquals("", decoded.voiceZh)
    }

    // -------------------------------------------- per-preset storage codec

    @Test
    fun `config map round-trips one entry per preset`() {
        val map = mapOf(
            "mimo" to TtsProviderConfig(
                api = TtsApiKind.CHAT,
                baseUrl = "https://api.xiaomimimo.com/v1",
                apiKey = "k1",
                model = "mimo-v2.5-tts",
                voiceEn = "Chloe",
                voiceZh = "冰糖",
            ),
            "zhipu" to TtsProviderConfig(
                api = TtsApiKind.SPEECH,
                baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                apiKey = "k2",
                model = "glm-tts",
                responseFormat = "wav",
            ),
        )
        assertEquals(map, decodeTtsConfigMap(encodeTtsConfigMap(map)))
    }

    @Test
    fun `config map drops corrupt entries instead of failing the store`() {
        val raw = """
            {"mimo":{"api":"chat","baseUrl":"https://x","apiKey":"k","model":"m"},
             "broken":{"api":"sms","baseUrl":"https://x","apiKey":"k","model":"m"},
             "not-an-object":5}
        """.trimIndent()
        val decoded = decodeTtsConfigMap(raw)
        assertEquals(setOf("mimo"), decoded.keys)
        assertEquals("https://x", decoded["mimo"]!!.baseUrl)
    }

    @Test
    fun `config map tolerates garbage and empty objects`() {
        assertEquals(emptyMap<String, TtsProviderConfig>(), decodeTtsConfigMap("not json"))
        assertEquals(emptyMap<String, TtsProviderConfig>(), decodeTtsConfigMap("{}"))
    }

    @Test
    fun `legacy preset matching pairs baseUrl and model, else custom`() {
        assertEquals(
            "mimo",
            legacyTtsPresetId(
                TtsProviderConfig(TtsApiKind.CHAT, "https://api.xiaomimimo.com/v1", "k", "mimo-v2.5-tts"),
            ),
        )
        assertEquals(
            "custom",
            legacyTtsPresetId(TtsProviderConfig(TtsApiKind.SPEECH, "https://elsewhere/v1", "k", "m")),
        )
    }

    // --------------------------------------------------- HTTP error message

    @Test
    fun `http error message surfaces error message then falls back to the status`() {
        assertEquals(
            "The api key is invalid",
            ttsHttpErrorMessage(401, """{"error":{"message":"The api key is invalid"}}"""),
        )
        assertEquals("HTTP 401", ttsHttpErrorMessage(401, """{"error":{"code":"bad_key"}}"""))
        assertEquals("HTTP 502", ttsHttpErrorMessage(502, "<html>Bad Gateway</html>"))
        assertEquals("HTTP 429", ttsHttpErrorMessage(429, "rate limited"))
        assertEquals("HTTP 500", ttsHttpErrorMessage(500, ""))
    }

    @Test
    fun `config codec trims stored values`() {
        val decoded = decodeTtsProviderConfig(
            """{"api":"speech","baseUrl":" https://x/ ","apiKey":" sk-1 ","model":" m ","responseFormat":" wav "}""",
        )!!
        assertEquals("https://x/", decoded.baseUrl)
        assertEquals("sk-1", decoded.apiKey)
        assertEquals("wav", decoded.responseFormat)
    }
}
