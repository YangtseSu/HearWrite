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
    fun `bare word parses to display only`() {
        assertEquals(wordRowOf("apple"), parseWordLine("apple"))
        assertEquals(wordRowOf("Good morning"), parseWordLine("  Good morning  "))
    }

    @Test
    fun `three ascii columns parse into display pos gloss`() {
        assertEquals(
            wordRowOf("apple", "n.", "苹果"),
            parseWordLine("apple | n. | 苹果"),
        )
    }

    @Test
    fun `fullwidth pipe is accepted as column delimiter`() {
        assertEquals(
            wordRowOf("月", "yuè", "月亮"),
            parseWordLine("月｜yuè｜月亮"),
        )
    }

    @Test
    fun `mixed ascii and fullwidth pipes work`() {
        assertEquals(
            wordRowOf("apple", "n.", "苹果"),
            parseWordLine("apple | n.｜苹果"),
        )
    }

    @Test
    fun `blank pos column parses to null`() {
        // Real textbook row: what's |  | what is 的缩写形式
        val row = parseWordLine("what's |  | what is 的缩写形式")
        assertEquals(wordRowOf("what's", null, "what is 的缩写形式"), row)
    }

    @Test
    fun `blank gloss column parses to null`() {
        assertEquals(wordRowOf("apple", "n.", null), parseWordLine("apple | n. |  "))
    }

    @Test
    fun `columns beyond gloss are ignored`() {
        // The .txt format is 1–3 columns (AGENTS.md "Word-line format"); a
        // stray 4th column is dropped rather than read as anything.
        assertEquals(
            wordRowOf("apple", "n.", "苹果"),
            parseWordLine("apple | n. | 苹果 | extra | ignored"),
        )
    }

    @Test
    fun `whitespace around columns is trimmed but inner spaces survive`() {
        assertEquals(
            wordRowOf("Good morning", "adj.", "好的,令人愉快的"),
            parseWordLine("  Good morning  |  adj.  |  好的,令人愉快的 "),
        )
    }

    @Test
    fun `blank line parses to empty row`() {
        assertEquals(wordRowOf(""), parseWordLine("   "))
    }

    @Test
    fun `parseWordRows maps every line`() {
        val rows = parseWordRows("月 | yuè | 月亮\n\napple\n果 |  | 苹果")
        assertEquals(
            listOf(
                wordRowOf("月", "yuè", "月亮"),
                wordRowOf("apple"),
                wordRowOf("果", null, "苹果"),
            ),
            rows,
        )
    }

    // --- speak: derived once at parse time, not re-derived per consumer ---

    @Test
    fun `bare headword speaks itself`() {
        assertEquals("apple", parseWordLine("  apple  ").speak)
        assertEquals("Good morning", parseWordLine("Good morning").speak)
        assertEquals("", parseWordLine("   ").speak)
    }

    @Test
    fun `columns never reach the spoken text`() {
        assertEquals("apple", parseWordLine("apple | n. | 苹果").speak)
        assertEquals("处", parseWordLine("处 | chù | 到处").speak)
        // Fullwidth pipes strip the same way.
        assertEquals("月", parseWordLine("月｜yuè｜月亮").speak)
        // A line that starts with the delimiter has no headword to speak.
        assertEquals("", parseWordLine("| n. | 苹果").speak)
    }

    @Test
    fun `you are expansion speaks the left side`() {
        assertEquals("you're", parseWordLine("you're = you are").speak)
        assertEquals("you're", parseWordLine("you're = you are | v.").speak)
        assertEquals("Mr", parseWordLine("Mr=mister | n. | 先生(用于姓名前)").speak)
    }

    @Test
    fun `fullwidth equals also splits`() {
        assertEquals("you are", parseWordLine("you are＝你是").speak)
    }

    @Test
    fun `empty left side falls back to whole text`() {
        assertEquals("= you are", parseWordLine("= you are").speak)
    }

    @Test
    fun `only the first equals splits`() {
        assertEquals("a", parseWordLine("a = b = c").speak)
    }

    // --- kind: decided by the speakable headword alone ---

    @Test
    fun `kind follows the speakable headword`() {
        assertEquals(WordKind.EN, parseWordLine("apple").kind)
        assertEquals(WordKind.EN, parseWordLine("good morning | n. | 早上好").kind)
        assertEquals(WordKind.HANZI, parseWordLine("月").kind)
        assertEquals(WordKind.HANZI, parseWordLine("月 | yuè | 月亮").kind)
        assertEquals(WordKind.WORD, parseWordLine("月亮").kind)
        assertEquals(WordKind.WORD, parseWordLine("生日快乐").kind)
    }

    @Test
    fun `a chinese gloss column never makes an english row chinese`() {
        assertEquals(WordKind.EN, parseWordLine("apple | n. | 苹果").kind)
        assertEquals(WordKind.EN, parseWordLine("you're = you are | v.").kind)
        // A Chinese `=` tail is gloss-like text too — it must not re-kind a row.
        assertEquals(WordKind.EN, parseWordLine("apple = 苹果").kind)
    }

    @Test
    fun `kindOf is the single source of the classification`() {
        assertEquals(WordKind.EN, kindOf(""))
        assertEquals(WordKind.EN, kindOf("apple"))
        assertEquals(WordKind.HANZI, kindOf("月"))
        assertEquals(WordKind.WORD, kindOf("月亮"))
        // It classifies the *speakable* text it is handed: a two-char Chinese
        // expansion left side is WORD, not HANZI.
        assertEquals(WordKind.HANZI, kindOf("你"))
        assertEquals(WordKind.WORD, kindOf("你好"))
        // `wordRowOf` reads the kind off `speak`, so the `= ` tail never counts.
        with(wordRowOf("你 = you")) {
            assertEquals(WordKind.HANZI, kind)
            assertEquals("你", speak)
        }
        with(wordRowOf("apple = 苹果")) {
            assertEquals(WordKind.EN, kind)
            assertEquals("apple", speak)
        }
    }

    // --- rowToLine: canonical serialization, round-trip stable ---

    @Test
    fun `rowToLine bare word has no pipes`() {
        assertEquals("hello", rowToLine(wordRowOf("hello")))
    }

    @Test
    fun `rowToLine keeps empty columns as separators`() {
        assertEquals("apple | n. | 苹果", rowToLine(wordRowOf("apple", "n.", "苹果")))
        assertEquals("what's |  | what is 的缩写形式", rowToLine(wordRowOf("what's", null, "what is 的缩写形式")))
        // A missing trailing column is dropped: the hint-only 生字 shape
        // (`字 | 拼音`) is what an author writes for a char with no 组词, and a
        // dangling `| ` would ship as a visible empty column.
        assertEquals("apple | n.", rowToLine(wordRowOf("apple", "n.", null)))
        assertEquals("很 | hěn", rowToLine(wordRowOf("很", "hěn", null)))
    }

    @Test
    fun `rowToLine round trips through parseWordLine`() {
        val rows = listOf(
            wordRowOf("hello"),
            wordRowOf("apple", "n.", "苹果"),
            wordRowOf("what's", null, "what is 的缩写形式"),
            wordRowOf("月", "yuè", "月亮"),
            wordRowOf(""),
        )
        for (row in rows) {
            assertEquals(row, parseWordLine(rowToLine(row)))
        }
    }

    // --- findResolvedByHeadword: the 错词本 key lookup ---

    @Test
    fun `headword lookup returns the resolved row, not the bare word`() {
        val rows = resolvedRows("apple | n. | 苹果", "月 | yuè | 月亮", "pear")
        assertEquals(Sense("n.", "苹果"), findResolvedByHeadword(rows, "apple")?.senses?.single())
        assertEquals("yuè", findResolvedByHeadword(rows, "月")?.pinyin)
        assertEquals("月亮", findResolvedByHeadword(rows, "月")?.compound)
        // A row without columns comes back as a bare one.
        assertEquals(emptyList<Sense>(), findResolvedByHeadword(rows, "pear")?.senses)
    }

    @Test
    fun `headword lookup matches the spoken side of an expansion`() {
        // The book keys on the speakable headword, so `you're` must find its
        // expansion row rather than the raw text.
        val rows = resolvedRows("you're = you are")
        assertEquals("you're = you are", findResolvedByHeadword(rows, "you're")?.display)
    }

    @Test
    fun `unknown headword has no row`() {
        val rows = resolvedRows("apple", "pear")
        assertEquals(null, findResolvedByHeadword(rows, "plum"))
        assertEquals(null, findResolvedByHeadword(emptyList(), "apple"))
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
        assertTrue(POS_PREFIX_RE.matchAt("art. 一件", 0)?.value?.startsWith("art.") == true)
        assertTrue(POS_PREFIX_RE.matchAt("vt. 使高兴", 0)?.value?.startsWith("vt.") == true)
        assertTrue(POS_PREFIX_RE.matchAt("vi. 发生", 0)?.value?.startsWith("vi.") == true)
    }

    @Test
    fun `BOM and unicode whitespace are stripped at line edges js parity`() {
        assertEquals(listOf("月", "apple"), parseWords("\uFEFF月\uFEFF\n\u3000apple"))
        assertEquals("月", parseWordLine("\uFEFF月 | yuè | 月亮").display)
        assertEquals("月", parseWordLine("\uFEFF月 | yuè | 月亮").speak)
        assertEquals("yuè", parseWordLine("\uFEFF月 | yuè | 月亮").pos)
        assertEquals("apple", parseWordLine("\uFEFFapple | n. | 苹果").speak)
        // A BOM-only or fullwidth-space-only line is blank after the parity trim.
        assertEquals(emptyList<String>(), parseWords("\u3000\u3000\n\uFEFF"))
    }
}
