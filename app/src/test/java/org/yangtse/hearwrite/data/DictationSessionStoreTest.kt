package org.yangtse.hearwrite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.yangtse.hearwrite.domain.WordRow
import org.yangtse.hearwrite.domain.parseWordLine

/**
 * `DictationSessionStore` — the in-memory handoff between the launch pad and
 * the dictation screen (AGENTS.md). It stages parsed rows; its contract is
 * take-once: an activity kill that recreates the DictationViewModel must not
 * replay the old session, so the second `take()` must see an empty store.
 */
class DictationSessionStoreTest {

    private fun rows(vararg lines: String): List<WordRow> = lines.map(::parseWordLine)

    @Test
    fun `take returns the staged session then empties the store`() {
        val store = DictationSessionStore()
        store.stage(rows("月 | yuè | 月亮", "apple"), sourceLabel = "default_人教版小学_识字表")

        val session = store.take()
        assertEquals(rows("月 | yuè | 月亮", "apple"), session.rows)
        assertEquals("default_人教版小学_识字表", session.sourceLabel)

        // Consume-once: a recreated ViewModel must not restart the old run.
        val again = store.take()
        assertEquals(emptyList<WordRow>(), again.rows)
        assertNull(again.sourceLabel)
    }

    @Test
    fun `a bare session stages a null source label`() {
        val store = DictationSessionStore()
        store.stage(rows("apple"), sourceLabel = null)

        val session = store.take()
        assertEquals(rows("apple"), session.rows)
        assertNull(session.sourceLabel)
    }

    @Test
    fun `taking without staging yields an empty session`() {
        val store = DictationSessionStore()
        val session = store.take()
        assertEquals(emptyList<WordRow>(), session.rows)
        assertNull(session.sourceLabel)
    }

    @Test
    fun `staging again replaces the previous session`() {
        val store = DictationSessionStore()
        store.stage(rows("a"), sourceLabel = "x")
        store.stage(rows("b", "c"), sourceLabel = "y")

        val session = store.take()
        assertEquals(rows("b", "c"), session.rows)
        assertEquals("y", session.sourceLabel)
    }
}
