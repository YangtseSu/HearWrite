package org.yangtse.hearwrite.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the 错词本 → original-line resolution (Roadmap #7): 复习错词 / 听写错词
 * must dictate the stored 词性/释义 or 拼音/组词 line, not a bare headword.
 * The loaders are plain lambdas here — the asset/Room wiring lives in
 * HearWriteApplication and needs no test.
 */
class WrongWordLineResolverTest {

    private val builtinLines = mapOf(
        "default_人教版小学语文_识字表" to listOf(
            "月 | yuè | 月亮",
            "明 | míng | 明天",
        ),
        "default_中考1600_核心词汇" to listOf(
            "apple | n. | 苹果",
            "banana | n. | 香蕉",
        ),
    )

    private fun resolver(
        history: Map<String, String> = emptyMap(),
        failBuiltin: Boolean = false,
    ) = WrongWordLineResolver(
        builtinListLines = { category, label ->
            if (failBuiltin) throw IllegalStateException("asset unavailable")
            builtinLines["default_${category}_$label"].orEmpty()
        },
        historyText = { id -> history[id] },
    )

    private fun mark(word: String, source: String? = null) =
        WrongWordMark(word, errorCount = 1, lastWrongAt = 0L, sourceLabel = source)

    @Test
    fun `built-in source restores the enriched line`() = runTest {
        val lines = resolver().linesFor(
            listOf(
                mark("月", "default_人教版小学语文_识字表"),
                mark("apple", "default_中考1600_核心词汇"),
            ),
        )
        // Order follows the marks, and each word keeps its own columns
        // (拼音/组词 for 生字, 词性/释义 for English).
        assertEquals(listOf("月 | yuè | 月亮", "apple | n. | 苹果"), lines)
    }

    @Test
    fun `history source restores the stored line`() = runTest {
        val repo = resolver(
            history = mapOf("1712345678_abc123" to "apple | n. | 苹果\npear | n. | 梨"),
        )
        assertEquals(
            listOf("pear | n. | 梨"),
            repo.linesFor(listOf(mark("pear", "1712345678_abc123"))),
        )
    }

    @Test
    fun `the run's own lines win over the mark's source`() = runTest {
        // A word dictated in this run must come back exactly as this run has
        // it, even when the book row points at another list.
        val lines = resolver().linesFor(
            marks = listOf(mark("apple", "default_中考1600_核心词汇")),
            preferred = listOf("apple | n. | 苹果（本场）"),
        )
        assertEquals(listOf("apple | n. | 苹果（本场）"), lines)
    }

    @Test
    fun `manual marks stay bare headwords`() = runTest {
        assertEquals(listOf("plum"), resolver().linesFor(listOf(mark("plum"))))
    }

    @Test
    fun `a source that no longer resolves degrades to the bare word`() = runTest {
        // Deleted history row, renamed list, unreadable asset — the mark must
        // survive as a plain word, and one failure must not take the others
        // down with it.
        val lines = resolver(failBuiltin = true).linesFor(
            listOf(
                mark("apple", "default_中考1600_核心词汇"),
                mark("pear", "1712345678_gone"),
                mark("plum"),
            ),
        )
        assertEquals(listOf("apple", "pear", "plum"), lines)
    }

    @Test
    fun `a headword missing from its source stays a bare word`() = runTest {
        // The list is still there but the word is not in it (list edited).
        val lines = resolver().linesFor(
            listOf(mark("grape", "default_中考1600_核心词汇")),
        )
        assertEquals(listOf("grape"), lines)
    }

    @Test
    fun `an expansion entry matches under its spoken headword`() = runTest {
        val repo = resolver(
            history = mapOf("h1" to "you're = you are | v. | 你是"),
        )
        assertEquals(
            listOf("you're = you are | v. | 你是"),
            repo.linesFor(listOf(mark("you're", "h1"))),
        )
    }
}
