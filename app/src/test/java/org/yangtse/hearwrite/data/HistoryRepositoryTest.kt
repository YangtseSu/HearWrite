package org.yangtse.hearwrite.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks [HistoryRepository.add]'s dedupe over the DAO seam: a re-run of a
 * stored list bumps the row instead of inserting a duplicate that would crowd
 * the 50-row cap. The stored text is the authored list, so the key is the
 * text itself — nothing else is persisted (AGENTS.md *Persistence*,
 * the `enrichedText` column was dropped in Room v5). The SQL favorited-row
 * exemptions in `HistoryDao` are NOT unit-testable this way (a mirroring fake
 * proves nothing); they are locked on real SQLite in the instrumentation test
 * `HistoryFavoritesTrimTest` (app/src/androidTest, Roadmap #2 验收).
 */
class HistoryRepositoryTest {

    private class FakeHistoryDao : HistoryDao() {
        private val rows = MutableStateFlow<List<HistoryEntity>>(emptyList())

        fun seed(text: String, created: Long, id: String) {
            rows.value = rows.value + HistoryEntity(id, text, created)
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

        override suspend fun insertExact(entry: HistoryEntity) {
            rows.value = rows.value.filterNot { it.id == entry.id } + entry
        }

        override suspend fun clear() {
            rows.value = emptyList()
        }
    }

    /**
     * Faithful mirror of [FavoritesDao] over the same in-memory rows the fake
     * history DAO holds: `insert` REPLACEs by id (a repeated favorite is one
     * row) and [pruneHistoryOrphans] drops non-`default_*` ids whose history
     * row is gone — the delete-then-撤销 path depends on both.
     */
    private class FakeFavoritesDao(private val history: FakeHistoryDao) : FavoritesDao {
        val ids = MutableStateFlow<List<String>>(emptyList())
        override fun observeIds(): Flow<List<String>> = ids

        override suspend fun exists(id: String): Boolean = id in ids.value

        override suspend fun insert(favorite: FavoriteEntity) {
            ids.value = ids.value.filterNot { it == favorite.id } + favorite.id
        }

        override suspend fun delete(id: String) {
            ids.value = ids.value - id
        }

        override suspend fun pruneHistoryOrphans() {
            val live = history.all().mapTo(mutableSetOf()) { it.id }
            ids.value = ids.value.filter { it.startsWith("default_") || it in live }
        }
    }

    private fun repo(): Triple<HistoryRepository, FakeHistoryDao, FakeFavoritesDao> {
        val dao = FakeHistoryDao()
        val favorites = FakeFavoritesDao(dao)
        return Triple(HistoryRepository(dao, favorites), dao, favorites)
    }

    private val plain = "apple\npear\nplum"

    @Test
    fun `re-dictating a stored list bumps instead of duplicating`() = runTest {
        val (r, dao, _) = repo()
        dao.seed(plain, 1L, "r0")
        val id = r.add(plain)
        assertEquals(1, dao.all().size)
        assertEquals("r0", id) // the stored row's id is returned (source key)
    }

    @Test
    fun `a padded paste is the same list, not a second row`() = runTest {
        val (r, dao, _) = repo()
        dao.seed(plain, 1L, "r0")
        r.add(plain)
        assertEquals(1, dao.all().size)
        // The dedupe compares the trimmed input, so a phone keyboard's extra
        // whitespace lines are the same list.
        r.add("\n$plain\n")
        assertEquals(1, dao.all().size)
        assertEquals("r0", dao.all().single().id)
    }

    @Test
    fun `the row is stored as authored, with no looked-up column`() = runTest {
        val (r, dao, _) = repo()
        val id = r.add("apple | n. | 苹果\nbanana")
        assertEquals("apple | n. | 苹果\nbanana", dao.all().single { it.id == id }.text)
    }

    @Test
    fun `distinct content inserts a new row`() = runTest {
        val (r, dao, _) = repo()
        dao.seed(plain, 1L, "r0")
        val id = r.add("kiwi\nmango")
        assertEquals(2, dao.all().size)
        assertEquals(id, dao.all().first { it.text == "kiwi\nmango" }.id)
    }

    @Test
    fun `blank input returns null and records nothing`() = runTest {
        val (r, dao, _) = repo()
        val id = r.add("   ")
        assertNull(id)
        assertEquals(0, dao.all().size)
    }

    @Test
    fun `restore re-inserts the same row and its favorite star`() = runTest {
        val (r, dao, favorites) = repo()
        dao.seed(plain, 1L, "r0")
        favorites.insert(FavoriteEntity("r0"))
        val entry = r.all().single()

        r.delete("r0")
        assertEquals(0, dao.all().size)

        r.restore(entry, wasFavorited = true)

        // Same row id, so a 错词本 source / favorite pointing at it resolves again.
        assertEquals(listOf("r0"), dao.all().map { it.id })
        assertEquals(listOf("r0"), favorites.ids.value)
    }

    @Test
    fun `restore without a favorite leaves the star off`() = runTest {
        val (r, dao, favorites) = repo()
        dao.seed(plain, 1L, "r0")
        val entry = r.all().single()

        r.delete("r0")
        r.restore(entry, wasFavorited = false)

        assertEquals(listOf("r0"), dao.all().map { it.id })
        assertEquals(emptyList<String>(), favorites.ids.value)
    }
}
