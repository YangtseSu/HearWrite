package org.yangtse.hearwrite.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the offline list enrichment (AGENTS.md "Built-in library"): a bare
 * English word gains ECDICT 词性/释义, a bare single Chinese char gains
 * 拼音/组词 from `hanzi-meta.json`. Rows that already carry columns are never
 * rewritten, and an unknown headword stays bare rather than getting invented
 * data.
 *
 * The assets are read through the repository's seam, so this runs on the JVM
 * against a stub; the shipped asset files themselves are covered by
 * `DataFixtureTest` (`app/src/test/java/.../domain/`) and by
 * `python3 scripts/check-assets.py`.
 */
class DictionaryRepositoryTest {

    private val ecdict = """
        {"apple":"n.|苹果","banana":"v.|香蕉；芭蕉","a bit":"|一点儿","a/an":"art.|一个"}
    """.trimIndent()

    private val hanzi = """
        {"月":"yuè|岁月","行":"xíng|进行","好":"hǎo|良好"}
    """.trimIndent()

    private fun repository(
        ecdictJson: String = ecdict,
        hanziJson: String = hanzi,
    ) = DictionaryRepository { path ->
        when {
            path.endsWith("ecdict-meta.json") -> ecdictJson
            path.endsWith("hanzi-meta.json") -> hanziJson
            else -> throw IllegalArgumentException("unexpected asset $path")
        }
    }

    private fun ecdictRepository() = repository()

    @Test
    fun `bare english word gains pos and meaning`() = runTest {
        assertEquals(
            "apple | n. | 苹果\nbanana | v. | 香蕉；芭蕉",
            ecdictRepository().enrichText("apple\nbanana"),
        )
    }

    @Test
    fun `bare single chinese char gains pinyin and compound`() = runTest {
        // The 生字 shape: 拼音 in column 2, 组词 in column 3 — the same columns
        // a textbook row carries, so hints and 组词朗读 both work.
        assertEquals(
            "月 | yuè | 岁月\n行 | xíng | 进行",
            ecdictRepository().enrichText("月\n行"),
        )
    }

    @Test
    fun `char with a reading but no compound gains the pinyin hint alone`() = runTest {
        // hanzi-meta's hint-only shape (`很`: the char has no 组词 to derive):
        // the row must come out two columns wide, never with a dangling empty
        // column — and the 拼音 alone still anchors 组词朗读 at playback.
        val repo = repository(hanziJson = """{"很":"hěn|","吴":"wú|"}""")
        assertEquals("很 | hěn\n吴 | wú", repo.enrichText("很\n吴"))
    }

    @Test
    fun `rows that already carry columns stay untouched`() = runTest {
        val text = "apple | n. | 苹果\n月 | yuè | 月亮"
        assertEquals(text, ecdictRepository().enrichText(text))
    }

    @Test
    fun `multi-char chinese words and unknown headwords stay bare`() = runTest {
        // No offline source has 组词/拼音 for a multi-char Chinese word, and
        // 月 has no hanzi entry here — nothing is invented.
        val text = "香蕉\n月\nzzzz"
        assertEquals(text, repository(hanziJson = "{}").enrichText(text))
    }

    @Test
    fun `chinese word never gets an ecdict gloss`() = runTest {
        // ECDICT holds the odd pinyin-looking key; a Chinese headword must not
        // be looked up there, or a list row would gain an English gloss.
        val repo = repository(ecdictJson = """{"苹果":"n.|apple"}""")
        assertEquals("苹果", repo.enrichText("苹果"))
    }

    @Test
    fun `enrichment canonicalizes blank lines like parseWords`() = runTest {
        assertEquals(
            "apple | n. | 苹果\nbanana | v. | 香蕉；芭蕉",
            ecdictRepository().enrichText("  apple  \n\n\nbanana\n"),
        )
    }

    @Test
    fun `enrichLines keeps line count and order`() = runTest {
        assertEquals(
            listOf("apple | n. | 苹果", "月 | yuè | 岁月", "zzzz"),
            ecdictRepository().enrichLines(listOf("apple", "月", "zzzz")),
        )
    }

    @Test
    fun `lookupWordMeta is case insensitive and tolerates ocr noise`() = runTest {
        val repo = ecdictRepository()
        assertEquals(WordMeta("n.", "苹果"), repo.lookupWordMeta("Apple"))
        // Non-ASCII punctuation an OCR pass may leave behind is stripped before
        // the retry (the ASCII set `.`, `/`, `-`, `'` is intentionally kept —
        // real headwords carry them).
        assertEquals(WordMeta("n.", "苹果"), repo.lookupWordMeta("apple，"))
        // A slash-separated headword resolves as a whole.
        assertEquals(WordMeta("art.", "一个"), repo.lookupWordMeta("a/an"))
    }

    @Test
    fun `expand-style headword is looked up by its spoken left side`() = runTest {
        // `you're = you are` speaks the left side (AGENTS.md), so that is the
        // side the offline lookup must use.
        val repo = repository(ecdictJson = """{"you're":"|你是"}""")
        assertEquals("you're = you are |  | 你是", repo.enrichText("you're = you are"))
    }

    @Test
    fun `asset read failure propagates so callers fall back to the plain list`() = runTest {
        // One convention for both tables: the repository propagates a read
        // failure and every caller (Home, preview, wrong-word resolver) wraps
        // the call and degrades to the unenriched text.
        val repo = DictionaryRepository { path ->
            if (path.endsWith("ecdict-meta.json")) ecdict else throw java.io.FileNotFoundException(path)
        }
        assertTrue(runCatching { repo.enrichText("apple\n月") }.isFailure)
    }
}
