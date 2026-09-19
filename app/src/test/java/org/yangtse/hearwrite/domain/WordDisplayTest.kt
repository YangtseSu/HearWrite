package org.yangtse.hearwrite.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks how a [ResolvedWord] becomes text (`docs/2026-09-18-DATA-MODEL.md`
 * §4.2): the dial's **one** hint line (`音标 + 词性`), the lists' 2-line meta
 * (both accents, 英 first) and the 释义/组词 text. Every row surface stitches
 * these segments, so a change here is a change on four screens at once.
 */
class WordDisplayTest {

    private fun word(
        display: String,
        senses: List<Sense> = emptyList(),
        pinyin: String? = null,
        compound: String? = null,
        ipa: Ipa? = null,
    ): ResolvedWord {
        val row = wordRowOf(display)
        return ResolvedWord(display, row.speak, row.kind, senses, pinyin, compound, ipa)
    }

    // ----------------------------------------------------------- ipaLabels

    @Test
    fun `both accents are labelled, english first`() {
        assertEquals("英 /let/ · 美 /lɛt/", ipaLabels(Ipa(us = "lɛt", uk = "let")))
    }

    @Test
    fun `a single accent is printed alone and still labelled`() {
        assertEquals("美 /lɛt/", ipaLabels(Ipa(us = "lɛt", uk = null)))
        assertEquals("英 /let/", ipaLabels(Ipa(us = null, uk = "let")))
    }

    @Test
    fun `no transcription leaves no segment, never a placeholder`() {
        assertNull(ipaLabels(null))
        assertNull(ipaLabels(Ipa(us = null, uk = null)))
        assertNull(ipaLabels(Ipa(us = "  ", uk = "")))
    }

    // ------------------------------------------------------------- dialIpa

    @Test
    fun `the dial speaks american only`() {
        assertEquals("/lɛt/", dialIpa(Ipa(us = "lɛt", uk = "let")))
        assertNull(dialIpa(Ipa(us = null, uk = "let")))
        assertNull(dialIpa(null))
    }

    // ------------------------------------------------------------ dialHint

    @Test
    fun `an english hint composes the american ipa with the part of speech`() {
        val apple = word("apple", senses = listOf(Sense("n.", "苹果")), ipa = Ipa("ˈæpəl", "ˈæpl"))
        assertEquals("/ˈæpəl/ n.", apple.dialHint())
    }

    @Test
    fun `an english hint degrades to the part it has`() {
        assertEquals("n.", word("apple", senses = listOf(Sense("n.", "苹果"))).dialHint())
        assertEquals(
            "/ˈæpəl/",
            word("apple", ipa = Ipa(us = "ˈæpəl", uk = null)).dialHint(),
        )
        assertNull(word("apple").dialHint())
    }

    @Test
    fun `a single chinese char hints its pinyin and a word hints nothing`() {
        assertEquals("yuè", word("月", pinyin = "yuè", compound = "月亮").dialHint())
        assertEquals(null, word("月亮").dialHint())
    }

    // ----------------------------------------------------------- glossText

    @Test
    fun `a later sense spells its own part of speech inline`() {
        // The shape the old flat asset stored: the dial's gloss line is one
        // string, so a sense past the first has to name its own 词性.
        val fine = word(
            "fine",
            senses = listOf(Sense("adj.", "身体好的"), Sense("v.", "对……处以罚款")),
        )
        assertEquals("身体好的；v. 对……处以罚款", fine.glossText())
    }

    @Test
    fun `an entry with nothing to say has no gloss text`() {
        assertNull(word("apple").glossText())
        assertNull(word("apple", senses = listOf(Sense("n.", ""))).glossText())
        assertNull(word("月亮").glossText())
    }

    @Test
    fun `a chinese row glosses its 组词`() {
        assertEquals("月亮", word("月", pinyin = "yuè", compound = "月亮").glossText())
    }

    // ------------------------------------------------------------ listMeta

    @Test
    fun `an english row shows both accents, its 词性 and its 释义`() {
        val let = word(
            "let",
            senses = listOf(Sense("v.", "让，允许")),
            ipa = Ipa(us = "let", uk = "let"),
        )
        assertEquals("英 /let/ · 美 /let/ · v. · 让，允许", let.listMeta())
    }

    @Test
    fun `an english row without a transcription omits that segment`() {
        val keen = word("keen", senses = listOf(Sense("adj.", "热衷的")))
        assertEquals("adj. · 热衷的", keen.listMeta())
    }

    @Test
    fun `a chinese row shows pinyin and 组词, never an ipa segment`() {
        assertEquals("yuè 月亮", word("月", pinyin = "yuè", compound = "月亮").listMeta())
        assertEquals("hěn", word("很", pinyin = "hěn").listMeta())
    }

    @Test
    fun `a multi-char chinese word has no meta line`() {
        assertNull(word("月亮").listMeta())
    }
}
