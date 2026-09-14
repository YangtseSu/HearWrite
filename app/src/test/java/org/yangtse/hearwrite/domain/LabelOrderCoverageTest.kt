package org.yangtse.hearwrite.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The `HANZI` table's coverage contract (docs/WORDLIST.md §5 step 3): every CJK
 * char in a built-in category or list name must carry its (pinyin, stroke) key,
 * or [compareLabels] silently falls back to code-point order for it.
 *
 * `LabelOrderTest` cannot see this: its golden fixture is regenerated **with
 * `compareLabels`**, so a missing char reorders both sides identically and the
 * parity assertions stay green — `4012f66` shipped 60 missing chars that way.
 * This test reads the shipped asset names instead of the comparator's output,
 * which is what makes it a guard rather than a mirror.
 */
class LabelOrderCoverageTest {

    @Test
    fun `every CJK char in a library name has a comparator entry`() {
        val assets = File("src/main/assets")
        assertTrue("assets dir missing: ${assets.absolutePath}", assets.isDirectory)

        // Category dirs are library names too; dict/compounds/audio are their
        // own asset kinds, not categories (docs/WORDLIST.md §2).
        val names = buildList {
            for (dir in assets.listFiles().orEmpty().filter { it.isDirectory }) {
                if (dir.name in setOf("dict", "compounds", "audio")) continue
                add(dir.name)
                dir.listFiles().orEmpty()
                    .filter { it.isFile && it.extension == "txt" }
                    .forEach { add(it.nameWithoutExtension) }
            }
        }
        // A vacuous pass (wrong working directory) would defeat the guard.
        assertTrue("no library names found under ${assets.absolutePath}", names.size > 100)

        val gaps = hanziTableGaps(names)
        assertEquals(
            "CJK chars in library names without a HANZI entry: ${gaps.joinToString(" ")}",
            emptyList<Char>(),
            gaps,
        )
    }
}
