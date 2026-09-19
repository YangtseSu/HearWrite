package org.yangtse.hearwrite.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yangtse.hearwrite.domain.Ipa
import org.yangtse.hearwrite.domain.Sense
import org.yangtse.hearwrite.domain.parseWordLine
import org.yangtse.hearwrite.domain.rowToLine
import java.io.File
import java.io.FileNotFoundException

/**
 * Locks the lexicon's lookup/composition contract
 * (`docs/2026-09-18-DATA-MODEL.md` §1.3–1.4): lookup routes a headword to
 * exactly one asset, and [LexiconRepository.resolve] lets the row win **field
 * by field** — the rule that finally gives a 仁爱 row (which prints its own
 * 词性/释义) its textbook IPA. Rows are never written back: the resolved entry
 * is a read-time result and the row it came from is untouched (the display
 * composition built on top of it is locked by `WordDisplayTest`).
 *
 * Two halves: a stub-asset half driving the seam the way
 * `DictionaryRepositoryTest` used to, and a shipped-asset half that pins the
 * real 仁爱 / ipa-dict / missing-both cases (the Phase 2 acceptance).
 */
class LexiconRepositoryTest {

    private val english = """
        {"v":2,"entries":{
          "apple":{"s":[{"p":"n.","g":"苹果"}],"i":["ˈæpəɫ","ˈæpl"]},
          "fine":{"s":[{"p":"adj.","g":"身体好的"},{"p":"v.","g":"对……处以罚款"}],"i":["faɪn","faɪn"]},
          "let":{"s":[{"p":"v.","g":"让；允许"}],"i":["let","let"]},
          "a bit":{"s":[{"g":"一点儿"}]},
          "you're":{"s":[{"p":"abbr.","g":"你是"}]}
        }}
    """.trimIndent()

    private val hanzi = """
        {"v":2,"entries":{"月":{"p":"yuè","c":"岁月"},"行":{"p":"xíng","c":"进行"},"很":{"p":"hěn"}}}
    """.trimIndent()

    private fun repository(
        englishJson: String = english,
        hanziJson: String = hanzi,
    ) = LexiconRepository { path ->
        when {
            path.endsWith("lexicon-en.json") -> englishJson.byteInputStream()
            path.endsWith("lexicon-hanzi.json") -> hanziJson.byteInputStream()
            else -> throw FileNotFoundException(path)
        }
    }

    /** The shipped lexicon, read through the same seam the app uses. */
    private fun assetRepository() = LexiconRepository { path ->
        File("src/main/assets/$path").inputStream()
    }

    // ------------------------------------------------- lookup + composition

    @Test
    fun `a bare english row gains senses and ipa`() = runTest {
        val resolved = repository().resolve(parseWordLine("apple"))
        assertEquals(listOf(Sense("n.", "苹果")), resolved.senses)
        assertEquals(Ipa("ˈæpəɫ", "ˈæpl"), resolved.ipa)
    }

    @Test
    fun `a row with its own columns keeps them and still gains the ipa`() = runTest {
        // The 仁爱 case: 1,453 of the textbook's 1,806 rows print 词性 + 释义,
        // and the old "already has a column → leave the line alone" rule left
        // every one of them without a 音标. Field-by-field fallback fixes it.
        val row = parseWordLine("let | v. | 让；允许")
        val resolved = repository().resolve(row)
        assertEquals(listOf(Sense("v.", "让；允许")), resolved.senses)
        assertEquals(Ipa("let", "let"), resolved.ipa)
        assertEquals("let | v. | 让；允许", rowToLine(row))
    }

    @Test
    fun `a row carrying its own columns wins them field by field`() = runTest {
        // The row's pos/gloss are one column pair: a row that prints a 词性 and
        // no 释义 keeps its (empty) gloss rather than taking the lexicon's —
        // the row is the authority for what the list says.
        val resolved = repository().resolve(parseWordLine("apple | n."))
        assertEquals(listOf(Sense("n.", "")), resolved.senses)
        assertEquals(Ipa("ˈæpəɫ", "ˈæpl"), resolved.ipa)
    }

    @Test
    fun `a bare single chinese char gains pinyin and compound`() = runTest {
        val resolved = repository().resolve(parseWordLine("月"))
        assertEquals("yuè", resolved.pinyin)
        assertEquals("岁月", resolved.compound)
        assertNull(resolved.ipa) // 汉字 rows carry no 音标 (§9)
    }

    @Test
    fun `char with a reading but no compound gains the pinyin hint alone`() = runTest {
        // `很` has no derivable 组词 — the reading alone, not an empty gloss.
        val resolved = repository().resolve(parseWordLine("很"))
        assertEquals("hěn", resolved.pinyin)
        assertNull(resolved.compound)
    }

    @Test
    fun `a multi-char chinese word is never looked up in the english lexicon`() = runTest {
        val repo = repository(englishJson = """{"v":2,"entries":{"香蕉":{"s":[{"p":"n.","g":"banana"}]}}}""")
        val resolved = repo.resolve(parseWordLine("香蕉"))
        assertTrue(resolved.senses.isEmpty())
        assertNull(resolved.pinyin)
        assertNull(resolved.compound)
        assertEquals("香蕉", resolved.display)
        assertNull(repo.lookup("香蕉"))
    }

    @Test
    fun `a chinese-only list never parses the english lexicon`() = runTest {
        // The two assets are split so a 生字表 does not pay for 6.5 MB of
        // English dictionary: the English file may not even be readable.
        val repo = LexiconRepository { path ->
            if (path.endsWith("lexicon-hanzi.json")) hanzi.byteInputStream() else throw FileNotFoundException(path)
        }
        assertEquals("yuè", repo.resolve(parseWordLine("月")).pinyin)
    }

