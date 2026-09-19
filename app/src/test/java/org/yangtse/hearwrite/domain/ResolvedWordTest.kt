package org.yangtse.hearwrite.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the composition rule's **degraded** half (`docs/2026-09-18-DATA-MODEL.md`
 * §1.4): with no dictionary behind a row, the row's own columns are what is
 * left, and a 错词本 mark whose source is gone still becomes a usable row.
 * Three production call sites depend on it — the display pass, the library
 * preview's first frame and the 抽词听写 pool all fall back to it when the
 * lexicon cannot be read. The dictionary-backed half (row wins field by field,
 * IPA filled in) runs through the asset seam in `LexiconRepositoryTest`.
 */
class ResolvedWordTest {

    @Test
    fun `an english row keeps its own 词性 and 释义 with no dictionary behind it`() {
        val resolved = resolveWord(parseWordLine("let | v. | 让；允许"), entry = null, hanzi = null)
        assertEquals(listOf(Sense("v.", "让；允许")), resolved.senses)
        assertNull(resolved.ipa)
    }

    @Test
    fun `a chinese row keeps its own 拼音 and 组词 with no dictionary behind it`() {
        val resolved = resolveWord(parseWordLine("月 | yuè | 月亮"), entry = null, hanzi = null)
        assertEquals("yuè", resolved.pinyin)
        assertEquals("月亮", resolved.compound)
    }

    @Test
    fun `a bare headword composes into a row that dictates itself`() {
        val resolved = bareResolvedWord("you're = you are")
        assertEquals("you're = you are", resolved.display)
        assertEquals("you're", resolved.speak)
        assertEquals(WordKind.EN, resolved.kind)
        assertTrue(resolved.senses.isEmpty())
    }

    @Test
    fun `a bare chinese headword resolves to the hanzi kind`() {
        assertEquals(WordKind.HANZI, bareResolvedWord("月").kind)
        assertEquals(WordKind.WORD, bareResolvedWord("月亮").kind)
    }
}
