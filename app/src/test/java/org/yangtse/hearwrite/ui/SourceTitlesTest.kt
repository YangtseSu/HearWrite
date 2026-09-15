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

    /**
     * The 查看词表 jump (错词本 drawer / 听写统计 rows) is offered on a built-in
     * source that still resolves, and on nothing else: a history row has no
     * list to open, a 抽词听写 pool names its members in the title instead, and
     * a built-in id whose list is gone would route to a list that is not there.
     */
    @Test
    fun `the source jump resolves only a built-in list that is still there`() {
        assertEquals(
            SourceJump("人教版小学语文", "二上 识字表"),
            resolveSourceJump("default_人教版小学语文_识字表", "二上 识字表"),
        )
        // Resolved title null = the list no longer exists → no dead route.
        assertNull(resolveSourceJump("default_人教版小学语文_改名了", null))
        assertNull(resolveSourceJump("h1", "apple"))
        assertNull(
            resolveSourceJump(
                multiSourceLabel(listOf("default_中考1600_核心词汇")),
                "核心词汇",
            ),
        )
        assertNull(resolveSourceJump(null, null))
    }
}