    @Test
    fun `rows that carry no columns stay bare and unknown words gain nothing`() = runTest {
        val repo = repository(hanziJson = """{"v":2,"entries":{}}""")
        val unknown = repo.resolve(parseWordLine("zzzz"))
        assertTrue(unknown.senses.isEmpty())
        assertNull(unknown.ipa)
        val chinese = repo.resolve(parseWordLine("香蕉"))
        assertTrue(chinese.senses.isEmpty())
        assertNull(chinese.compound)
    }

    @Test
    fun `senses stay structured, one entry per meaning`() = runTest {
        // `fine` — the old flat asset could only store this as one string with
        // its internal `；` lossily folded; the dial rebuilds the display text
        // from the list (WordDisplayTest).
        assertEquals(
            listOf(Sense("adj.", "身体好的"), Sense("v.", "对……处以罚款")),
            repository().resolve(parseWordLine("fine")).senses,
        )
    }

    @Test
    fun `lookup routes by kind and is case insensitive`() = runTest {
        val repo = repository()
        assertEquals(listOf(Sense("n.", "苹果")), repo.lookup("Apple")?.senses)
        assertEquals("yuè", repo.lookup("月")?.pinyin)
        assertNull(repo.lookup("zzzz"))
        assertNull(repo.lookup("  "))
    }

    @Test
    fun `lookup tolerates ocr noise and slash alternatives`() = runTest {
        // OCR leaves non-ASCII punctuation behind; the ASCII set (`.` `/` `-`
        // `'`) is kept because real headwords carry it.
        val repo = repository()
        assertEquals(listOf(Sense("n.", "苹果")), repo.lookup("apple，")?.senses)
        assertEquals(listOf(Sense("n.", "苹果")), repo.lookup("apple/banana")?.senses)
        assertEquals(listOf(Sense(null, "一点儿")), repo.lookup("a bit")?.senses)
    }

    @Test
    fun `an expansion row is looked up by its spoken left side`() = runTest {
        val resolved = repository().resolve(parseWordLine("you're = you are"))
        assertEquals("you're", resolved.speak)
        assertEquals("you're = you are", resolved.display)
        assertEquals(listOf(Sense("abbr.", "你是")), resolved.senses)
    }

    @Test
    fun `asset read failure propagates so callers fall back to the plain list`() = runTest {
        val repo = LexiconRepository { path -> throw java.io.FileNotFoundException(path) }
        assertTrue(runCatching { repo.resolve(parseWordLine("apple")) }.isFailure)
    }

    // ---------------------------------------------------------- warm-up

    @Test
    fun `warming the english table moves the parse off the first lookup`() = runTest {
        // The app warms the dictionary while the user is still picking a list,
        // so the lookup that would have paid the parse finds the table ready.
        // Pinned here as "the asset is read once, by the warm-up": a second
        // read would mean the first lookup paid the parse again.
        val reads = mutableListOf<String>()
        val repo = LexiconRepository { path ->
            reads += path
            when {
                path.endsWith("lexicon-en.json") -> english.byteInputStream()
                else -> throw FileNotFoundException(path)
            }
        }

        repo.warmEnglish()
        assertEquals(listOf("dict/lexicon-en.json"), reads)

        assertEquals(listOf(Sense("n.", "苹果")), repo.resolve(parseWordLine("apple")).senses)
        assertEquals(listOf("dict/lexicon-en.json"), reads) // served from memory
    }

    @Test
    fun `warming never reads the hanzi asset`() = runTest {
        // The warm-up targets the English dictionary alone: a reader that only
        // ever meets Chinese must not pay for 6.6 MB of it, and the reverse
        // holds too — warming must not touch the 汉字 table.
        val reads = mutableListOf<String>()
        val repo = LexiconRepository { path ->
            reads += path
            when {
                path.endsWith("lexicon-en.json") -> english.byteInputStream()
                path.endsWith("lexicon-hanzi.json") -> hanzi.byteInputStream()
                else -> throw FileNotFoundException(path)
            }
        }

        repo.warmEnglish()
        assertEquals(listOf("dict/lexicon-en.json"), reads)

        assertEquals("yuè", repo.resolve(parseWordLine("月")).pinyin)
        assertEquals(listOf("dict/lexicon-en.json", "dict/lexicon-hanzi.json"), reads)
    }

    // ------------------------------------------------- shipped assets (JVM)

    @Test
    fun `the renai textbook ipa wins over ipa-dict`() = runTest {
        // 七上 prints /ˈæpl/ and /let/ — ipa-dict's notation (ˈæpəɫ, lˈɛt)
        // must not surface for a headword the textbook covers, or the student
        // cannot line the app up with the printed page.
        assertEquals(Ipa("ˈæpl", "ˈæpl"), assetRepository().lookup("apple")?.ipa)
    }

    @Test
    fun `ipa-dict supplies both accents when the textbook has none`() = runTest {
        assertEquals(Ipa("bəˈnænə", "bəˈnɑːnə"), assetRepository().lookup("banana")?.ipa)
    }

    @Test
    fun `a word missing from both ipa sources keeps its senses without an ipa`() = runTest {
        val entry = assetRepository().lookup("taikonaut")
        assertEquals(listOf(Sense("n.", "中国太空人")), entry?.senses)
        assertNull(entry?.ipa)
    }

    @Test
    fun `a renai row keeps its own columns and gains the textbook ipa`() = runTest {
        val row = parseWordLine("let | v. | 让；允许") // 七上 Unit 1 导入, verbatim
        val resolved = assetRepository().resolve(row)
        assertEquals(listOf(Sense("v.", "让；允许")), resolved.senses)
        assertEquals(Ipa("let", "let"), resolved.ipa)
    }
}
