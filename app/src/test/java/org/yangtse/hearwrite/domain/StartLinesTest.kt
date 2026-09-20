package org.yangtse.hearwrite.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `prepareStartRows` — the shared session-start ordering (AGENTS.md playback
 * engine): slice from the 起始序号, then optionally shuffle. Both launch pads
 * (Home and the 词库 preview) funnel through this one function, so a wrong
 * slice or a mutating shuffle would mis-start every dictation.
 */
class StartLinesTest {

    private fun rows(vararg words: String): List<ResolvedWord> = words.map(::bareResolvedWord)

    @Test
    fun `no shuffle keeps the tail from the start index`() {
        val pool = rows("a", "b", "c", "d", "e")
        assertEquals(rows("c", "d", "e"), prepareStartRows(pool, 2, shuffle = false))
    }

    @Test
    fun `zero start index keeps the whole list`() {
        val pool = rows("a", "b", "c")
        assertEquals(pool, prepareStartRows(pool, 0, shuffle = false))
    }

    @Test
    fun `start index past the end clamps to the last word`() {
        val pool = rows("a", "b", "c")
        assertEquals(rows("c"), prepareStartRows(pool, 10, shuffle = false))
    }

    @Test
    fun `negative start index clamps to the first word`() {
        val pool = rows("a", "b", "c")
        assertEquals(pool, prepareStartRows(pool, -3, shuffle = false))
    }

    @Test
    fun `single word list starts on the word regardless of index`() {
        assertEquals(rows("only"), prepareStartRows(rows("only"), 7, shuffle = false))
    }

    @Test
    fun `empty list yields an empty session`() {
        assertEquals(emptyList<ResolvedWord>(), prepareStartRows(emptyList(), 0, shuffle = false))
        assertEquals(emptyList<ResolvedWord>(), prepareStartRows(emptyList(), 4, shuffle = true))
    }

    @Test
    fun `shuffle is a permutation of the sliced tail`() {
        val pool = (1..20).map { bareResolvedWord("w$it") }
        val shuffled = prepareStartRows(pool, 5, shuffle = true)
        val tail = pool.drop(5)
        // Same words, same count — the engine plays every drawn word exactly once.
        assertEquals(tail.size, shuffled.size)
        assertEquals(tail.toSet(), shuffled.toSet())
    }

    @Test
    fun `shuffle of one word is that word`() {
        assertEquals(rows("b"), prepareStartRows(rows("a", "b"), 1, shuffle = true))
    }

    @Test
    fun `input list is never mutated`() {
        val pool = rows("a", "b", "c").toMutableList()
        prepareStartRows(pool, 1, shuffle = true)
        assertEquals(rows("a", "b", "c"), pool)
    }
}
