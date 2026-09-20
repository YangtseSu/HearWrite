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
                wordRowOf("处", "chù", "到处"),
                wordRowOf("块", "kuài", "一块"),
                wordRowOf("座", "zuò", "座位"),
            ),
            entries.take(3),
        )

        // Textbook 汉字 rows are CJK speakable entries: 处 -> zh-CN playback.
        assertTrue(entries.all { it.kind != WordKind.EN })
        // Round-trip serialization is stable for every shipped row.
        entries.forEach { assertEquals(it, parseWordLine(rowToLine(it))) }
    }

    @Test
    fun `bare-word english lists parse to word-only entries`() {
        val file = listFile("中考1600/A.txt")
        val entries = parseWordRows(file.readText())
        assertEquals(listOf("a/an", "ability", "able"), entries.take(3).map { it.display })
        assertTrue(entries.all { it.pos == null && it.gloss == null })
        assertTrue(entries.all { it.kind == WordKind.EN })
    }

    @Test
    fun `textbook rows keep blank pos and gloss spaces`() {
        val file = listFile("初中2182/第一册 常见.txt")
        val lines = file.readLines().filter { it.isNotBlank() }
        val entries = lines.map(::parseWordLine)

        assertEquals(
            wordRowOf("what", "pron.", "什么"),
            entries[0],
        )
        assertEquals(
            // Real row with an empty pos column between the pipes.
            wordRowOf("what's", null, "what is 的缩写形式"),
            entries[2],
        )

        // POS column normalizes without loss across every row of the file.
        for (entry in entries) {
            if (entry.pos != null) {
                assertEquals(entry.pos.trim().lowercase(), normalizePos(entry.pos))
            }
            assertTrue(entry.display.isNotEmpty())
        }
    }

    @Test
    fun `meaning glosses speak as their first pos-stripped sense`() {
        val file = listFile("初中2182/第一册 常见.txt")
        val entries = parseWordRows(file.readText())
        for (entry in entries) {
            val speakable = speakableMeaning(entry.gloss)
            assertTrue("${entry.display}: meaning ${entry.gloss}", speakable.isNotEmpty())
            // Spoken gloss never starts with a POS abbreviation.
            assertNull(POS_PREFIX_RE.matchAt(speakable, 0))
        }
    }

    @Test
    fun `renai textbook rows carry pos and a speakable gloss`() {
        val files = File("src/main/assets/仁爱版初中").listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("七上 Unit 1") }
            .sortedBy { it.name }
        assertEquals(4, files.size)

        val entries = files.flatMap { parseWordRows(it.readText()) }
        assertEquals(wordRowOf("let", "v.", "让；允许"), entries.first())
        // A multi-POS textbook row keeps the dominant POS in column 2 and marks
        // the other senses inline — the convention the ECDICT-derived lists use.
        val fine = entries.single { it.display == "fine" }
        assertEquals("adj.", fine.pos)
        assertEquals("身体好的，健康的；很好；v. 对……处以罚款；n. 罚款", fine.gloss)
        assertEquals("身体好的，健康的", speakableMeaning(fine.gloss))

        for (entry in entries) {
            assertTrue("${entry.display} has no pos", entry.pos != null)
            assertTrue("${entry.display} has no gloss", !entry.gloss.isNullOrBlank())
            val speakable = speakableMeaning(entry.gloss)
            assertNull("${entry.display}: ${entry.gloss}", POS_PREFIX_RE.matchAt(speakable, 0))
        }

        // The OCR source carried IPA transcriptions and page references; neither
        // may reach a gloss, and a spoken gloss must never open with a POS token
        // (TTS spells `n.` out). An empty speakable gloss is legal — the
        // textbook's `the`/`yes` rows carry usage notes only, and 朗读释义 then
        // says nothing rather than reading the note aloud.
        val all = File("src/main/assets/仁爱版初中").listFiles().orEmpty()
            .filter { it.isFile && it.extension == "txt" }
        assertEquals(78, all.size)
        for (file in all) {
            for (entry in parseWordRows(file.readText())) {
                val where = "${file.name}: ${entry.display}"
                val meaning = entry.gloss.orEmpty()
                assertTrue("$where has no gloss", meaning.isNotEmpty())
                assertTrue("$where leaks IPA: $meaning", IPA_RE.find(meaning) == null)
                assertTrue("$where leaks a page ref: $meaning", PAGE_REF_RE.find(meaning) == null)
                val speakable = speakableMeaning(meaning)
                assertNull("$where: $meaning", POS_PREFIX_RE.matchAt(speakable, 0))
            }
        }
    }

    private companion object {
        /** An `/…/` transcription — the OCR pages carry two per headword. */
        val IPA_RE = Regex("""/(?![^/]*[\u4e00-\u9fff])[^/]{1,60}?/""")

        /** The textbook's page reference, `(1)` … `(107)` / `（26）`. */
        val PAGE_REF_RE = Regex("""[（(]\s*\d{1,3}\s*[)）]""")
    }

    // --- 义务教育语文课程标准 字表: a single Chinese char per line ---

    private fun zibiao(label: String): List<String> =
        listFile("义务教育语文课程标准/$label.txt").readLines().filter { it.isNotBlank() }

    @Test
    fun `curriculum character lists hold one bare char per line`() {
        val expected = mapOf(
            "基本字表 300" to 300,
            "常用字表1 2500" to 2500,
            "常用字表2 1000" to 1000,
        )
        for ((label, count) in expected) {
            val lines = zibiao(label)
            assertEquals("$label row count", count, lines.size)
            assertEquals("$label unique chars", count, lines.toSet().size)
            // Bare single Han chars: the app fills 拼音/组词 from
            // `dict/lexicon-hanzi.json` at display time, and a stray column
            // here would ship as literal text.
            lines.forEach { line ->
                assertEquals("$label row $line", 1, line.length)
                assertTrue("$label row $line", parseWordLine(line).pos == null)
            }
        }
    }

    @Test
    fun `curriculum lists nest as the standard defines them`() {
        val basic = zibiao("基本字表 300").toSet()
        val first = zibiao("常用字表1 2500").toSet()
        val second = zibiao("常用字表2 1000").toSet()

        // 表一 + 表二 are the two halves of the 3500 常用字表: disjoint, and
        // the 300-char 基本字表 is drawn from that 3500 (the standard prints
        // them in one sequence).
        assertEquals(emptySet<Char>(), first intersect second)
        assertEquals(3500, (first + second).size)
        assertEquals("基本字表 outside the 常用字表", emptySet<Char>(), basic - (first + second))
    }

    @Test
    fun `every curriculum char has an offline pinyin hint`() {
        // The lists are bare chars — their hints come entirely from
        // `dict/lexicon-hanzi.json`. A char with no entry would dictate with
        // an empty dial, so the shipped asset must cover the standard's 3500.
        val lexicon = listFile("dict/lexicon-hanzi.json").readText()
        val chars = zibiao("基本字表 300") + zibiao("常用字表1 2500") + zibiao("常用字表2 1000")
        val missing = chars.filter { !lexicon.contains("\"$it\":") }
        assertEquals("chars without a lexicon-hanzi entry", emptyList<String>(), missing)
    }
}
