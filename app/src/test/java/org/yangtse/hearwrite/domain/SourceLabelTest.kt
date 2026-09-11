package org.yangtse.hearwrite.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Locks the 抽词听写 source-label encoding (Roadmap #9). */
class SourceLabelTest {

    @Test
    fun `multi label round-trips its member ids in order`() {
        val ids = listOf(
            "default_中考1600_核心词汇",
            "default_人教版小学语文_二上 识字表",
        )
        val label = multiSourceLabel(ids)
        assertEquals("multi:${ids.joinToString(",")}", label)
        assertEquals(ids, multiSourceIds(label))
    }

    @Test
    fun `non-multi labels carry no member ids`() {
        assertEquals(emptyList<String>(), multiSourceIds("default_中考1600_A"))
        assertEquals(emptyList<String>(), multiSourceIds("1712345678_abc"))
    }

    @Test
    fun `built-in ids parse back into category and label`() {
        assertEquals(
            "人教版小学语文" to "二上 写字表 识字 1",
            parseBuiltinListId("default_人教版小学语文_二上 写字表 识字 1"),
        )
        assertNull(parseBuiltinListId("1712345678_abc"))
        assertNull(parseBuiltinListId("default_nounderscore"))
    }
}
