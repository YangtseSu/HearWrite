package org.yangtse.hearwrite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `DictationSessionStore` — the in-memory handoff between the launch pad and
 * the dictation screen (AGENTS.md). Its contract is take-once: an activity
 * kill that recreates the DictationViewModel must not replay the old session,
 * so the second `take()` must see an empty store.
 */
class DictationSessionStoreTest {

    @Test
    fun `take returns the staged session then empties the store`() {
        val store = DictationSessionStore()
        store.stage(listOf("月 | yuè | 月亮", "apple"), sourceLabel = "default_人教版小学_识字表")

        val session = store.take()
        assertEquals(listOf("月 | yuè | 月亮", "apple"), session.lines)
        assertEquals("default_人教版小学_识字表", session.sourceLabel)

        // Consume-once: a recreated ViewModel must not restart the old run.
        val again = store.take()
        assertEquals(emptyList<String>(), again.lines)
        assertNull(again.sourceLabel)
    }

    @Test
    fun `a bare session stages a null source label`() {
        val store = DictationSessionStore()
        store.stage(listOf("apple"), sourceLabel = null)

        val session = store.take()
        assertEquals(listOf("apple"), session.lines)
        assertNull(session.sourceLabel)
    }

    @Test
    fun `taking without staging yields an empty session`() {
        val store = DictationSessionStore()
        val session = store.take()
        assertEquals(emptyList<String>(), session.lines)
        assertNull(session.sourceLabel)
    }

    @Test
    fun `staging again replaces the previous session`() {
        val store = DictationSessionStore()
        store.stage(listOf("a"), sourceLabel = "x")
        store.stage(listOf("b", "c"), sourceLabel = "y")

        val session = store.take()
        assertEquals(listOf("b", "c"), session.lines)
        assertEquals("y", session.sourceLabel)
    }
}
