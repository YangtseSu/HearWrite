package org.yangtse.hearwrite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure system-voice enumeration/labeling helpers ([SystemSpeaker]'s 音色
 * picker data). Android exposes raw engine voice ids with no display name
 * and no gender field — these helpers filter the engine voice set to the
 * dictation languages and derive the picker labels (a readable engine name
 * when there is one, otherwise 中文男声1/中文女声1/中文语音1-style).
 */
class SystemSpeakerTest {

    private fun v(
        name: String,
        language: String,
        country: String = "",
        network: Boolean = false,
    ) = SystemEngineVoice(name, language, country, network)

    // ------------------------------------------------- language filtering

    @Test
    fun `zh language detection`() {
        assertTrue(systemLangIsZh("zh-CN"))
        assertTrue(systemLangIsZh("zh"))
        assertTrue(!systemLangIsZh("en-US"))
    }

    @Test
    fun `zh voices keep mainland mandarin only`() {
        val set = setOf(
            v("cmn-cn-x-ssa-local", "cmn", "CN"),
            v("cmn-cn-x-ssa-network", "cmn", "CN"), // same voice — dedupe
            v("zh-cn-x-txc-network", "zh", "CN"),
            v("cmn-x-ssa-network", "cmn"), // no country — generic
            v("zh-tw-x-ssa-network", "zh", "TW"), // traditional — out
            v("yue-CN-local", "yue", "CN"), // Cantonese — out
        )
        val got = systemVoicesFor(set, "zh-CN")
        assertEquals(3, got.size)
        assertTrue(got.none { it.networkRequired })
        assertTrue(got.any { it.name == "cmn-cn-x-ssa-local" })
    }

    @Test
    fun `en voices keep us english only and dedupe transport twins`() {
        val set = setOf(
            v("en-us-x-sfg-local", "en", "US"),
            v("en-us-x-sfg-network", "en", "US"), // twin — dedupe
            v("en-gb-x-rjs-local", "en", "GB"), // out — GB
            v("en-x-ssa-network", "en"), // no country — keep
            v("en-US-language", "en", "US"), // locale pseudo — out
        )
        val got = systemVoicesFor(set, "en-US")
        assertEquals(2, got.size)
        assertTrue(got.none { it.name.endsWith("-language") })
    }

    // ----------------------------------------------- English regions

    @Test
    fun `en region detection from voice key`() {
        assertEquals(SYSTEM_EN_REGION_GB, systemEnRegionOf("en-gb-x-gba-local"))
        assertEquals(SYSTEM_EN_REGION_GB, systemEnRegionOf("en-GB-language"))
        assertEquals(SYSTEM_EN_REGION_US, systemEnRegionOf("en-us-x-iob-local"))
        assertEquals(SYSTEM_EN_REGION_US, systemEnRegionOf("en-US-language"))
        assertEquals(SYSTEM_EN_REGION_US, systemEnRegionOf(""))
    }

    @Test
    fun `region voices split us from gb`() {
        val set = setOf(
            v("en-us-x-sfg-local", "en", "US"),
            v("en-us-x-sfg-network", "en", "US"),
            v("en-gb-x-gba-local", "en", "GB"),
            v("en-gb-x-gba-network", "en", "GB"),
            v("en-gb-x-rjs-local", "en", "GB"),
            v("en-au-x-aua-local", "en", "AU"), // neither region — out
        )
        val us = systemEnVoicesForRegion(set, SYSTEM_EN_REGION_US)
        val gb = systemEnVoicesForRegion(set, SYSTEM_EN_REGION_GB)
        assertEquals(listOf("en-us-x-sfg-local"), us.map { it.name })
        assertEquals(listOf("en-gb-x-gba-local", "en-gb-x-rjs-local"), gb.map { it.name })
    }

    @Test
    fun `us fallback keeps countryless en voices`() {
        val set = setOf(
            v("en-x-ssa-network", "en"),
            v("en-us-x-sfg-local", "en", "US"),
        )
        val us = systemEnVoicesForRegion(set, SYSTEM_EN_REGION_US)
        assertEquals(2, us.size)
    }

    // ------------------------------------------- merged en picker

    @Test
    fun `merged en list orders us then gb then countryless`() {
        val set = setOf(
            v("en-gb-x-rjs-local", "en", "GB"),
            v("en-us-x-sfg-local", "en", "US"),
            v("en-x-ssa-network", "en"), // countryless
            v("en-us-x-sfg-network", "en", "US"), // twin — dedupe
        )
        val all = systemEnVoicesAll(set)
        assertEquals(
            listOf("en-us-x-sfg-local", "en-gb-x-rjs-local", "en-x-ssa-network"),
            all.map { it.name },
        )
    }

    @Test
    fun `merged en labels number within region`() {
        val set = setOf(
            v("en-us-x-sfg-local", "en", "US"),
            v("en-us-x-iob-local", "en", "US"),
            v("en-gb-x-rjs-local", "en", "GB"),
            v("en-x-ssa-network", "en"), // countryless
        )
        val infos = systemEnVoiceInfosAll(set)
        assertEquals(
            listOf("美式英语1", "美式英语2", "英式英语1", "英文语音1"),
            infos.map { it.label },
        )
    }

    @Test
    fun `merged en readable names get region prefix`() {
        val set = setOf(
            v("com.apple.voice.compact.en-GB.Daniel", "en", "GB"),
            v("com.apple.voice.compact.en-US.Samantha", "en", "US"),
        )
        val infos = systemEnVoiceInfosAll(set)
        assertEquals(listOf("美式 Samantha", "英式 Daniel"), infos.map { it.label })
    }

