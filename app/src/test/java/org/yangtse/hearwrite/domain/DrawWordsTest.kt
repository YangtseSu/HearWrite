package org.yangtse.hearwrite.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Locks the 抽词听写 pool contract (Roadmap #9): cross-list dedup keys on the
 * speakable headword (the book's key) and the draw is without replacement,
 * clamped to the pool.
 */
class DrawWordsTest {

    @Test
    fun `dedupe keeps the first occurrence in list order`() {
        val lines = listOf("apple", "pear", "apple", "banana", "pear")
        assertEquals(listOf("apple", "pear", "banana"), dedupeByHeadword(lines))
    }

    @Test
    fun `dedupe matches a headword across columns and expansion forms`() {
        // The book keys on the spoken headword, so `月 | yuè | 月亮` and a bare
        // `月` are one candidate, and `you're = you are` matches `you're`.
        val lines = listOf(
            "月 | yuè | 月亮",
            "月",
            "you're = you are",
            "you're",
        )
        assertEquals(listOf("月 | yuè | 月亮", "you're = you are"), dedupeByHeadword(lines))
    }

    @Test
    fun `sample draws distinct lines of the requested size`() {
        val pool = (1..30).map { "word$it" }
        val drawn = sampleWords(pool, 7, Random(42))
        assertEquals(7, drawn.size)
        assertEquals(7, drawn.toSet().size)
        assertTrue(drawn.all { it in pool })
    }

    @Test
    fun `sample clamps to the pool and keeps every line`() {
        val pool = listOf("a", "b", "c")
        assertEquals(3, sampleWords(pool, 10, Random(1)).size)
        assertEquals(pool.toSet(), sampleWords(pool, 10, Random(1)).toSet())
    }

    @Test
    fun `sample of a non-positive count draws nothing`() {
        assertEquals(emptyList<String>(), sampleWords(listOf("a", "b"), 0))
        assertEquals(emptyList<String>(), sampleWords(listOf("a", "b"), -3))
        assertEquals(emptyList<String>(), sampleWords(emptyList(), 5))
    }

    @Test
    fun `sample actually randomizes rather than taking from the front`() {
        // Guards the "shuffle + take" drawing against a take-from-front
        // regression: the first element must not always be the pool's first.
        val pool = listOf("a", "b", "c", "d")
        val firsts = (1..40).map { sampleWords(pool, 1, Random(it)).single() }.toSet()
        assertTrue("expected several different first draws, got $firsts", firsts.size > 1)
    }
}
