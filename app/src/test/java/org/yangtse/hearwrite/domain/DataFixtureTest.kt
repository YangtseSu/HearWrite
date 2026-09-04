package org.yangtse.hearwrite.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Parses read-only fixtures from the bundled library (`app/src/main/assets/`,
 * unit tests run with the `app/` module as working directory). These lock the
 * parser against the real shipped lists — see AGENTS.md "Testing & QA".
 */
class DataFixtureTest {

    private fun listFile(relative: String): File =
        File("src/main/assets", relative).also {
            assertTrue("fixture missing: ${it.path}", it.isFile)
        }

    @Test
    fun `chinese writing-list rows parse into pinyin and compound columns`() {
        val file = listFile("人教版小学语文/二上 写字表 识字 1.txt")
        val lines = file.readLines().filter { it.isNotBlank() }
        assertEquals(7, lines.size)

        val entries = lines.map(::parseWordLine)
        assertEquals(
            listOf(
                WordEntry("处", "chù", "到处"),
                WordEntry("块", "kuài", "一块"),
                WordEntry("座", "zuò", "座位"),
            ),
            entries.take(3),
        )

        // Textbook 汉字 rows are CJK speakable entries: 处 -> zh-CN playback.
        assertTrue(entries.all { isCjkEntry(entryToLine(it)) })
        // Round-trip serialization is stable for every shipped row.
        entries.forEach { assertEquals(it, parseWordLine(entryToLine(it))) }
    }

    @Test
    fun `bare-word english lists parse to word-only entries`() {
        val file = listFile("中考1600/A.txt")
        val entries = parseWordEntries(file.readText())
        assertEquals(listOf("a/an", "ability", "able"), entries.take(3).map { it.word })
        assertTrue(entries.all { it.pos == null && it.meaning == null })
        assertTrue(entries.all { !isCjkEntry(it.word) })
    }

    @Test
    fun `enriched textbook rows keep blank pos and gloss spaces`() {
        val file = listFile("初中2182/第一册 常见.txt")
        val lines = file.readLines().filter { it.isNotBlank() }
        val entries = lines.map(::parseWordLine)

        assertEquals(
            WordEntry("what", "pron.", "什么"),
            entries[0],
        )
        assertEquals(
            // Real row with an empty pos column between the pipes.
            WordEntry("what's", null, "what is 的缩写形式"),
            entries[2],
        )

        // POS column normalizes without loss across every row of the file.
        for (entry in entries) {
            if (entry.pos != null) {
                assertEquals(entry.pos.trim().lowercase(), normalizePos(entry.pos))
            }
            assertTrue(entry.word.isNotEmpty())
        }
    }

    @Test
    fun `meaning glosses speak as their first pos-stripped sense`() {
        val file = listFile("初中2182/第一册 常见.txt")
        val entries = parseWordEntries(file.readText())
        for (entry in entries) {
            val speakable = speakableMeaning(entry.meaning)
            assertTrue("${entry.word}: meaning ${entry.meaning}", speakable.isNotEmpty())
            // Spoken gloss never starts with a POS abbreviation.
            assertNull(POS_PREFIX_RE.matchAt(speakable, 0))
        }
    }
}
