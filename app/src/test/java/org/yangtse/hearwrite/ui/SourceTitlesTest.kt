package org.yangtse.hearwrite.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.yangtse.hearwrite.data.HistoryEntry
import org.yangtse.hearwrite.domain.multiSourceLabel

/**
 * Locks the 错词本 / 听写统计 source-title resolution, including the 抽词听写
 * `multi:` labels (Roadmap #9) — a drawn session's marks must show which
 * lists they came from instead of degrading to 未知来源.
 */
class SourceTitlesTest {

    private val titles = mapOf(
        "default_中考1600_核心词汇" to "核心词汇",
        "default_人教版小学语文_识字表" to "二上 识字表",
    )
    private val history = listOf(
        HistoryEntry("h1", "apple | n. | 苹果\npear", null, 0L),
    )

    @Test
    fun `a multi source titles its first resolvable list and the member count`() {
        val label = multiSourceLabel(
            listOf("default_中考1600_核心词汇", "default_人教版小学语文_识字表"),
        )
        assertEquals("核心词汇 等 2 个词表", resolveSourceTitle(label, history, titles))
    }

    @Test
    fun `a multi source whose lists are gone still names the group`() {
        val label = multiSourceLabel(listOf("default_未知_甲", "default_未知_乙"))
        assertEquals("多词表（2 个词表）", resolveSourceTitle(label, history, titles))
    }

    @Test
    fun `single built-in and history sources keep their existing titles`() {
        assertEquals(
            "二上 识字表",
            resolveSourceTitle("default_人教版小学语文_识字表", history, titles),
        )
        assertEquals("apple", resolveSourceTitle("h1", history, titles))
        assertNull(resolveSourceTitle(null, history, titles))
    }
}
