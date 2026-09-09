package org.yangtse.hearwrite.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the dedupe arms of [HistoryRepository.add] — pure Kotlin logic over
 * the DAO seam: a re-run of a stored row (plain text, effective text, or the
 * enriched text itself) bumps the row instead of inserting a duplicate that
 * would crowd the 50-row cap. The SQL favorited-row exemptions in
 * `HistoryDao` are NOT unit-testable this way (a mirroring fake proves
 * nothing); they are verified on device instead (no androidTest infra).
 */
class HistoryRepositoryTest {

    private class FakeHistoryDao : HistoryDao() {
        private val rows = MutableStateFlow<List<HistoryEntity>>(emptyList())

        fun seed(text: String, enriched: String?, created: Long, id: String) {
            rows.value = rows.value + HistoryEntity(id, text, enriched, created)
        }

        override fun observeAll(): Flow<List<HistoryEntity>> = rows

        override suspend fun all(): List<HistoryEntity> = rows.value

        override suspend fun insert(entry: HistoryEntity) {
            rows.value = rows.value.filterNot { it.id == entry.id } + entry
        }

        // Not exercised by the dedupe tests (repo.add trims after insert, but
        // with at most one row nothing can be trimmed away).
        override suspend fun trimTo(limit: Int) {}

        override suspend fun delete(id: String) {
            rows.value = rows.value.filterNot { it.id == id }
        }

        override suspend fun clear() {
            rows.value = emptyList()
        }
    }

    private class FakeFavoritesDao : FavoritesDao {
        override fun observeIds(): Flow<List<String>> = MutableStateFlow(emptyList())
        override suspend fun exists(id: String): Boolean = false
        override suspend fun insert(favorite: FavoriteEntity) {}
        override suspend fun delete(id: String) {}
        override suspend fun pruneHistoryOrphans() {}
    }

    private fun repo(): Pair<HistoryRepository, FakeHistoryDao> {
        val dao = FakeHistoryDao()
        return HistoryRepository(dao, FakeFavoritesDao()) to dao
    }

    private val plain = "apple\npear\nplum"
    private val enriched = "apple | n. | 苹果\npear | n. | 梨\nplum | n. | 李子"

    @Test
    fun `re-dictating a stored enriched row bumps instead of duplicating`() = runTest {
        val (r, dao) = repo()
        dao.seed(plain, enriched, 1L, "r0")
        // The library preview / history rows hand the enriched text to the
        // draft, so the submission IS the enriched text and effective == null.
        r.add(enriched, null)
        assertEquals(1, dao.all().size)
    }

    @Test
    fun `re-dictating the plain text of an enriched row bumps instead of duplicating`() = runTest {
        val (r, dao) = repo()
        dao.seed(plain, enriched, 1L, "r0")
        r.add(plain, enriched) // effective == enrichedText of the stored row
        assertEquals(1, dao.all().size)
    }

    @Test
    fun `distinct content inserts a new row`() = runTest {
        val (r, dao) = repo()
        dao.seed(plain, enriched, 1L, "r0")
        r.add("kiwi\nmango", null)
        assertEquals(2, dao.all().size)
    }
}