    @Test
    fun `language pseudo entries are dropped`() {
        val set = setOf(
            v("zh-CN-language", "zh", "CN"),
            v("cmn-cn-x-ssa-local", "cmn", "CN"),
        )
        val got = systemVoicesFor(set, "zh-CN")
        assertEquals(listOf("cmn-cn-x-ssa-local"), got.map { it.name })
    }

    @Test
    fun `stem strips transport suffixes`() {
        assertEquals("cmn-cn-x-ssa", systemVoiceStem("cmn-cn-x-ssa-local"))
        assertEquals("cmn-cn-x-ssa", systemVoiceStem("cmn-cn-x-ssa-network"))
        assertEquals("zh-CN-language", systemVoiceStem("zh-CN-language"))
        assertEquals("plain-name", systemVoiceStem("plain-name"))
    }

    @Test
    fun `sorted list is deterministic`() {
        val set = setOf(
            v("cmn-cn-x-ssa-network", "cmn", "CN", network = true),
            v("cmn-cn-x-ssa-local", "cmn", "CN"),
            v("cmn-cn-x-ssa#male_1-network", "cmn", "CN", network = true),
        )
        val got = systemVoicesFor(set, "zh-CN")
        // Unmarked stem dedupes to its local twin; the #male_1 marker is a
        // distinct voice character and survives. Embedded before network.
        assertEquals(
            listOf("cmn-cn-x-ssa-local", "cmn-cn-x-ssa#male_1-network"),
            got.map { it.name },
        )
    }

    // --------------------------------------------------- display names

    @Test
    fun `meaningful engine names are kept as display names`() {
        assertEquals(
            "Samantha",
            systemVoiceDisplayName(v("com.apple.voice.compact.en-US.Samantha", "en", "US")),
        )
        // A plain readable engine name (e.g. a service voice name).
        assertEquals("Google 中文普通话（女声）", systemVoiceDisplayName(v("Google 中文普通话（女声）", "zh", "CN")))
    }

    @Test
    fun `google-style raw ids get no display name`() {
        assertEquals("", systemVoiceDisplayName(v("cmn-cn-x-ssa-local", "cmn", "CN")))
        assertEquals("", systemVoiceDisplayName(v("cmn-cn-x-ssa#female_1-local", "cmn", "CN")))
        assertEquals("", systemVoiceDisplayName(v("zh-cn-x-txc-network", "zh", "CN")))
        assertEquals("", systemVoiceDisplayName(v("en-us-x-sfg#male_2-local", "en", "US")))
        assertEquals("", systemVoiceDisplayName(v("cmn-cn-x-tts-network", "cmn", "CN")))
    }

    // -------------------------------------------------------- gender

    @Test
    fun `gender from google hash markers`() {
        assertEquals("女", systemVoiceGender(v("cmn-cn-x-ssa#female_1-local", "cmn", "CN")))
        assertEquals("男", systemVoiceGender(v("cmn-cn-x-ssa#male_1-local", "cmn", "CN")))
        assertEquals("", systemVoiceGender(v("cmn-cn-x-ssa-local", "cmn", "CN")))
        assertEquals("", systemVoiceGender(v("zh-cn-x-txc-network", "zh", "CN")))
        assertEquals("", systemVoiceGender(v("cmn-cn-x-txc-network", "cmn", "CN")))
    }

    // ------------------------------------------------- voice routing

    @Test
    fun `cjk text always uses the default zh voice`() {
        assertEquals("zh", resolveSystemVoice("月亮", "zh", "en", false))
        assertEquals("zh", resolveSystemVoice("月亮", "zh", "en", true))
        assertEquals("zh", resolveSystemVoice("  苹果 apple ", "zh", "en", false))
    }

    @Test
    fun `english text uses the dedicated voice only when default is off`() {
        assertEquals("en", resolveSystemVoice("apple", "zh", "en", false))
        assertEquals("zh", resolveSystemVoice("apple", "zh", "en", true))
        assertEquals("zh", resolveSystemVoice("  hello ", "zh", "en", true))
    }

    // ------------------------------------------- fallback label rows

    @Test
    fun `fallback names group by gender for zh`() {
        val voices = listOf(
            v("cmn-cn-x-ssa#female_1-local", "cmn", "CN"),
            v("cmn-cn-x-ssa#female_2-local", "cmn", "CN"),
            v("cmn-cn-x-ssa#male_1-local", "cmn", "CN"),
            v("cmn-cn-x-ssa#male_2-local", "cmn", "CN"),
            v("cmn-cn-x-ssa-local", "cmn", "CN"),
        )
        val infos = systemVoiceInfos(voices, "zh-CN")
        assertEquals(
            listOf("中文女声1", "中文女声2", "中文男声1", "中文男声2", "中文语音1"),
            infos.map { it.label },
        )
    }

    @Test
    fun `fallback names use en prefix for english`() {
        val voices = listOf(
            v("en-us-x-sfg#female_1-local", "en", "US"),
            v("en-us-x-sfg#male_1-local", "en", "US"),
            v("en-us-x-sfg-local", "en", "US"),
        )
        val infos = systemVoiceInfos(voices, "en-US")
        assertEquals(
            listOf("英文女声1", "英文男声1", "英文语音1"),
            infos.map { it.label },
        )
    }

    @Test
    fun `readable names bypass the fallback numbering`() {
        val voices = listOf(
            v("com.apple.voice.compact.zh-CN.Tingting", "zh", "CN"),
            v("cmn-cn-x-ssa#male_1-local", "cmn", "CN"),
        )
        val infos = systemVoiceInfos(voices, "zh-CN")
        // The meaningful name keeps its own label; only the raw id is numbered.
        assertEquals("Tingting", infos[0].label)
        assertEquals("中文男声1", infos[1].label)
        assertEquals("", infos[0].gender)
        assertEquals("男", infos[1].gender)
    }
}
