package org.yangtse.hearwrite.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Locks [cjkWordSpeech] against the REAL shipped tables (asset
 * `compounds/compounds.json` in `app/src/main/assets/`, read-only fixture — unit
 * tests run with the `app/` module as working directory).
 * Cases cover the AGENTS.md behavioral contract: meaning-column authority, the
 * learned-first ranking, polyphone syllable matching over the raw pool walk,
 * and the no-word-dedupe rule (澄|dèng would lose its only candidate if 澄清
 * rows were collapsed to the first cheng2 reading).
 */
class CjkWordSpeechTest {

    private companion object {
        val TABLES: CompoundTables = parseCompoundTables(
            File("src/main/assets/compounds/compounds.json").readText(),
        )
    }

    private fun speech(entry: String, learnedWords: List<String> = emptyList()): String =
        cjkWordSpeech(entry, TABLES, learnedWords)

    // ------------------------------------------------------ meaning column

    @Test
    fun `meaning column word speaks the textbook compound`() {
        // Tier 1: the row's own 组词 gloss is authoritative ("月亮的月", the
        // traditional classroom call) — no list context needed.
        assertEquals("月亮的月", speech("月 | yuè | 月亮"))
    }

    @Test
    fun `meaning senses are split and ranked by the common word table`() {
        // "出生、生长、生活" split into per-word candidates; 生活 is the most
        // common (rank 0) and wins over 出生/生长 (not in the common table).
        assertEquals("生活的生", speech("生 | shēng | 出生、生长、生活"))
    }

    @Test
    fun `textbook gloss is authoritative over the reading filter`() {
        // Meaning-column compounds are NOT pinyin-filtered: whatever the row's
        // gloss says is what gets spoken.
        assertEquals("朝阳的朝", speech("朝 | zhāo | 朝阳"))
        assertEquals("朝廷的朝", speech("朝 | cháo | 朝廷"))
    }

    // ------------------------------------------------------ learned pool

    @Test
    fun `learned pool beats the common pool fallback`() {
        // 月 has no meaning column here: tier 2 (人教版 learned row 月亮 yue4)
        // wins — the bare common-pool walk would have picked 岁月 instead.
        assertEquals("月亮的月", speech("月 | yuè"))
    }

    @Test
    fun `list words join the learned pool in appearance order`() {
        // 花蕾/花茶 are not in the common table: unranked, so appearance order
        // decides (meaning → list → learned rows).
        assertEquals("花蕾的花", speech("花 | huā", learnedWords = listOf("花蕾", "花茶")))
    }

    @Test
    fun `common-word rank beats appearance order inside the learned pool`() {
        // 棉花 (common rank 0) outranks the earlier list word 花茶 (unranked).
        assertEquals("棉花的花", speech("花 | huā", learnedWords = listOf("花茶", "棉花")))
    }

    // ------------------------------------------------ polyphone walk (tier 3)

    @Test
    fun `polyphone char picks the reading-matching compound`() {
        assertEquals("长期的长", speech("长 | cháng")) // 长期 chang2 passes first
        assertEquals("增长的长", speech("长 | zhǎng")) // 长期 chang2 ✗ → 增长 zhang3 ✓
    }

    @Test
    fun `bare polyphone walk takes the first passing reading row`() {
        // AGENTS.md real walk for 朝|zhāo: 朝鲜(chao2)✗ → 朝廷(chao2)✗ → 明朝(zhao1)✓.
        assertEquals("明朝的朝", speech("朝 | zhāo"))
        assertEquals("朝鲜的朝", speech("朝 | cháo"))
    }

    @Test
    fun `duplicate words with different readings are never deduped`() {
        // 澄 pool walks 澄清(cheng2)✗ → 澄清(deng4)✓. Deduping candidates by
        // word keeps only the first (cheng2) row and the dèng reading would be
        // left without any compound (regression guard, AGENTS.md ⚠️).
        assertEquals("澄清的澄", speech("澄 | dèng"))
    }

    // ------------------------------------------------------------ edge cases

    @Test
    fun `function words never compound`() {
        for (head in listOf("的", "地", "得", "着", "了", "吗", "呢", "吧", "啊", "呀", "啦", "嘛", "么")) {
            assertEquals("$head should speak bare", "", speech("$head | de | 好的"))
        }
    }

    @Test
    fun `non-single-char and non-cjk heads return empty`() {
        assertEquals("", speech("学校")) // multi-char CJK: spoken as-is twice
        assertEquals("", speech("apple | n. | 苹果"))
        assertEquals("", speech("banana"))
    }

    // ------------------------------------------------------- real list rows

    @Test
    fun `real textbook row speaks its gloss compound`() {
        // 人教版小学语文 / 二上 识字表 阅读 22.txt, verbatim row.
        assertEquals("朝代的朝", speech("朝 | cháo | 朝代"))
    }
}
