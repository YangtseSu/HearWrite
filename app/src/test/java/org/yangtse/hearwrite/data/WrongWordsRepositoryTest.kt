package org.yangtse.hearwrite.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the v2 错词本 semantics (Roadmap #1) over the DAO seam:
 * - a fresh headword inserts with errorCount 1;
 * - a repeat mark of a **new run** bumps the count +1 and refreshes
 *   lastWrongAt while keeping addedAt;
 * - a null source mark never downgrades an existing concrete source;
 * - a concrete source replaces the stored one.
 * The upsert SQL itself (WrongWordsDao.recordMark) is NOT exercised here — it
 * is covered by the Room migration/instrumentation test on device.
 */
class WrongWordsRepositoryTest {

    private class FakeWrongWordsDao : WrongWordsDao {
        private val rows = MutableStateFlow<List<WrongWordEntity>>(emptyList())

        fun seed(row: WrongWordEntity) {
            rows.value = rows.value + row
        }

        fun all(): List<WrongWordEntity> = rows.value

        override fun observeWords(): Flow<List<String>> =
            MutableStateFlow(rows.value.map { it.word })

        override fun observeAll(): Flow<List<WrongWordEntity>> = rows

        override suspend fun recordMark(
            word: String,
            addedAt: Long,
            lastWrongAt: Long,
            sourceLabel: String?,
        ) {
            val existing = rows.value.firstOrNull { it.word == word }
            val updated = if (existing == null) {
                WrongWordEntity(word, addedAt, 1, lastWrongAt, sourceLabel)
            } else {
                existing.copy(
                    errorCount = existing.errorCount + 1,
                    lastWrongAt = lastWrongAt,
                    sourceLabel = if (sourceLabel != null) sourceLabel else existing.sourceLabel,
                )
            }
            rows.value = rows.value.filterNot { it.word == word } + updated
        }

        override suspend fun delete(word: String) {
            rows.value = rows.value.filterNot { it.word == word }
        }

        override suspend fun clear() {
            rows.value = emptyList()
        }
    }

    private fun repo(): Pair<WrongWordsRepository, FakeWrongWordsDao> {
        val dao = FakeWrongWordsDao()
        return WrongWordsRepository(dao) to dao
    }

    @Test
    fun `fresh headword inserts with count one and its source`() = runTest {
        val (r, dao) = repo()
        r.add("apple", "default_中考1600_中考词汇")
        val row = dao.all().single()
        assertEquals("apple", row.word)
        assertEquals(1, row.errorCount)
        assertEquals("default_中考1600_中考词汇", row.sourceLabel)
        assertEquals(row.addedAt, row.lastWrongAt)
    }

    @Test
    fun `repeat mark across runs bumps count and refreshes lastWrongAt`() = runTest {
        val (r, dao) = repo()
        r.add("apple", null)
        val first = dao.all().single()
        // Simulate a later run (clock advances; repository reads the wall clock).
        Thread.sleep(2)
        r.add("apple", null)
        val row = dao.all().single()
        assertEquals(2, row.errorCount)
        assertEquals(first.addedAt, row.addedAt) // first-mark time preserved
        assertEquals(true, row.lastWrongAt > first.lastWrongAt)
    }

    @Test
    fun `null source never downgrades an existing concrete source`() = runTest {
        val (r, dao) = repo()
        r.add("apple", "default_中考1600_中考词汇")
        r.add("apple", null) // review round: no provenance
        val row = dao.all().single()
        assertEquals("default_中考1600_中考词汇", row.sourceLabel)
        assertEquals(2, row.errorCount)
    }

    @Test
    fun `concrete source replaces the stored one`() = runTest {
        val (r, dao) = repo()
        r.add("apple", "default_中考1600_中考词汇")
        r.add("apple", "h_123") // dictated again from a user list
        val row = dao.all().single()
        assertEquals("h_123", row.sourceLabel)
        assertEquals(2, row.errorCount)
    }

    @Test
    fun `empty headword is ignored`() = runTest {
        val (r, dao) = repo()
        r.add("", null)
        assertEquals(0, dao.all().size)
    }

    @Test
    fun `remove and clear delete the book`() = runTest {
        val (r, dao) = repo()
        r.add("apple", null)
        r.add("pear", null)
        r.remove("apple")
        assertEquals(listOf("pear"), dao.all().map { it.word })
        r.clear()
        assertEquals(0, dao.all().size)
    }
}
