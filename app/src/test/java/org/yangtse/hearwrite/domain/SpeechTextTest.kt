package org.yangtse.hearwrite.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {

    // --- speakTextFromEntry ---

    @Test
    fun `plain entry speaks itself trimmed`() {
        assertEquals("apple", speakTextFromEntry(" apple "))
        assertEquals("", speakTextFromEntry(""))
        assertEquals("", speakTextFromEntry("   "))
    }

    @Test
    fun `pos and meaning columns are stripped before speaking`() {
        assertEquals("apple", speakTextFromEntry("apple | n. | 苹果"))
        assertEquals("处", speakTextFromEntry("处 | chù | 到处"))
    }

    @Test
    fun `fullwidth pipe is not stripped upstream parity`() {
        // speakTextFromEntry mirrors upstream: only the ASCII pipe is a delimiter.
        assertEquals("月｜yuè｜月亮", speakTextFromEntry("月｜yuè｜月亮"))
    }

    @Test
    fun `entry starting with pipe speaks nothing`() {
        assertEquals("", speakTextFromEntry("| n. | 苹果"))
    }

    @Test
    fun `you are expansion speaks the left side`() {
        assertEquals("you're", speakTextFromEntry("you're = you are"))
        assertEquals("you're", speakTextFromEntry("you're = you are | v."))
    }

    @Test
    fun `fullwidth equals also splits`() {
        assertEquals("you are", speakTextFromEntry("you are＝你是"))
    }

    @Test
    fun `empty left side falls back to whole text`() {
        assertEquals("= you are", speakTextFromEntry("= you are"))
    }

    @Test
    fun `only the first equals splits`() {
        assertEquals("a", speakTextFromEntry("a = b = c"))
    }

    // --- isCjkEntry ---

    @Test
    fun `cjk detection is based on the speakable headword only`() {
        assertTrue(isCjkEntry("月"))
        assertTrue(isCjkEntry("月 | yuè | 月亮"))
        assertTrue(isCjkEntry("月亮"))
        assertFalse(isCjkEntry("apple"))
        // Chinese meaning column does not make an English entry CJK.
        assertFalse(isCjkEntry("apple | n. | 苹果"))
        // Chinese left side of an expansion does.
        assertTrue(isCjkEntry("你 = you"))
    }

    // --- speakableMeaning ---

    @Test
    fun `missing or empty meaning speaks nothing`() {
        assertEquals("", speakableMeaning(null))
        assertEquals("", speakableMeaning(""))
        assertEquals("", speakableMeaning("   "))
    }

    @Test
    fun `plain meaning speaks verbatim`() {
        assertEquals("苹果", speakableMeaning("苹果"))
        assertEquals("使高兴", speakableMeaning(" 使高兴 "))
    }

    @Test
    fun `leading pos prefix is stripped`() {
        assertEquals("苹果", speakableMeaning("n. 苹果"))
        assertEquals("使高兴", speakableMeaning("vt. 使高兴"))
        assertEquals("苹果", speakableMeaning("N. 苹果")) // case-insensitive
    }

    @Test
    fun `only the first non-empty sense is spoken`() {
        assertEquals("使高兴", speakableMeaning("vt. 使高兴；vi. 发生"))
        assertEquals("苹果", speakableMeaning("n. 苹果; n. 梨"))
        assertEquals("苹果", speakableMeaning("；n. 苹果")) // skip empty first sense
    }

    @Test
    fun `parentheticals are not spoken`() {
        assertEquals("苹果", speakableMeaning("n.（一种）苹果"))
        assertEquals("苹果", speakableMeaning("n.(常吃的水果)苹果"))
    }

    @Test
    fun `edge punctuation is trimmed`() {
        assertEquals("苹果", speakableMeaning("苹果。"))
        assertEquals("苹果", speakableMeaning("，苹果，"))
        assertEquals("苹果", speakableMeaning("：苹果"))
        assertEquals("apple", speakableMeaning("apple。"))
    }

    @Test
    fun `pos-only sense speaks nothing and falls through`() {
        assertEquals("", speakableMeaning("n."))
        assertEquals("", speakableMeaning("n. "))
        assertEquals("", speakableMeaning("（补充说明）"))
        assertEquals("", speakableMeaning("vt. ；adj. "))
    }

    @Test
    fun `halfwidth chars count 0_5 width`() {
        // 15 ASCII letters = width 7.5 <= 12: no truncation.
        assertEquals("abcdefghijklmno", speakableMeaning("abcdefghijklmno"))
    }

    @Test
    fun `long sense without gloss boundary stays whole`() {
        val thirteen = "一二三四五六七八九十一二三"
        assertEquals(thirteen, speakableMeaning(thirteen)) // width 13, no split point
    }

    @Test
    fun `overlong sense is cut at first fullwidth gloss boundary`() {
        val cut = "一二三四五六七八九"
        assertEquals(cut, speakableMeaning("一二三四五六七八九，一二三四五六"))
        assertEquals(cut, speakableMeaning("一二三四五六七八九、一二三四五六"))
    }

    @Test
    fun `overlong sense is cut at first halfwidth gloss boundary`() {
        val cut = "一二三四五六七八九十"
        // 10 (CJK) + 0.5 (comma) + 4*0.5 (x) = 12.5 > 12 -> cut at ","
        assertEquals(cut, speakableMeaning("一二三四五六七八九十,xxxx"))
    }

    @Test
    fun `exactly max width is not truncated`() {
        // 12 CJK chars = width 12; truncation needs width > 12.
        val twelve = "一二三四五六七八九十甲乙"
        assertEquals(twelve, speakableMeaning(twelve))
    }

    @Test
    fun `cut respects the comma boundary then strips edges`() {
        val cut = "一二三四五六七八九十"
        assertEquals(cut, speakableMeaning("一二三四五六七八九十，等等。"))
    }

    @Test
    fun `ecdict style multi-sense gloss takes the pos-stripped first sense`() {
        assertEquals("使高兴", speakableMeaning("vt. 使高兴；n. 高兴"))
    }
}
