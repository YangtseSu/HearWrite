package org.yangtse.hearwrite.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordLineParserTest {

    // --- parseWords: one entry per non-empty line ---

    @Test
    fun `parseWords splits trims and drops blank lines`() {
        val input = "  apple  \r\n\r\n banana\n\ncherry \n"
        assertEquals(listOf("apple", "banana", "cherry"), parseWords(input))
    }

    @Test
    fun `parseWords handles lone CRLF separators`() {
        assertEquals(listOf("a", "b"), parseWords("a\r\nb"))
    }

    // --- parseWordLine: 1 or 3 columns, fullwidth pipe accepted ---

    @Test
    fun `bare word parses to word only`() {
        assertEquals(WordEntry("apple"), parseWordLine("apple"))
        assertEquals(WordEntry("Good morning"), parseWordLine("  Good morning  "))
    }

    @Test
    fun `three ascii columns parse into word pos meaning`() {
        assertEquals(
            WordEntry("apple", "n.", "苹果"),
            parseWordLine("apple | n. | 苹果"),
        )
    }

    @Test
    fun `fullwidth pipe is accepted as column delimiter`() {
        assertEquals(
            WordEntry("月", "yuè", "月亮"),
            parseWordLine("月｜yuè｜月亮"),
        )
    }

    @Test
    fun `mixed ascii and fullwidth pipes work`() {
        assertEquals(
            WordEntry("apple", "n.", "苹果"),
            parseWordLine("apple | n.｜苹果"),
        )
    }

    @Test
    fun `blank pos column parses to null`() {
        // Real textbook row: what's |  | what is 的缩写形式
        val entry = parseWordLine("what's |  | what is 的缩写形式")
        assertEquals(WordEntry("what's", null, "what is 的缩写形式"), entry)
    }

    @Test
    fun `blank meaning column parses to null`() {
        assertEquals(WordEntry("apple", "n.", null), parseWordLine("apple | n. |  "))
    }

    @Test
    fun `columns beyond meaning are ignored`() {
        assertEquals(
            WordEntry("apple", "n.", "苹果"),
            parseWordLine("apple | n. | 苹果 | extra | ignored"),
        )
    }

    @Test
    fun `whitespace around columns is trimmed but inner spaces survive`() {
        assertEquals(
            WordEntry("Good morning", "adj.", "好的,令人愉快的"),
            parseWordLine("  Good morning  |  adj.  |  好的,令人愉快的 "),
        )
    }

    @Test
    fun `blank line parses to empty-word entry`() {
        assertEquals(WordEntry(""), parseWordLine("   "))
    }

    @Test
    fun `parseWordEntries maps every line`() {
        val entries = parseWordEntries("月 | yuè | 月亮\n\napple\n果 |  | 苹果")
        assertEquals(
            listOf(
                WordEntry("月", "yuè", "月亮"),
                WordEntry("apple"),
                WordEntry("果", null, "苹果"),
            ),
            entries,
        )
    }

    // --- entryToLine: canonical serialization, round-trip stable ---

    @Test
    fun `entryToLine bare word has no pipes`() {
        assertEquals("hello", entryToLine(WordEntry("hello")))
    }

    @Test
    fun `entryToLine keeps empty columns as separators`() {
        assertEquals("apple | n. | 苹果", entryToLine(WordEntry("apple", "n.", "苹果")))
        assertEquals("what's |  | what is 的缩写形式", entryToLine(WordEntry("what's", null, "what is 的缩写形式")))
        assertEquals("apple | n. | ", entryToLine(WordEntry("apple", "n.", null)))
    }

    @Test
    fun `entryToLine round trips through parseWordLine`() {
        val entries = listOf(
            WordEntry("hello"),
            WordEntry("apple", "n.", "苹果"),
            WordEntry("what's", null, "what is 的缩写形式"),
            WordEntry("月", "yuè", "月亮"),
            WordEntry("", null, null),
        )
        for (entry in entries) {
            assertEquals(entry, parseWordLine(entryToLine(entry)))
        }
    }

    // --- normalizePos: ECDICT -> textbook mapping ---

    @Test
    fun `normalizePos maps abbreviations`() {
        assertEquals("adj.", normalizePos("a."))
        assertEquals("n.", normalizePos("pl."))
        assertEquals("int.", normalizePos("interj."))
        assertEquals("int.", normalizePos("exclam."))
        assertEquals("n.", normalizePos("na."))
        assertEquals("n.", normalizePos("un."))
        assertEquals("n.", normalizePos("pla."))
        assertEquals("n.", normalizePos("pn."))
        assertEquals("v.", normalizePos("vbl."))
        assertEquals("v.", normalizePos("pp."))
        assertEquals("abbr.", normalizePos("pref."))
        assertEquals("abbr.", normalizePos("suf."))
        assertEquals("abbr.", normalizePos("suff."))
        assertEquals("abbr.", normalizePos("comb."))
        assertEquals("abbr.", normalizePos("stuff."))
    }

    @Test
    fun `normalizePos is case-insensitive and trims`() {
        assertEquals("n.", normalizePos(" N. "))
        assertEquals("adj.", normalizePos("A."))
        assertEquals("v.", normalizePos("VBL."))
    }

    @Test
    fun `normalizePos keeps unknown pos lowercased`() {
        assertEquals("n.", normalizePos("n."))
        assertEquals("vt.", normalizePos("vt."))
        assertEquals("quant.", normalizePos("Quant."))
        assertEquals("xyz.", normalizePos("XYZ."))
    }

    // --- POS_PREFIX_RE: what counts as a leading POS ---

    @Test
    fun `pos prefix regex matches with trailing whitespace`() {
        assertEquals("vt. ", POS_PREFIX_RE.matchAt("vt. 使高兴", 0)?.value)
        assertEquals("N. ", POS_PREFIX_RE.matchAt("N. 苹果", 0)?.value)
        assertEquals("a. ", POS_PREFIX_RE.matchAt("a. 好的", 0)?.value)
    }

    @Test
    fun `pos prefix regex does not match plain words`() {
        assertNull(POS_PREFIX_RE.matchAt("able", 0))
        assertNull(POS_PREFIX_RE.matchAt("使高兴", 0))
        assertNull(POS_PREFIX_RE.matchAt("artful", 0)) // "art." needs the dot
    }

    @Test
    fun `pos prefix regex prefers longest literal alternative`() {
        // "art." must win over "a.", "vt." over "v."
        assertTrue(POS_PREFIX_RE.matchAt("art. 一件", 0)?.value?.startsWith("art.") == true)
        assertTrue(POS_PREFIX_RE.matchAt("vt. 使高兴", 0)?.value?.startsWith("vt.") == true)
        assertTrue(POS_PREFIX_RE.matchAt("vi. 发生", 0)?.value?.startsWith("vi.") == true)
    }
}
