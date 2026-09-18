package org.yangtse.hearwrite.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `prepareStartLines` — the shared session-start ordering (AGENTS.md playback
 * engine): slice from the 起始序号, then optionally shuffle. Both launch pads
 * (Home and the 词库 preview) funnel through this one function, so a wrong
 * slice or a mutating shuffle would mis-start every dictation.
 */
class StartLinesTest {

    @Test
    fun `no shuffle keeps the tail from the start index`() {
        val lines = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("c", "d", "e"), prepareStartLines(lines, 2, shuffle = false))
    }

    @Test
    fun `zero start index keeps the whole list`() {
        val lines = listOf("a", "b", "c")
        assertEquals(lines, prepareStartLines(lines, 0, shuffle = false))
    }

    @Test
    fun `start index past the end clamps to the last word`() {
        val lines = listOf("a", "b", "c")
        assertEquals(listOf("c"), prepareStartLines(lines, 10, shuffle = false))
    }

    @Test
    fun `negative start index clamps to the first word`() {
        val lines = listOf("a", "b", "c")
        assertEquals(lines, prepareStartLines(lines, -3, shuffle = false))
    }

    @Test
    fun `single word list starts on the word regardless of index`() {
        assertEquals(listOf("only"), prepareStartLines(listOf("only"), 7, shuffle = false))
    }

    @Test
    fun `empty list yields an empty session`() {
        assertEquals(emptyList<String>(), prepareStartLines(emptyList(), 0, shuffle = false))
        assertEquals(emptyList<String>(), prepareStartLines(emptyList(), 4, shuffle = true))
    }

    @Test
    fun `shuffle is a permutation of the sliced tail`() {
        val lines = (1..20).map { "w$it" }
        val shuffled = prepareStartLines(lines, 5, shuffle = true)
        val tail = lines.drop(5)
        // Same words, same count — the engine plays every drawn word exactly once.
        assertEquals(tail.size, shuffled.size)
        assertEquals(tail.toSet(), shuffled.toSet())
    }

    @Test
    fun `shuffle of one word is that word`() {
        assertEquals(listOf("b"), prepareStartLines(listOf("a", "b"), 1, shuffle = true))
    }

    @Test
    fun `input list is never mutated`() {
        val lines = listOf("a", "b", "c").toMutableList()
        prepareStartLines(lines, 1, shuffle = true)
        assertEquals(listOf("a", "b", "c"), lines)
        assertTrue(lines is MutableList<String>)
    }
}
