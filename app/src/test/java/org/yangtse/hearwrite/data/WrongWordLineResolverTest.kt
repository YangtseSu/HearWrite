package org.yangtse.hearwrite.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.yangtse.hearwrite.domain.ResolvedWord
import org.yangtse.hearwrite.domain.multiSourceLabel
import org.yangtse.hearwrite.domain.resolvedRows
import org.yangtse.hearwrite.domain.resolvedText

/**
 * Locks the 错词本 → original-row resolution (Roadmap #7): 复习错词 / 听写错词
 * must dictate the stored 词性/释义 or 拼音/组词 row, not a bare headword.
 * The loaders are plain lambdas here — the asset/Room wiring lives in
 * HearWriteApplication and needs no test.
 */
class WrongWordLineResolverTest {

    private fun rowsOf(vararg lines: String): List<ResolvedWord> = resolvedRows(*lines)

    private val builtinRows = mapOf(
        "default_人教版小学语文_识字表" to rowsOf(
            "月 | yuè | 月亮",
            "明 | míng | 明天",
        ),
        "default_中考1600_核心词汇" to rowsOf(
            "apple | n. | 苹果",
            "banana | n. | 香蕉",
        ),
    )

    private fun resolver(
        history: Map<String, String> = emptyMap(),
        failBuiltin: Boolean = false,
    ) = WrongWordLineResolver(
        builtinListRows = { category, label ->
            if (failBuiltin) throw IllegalStateException("asset unavailable")
            builtinRows["default_${category}_$label"].orEmpty()
        },
        historyRows = { id -> history[id]?.let(::resolvedText).orEmpty() },
    )

    private fun mark(word: String, source: String? = null) =
        WrongWordMark(word, errorCount = 1, lastWrongAt = 0L, sourceLabel = source)

    @Test
    fun `built-in source restores the enriched row`() = runTest {
        val lines = resolver().rowsFor(
            listOf(
                mark("月", "default_人教版小学语文_识字表"),
                mark("apple", "default_中考1600_核心词汇"),
            ),
        )
        // Order follows the marks, and each word keeps its own columns
        // (拼音/组词 for 生字, 词性/释义 for English).
        assertEquals(rowsOf("月 | yuè | 月亮", "apple | n. | 苹果"), lines)
    }

    @Test
    fun `history source restores the stored row`() = runTest {
        val repo = resolver(
            history = mapOf("1712345678_abc123" to "apple | n. | 苹果\npear | n. | 梨"),
        )
        assertEquals(
            rowsOf("pear | n. | 梨"),
            repo.rowsFor(listOf(mark("pear", "1712345678_abc123"))),
        )
    }

    @Test
    fun `the run's own rows win over the mark's source`() = runTest {
        // A word dictated in this run must come back exactly as this run has
        // it, even when the book row points at another list.
        val lines = resolver().rowsFor(
            marks = listOf(mark("apple", "default_中考1600_核心词汇")),
            preferred = rowsOf("apple | n. | 苹果（本场）"),
        )
        assertEquals(rowsOf("apple | n. | 苹果（本场）"), lines)
    }

    @Test
    fun `manual marks stay bare headwords`() = runTest {
        assertEquals(rowsOf("plum"), resolver().rowsFor(listOf(mark("plum"))))
    }

    @Test
    fun `a source that no longer resolves degrades to the bare word`() = runTest {
        // Deleted history row, renamed list, unreadable asset — the mark must
        // survive as a plain word, and one failure must not take the others
        // down with it.
        val lines = resolver(failBuiltin = true).rowsFor(
            listOf(
                mark("apple", "default_中考1600_核心词汇"),
                mark("pear", "1712345678_gone"),
                mark("plum"),
            ),
        )
        assertEquals(rowsOf("apple", "pear", "plum"), lines)
    }

    @Test
    fun `a headword missing from its source stays a bare word`() = runTest {
        // The list is still there but the word is not in it (list edited).
        val lines = resolver().rowsFor(
            listOf(mark("grape", "default_中考1600_核心词汇")),
        )
        assertEquals(rowsOf("grape"), lines)
    }

    @Test
    fun `an expansion entry matches under its spoken headword`() = runTest {
        val repo = resolver(
            history = mapOf("h1" to "you're = you are | v. | 你是"),
        )
        assertEquals(
            rowsOf("you're = you are | v. | 你是"),
            repo.rowsFor(listOf(mark("you're", "h1"))),
        )
    }

    @Test
    fun `a multi-list source searches every member list`() = runTest {
        // 抽词听写 (Roadmap #9): marks carry the pool's member lists, so a word
        // drawn from the second list still resolves its columns.
        val source = multiSourceLabel(
            listOf("default_中考1600_核心词汇", "default_人教版小学语文_识字表"),
        )
        val lines = resolver().rowsFor(
            listOf(
                mark("apple", source),
                mark("月", source),
            ),
        )
        assertEquals(rowsOf("apple | n. | 苹果", "月 | yuè | 月亮"), lines)
    }

    @Test
    fun `a multi-list source whose members are gone degrades to bare words`() = runTest {
        val lines = resolver(failBuiltin = true).rowsFor(
            listOf(mark("apple", multiSourceLabel(listOf("default_中考1600_核心词汇")))),
        )
        assertEquals(rowsOf("apple"), lines)
    }
}
