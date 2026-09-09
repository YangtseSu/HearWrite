package org.yangtse.hearwrite.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Roadmap #2 验收 (收藏不被 50 条上限淘汰): real-SQLite lock of the SQL
 * favorited-row exemptions in [HistoryDao] and their interplay with
 * [FavoritesDao.pruneHistoryOrphans]. The unit tests over fake DAOs cannot
 * exercise query text — these run the actual Room queries on device.
 */
@RunWith(AndroidJUnit4::class)
class HistoryFavoritesTrimTest {

    private lateinit var db: HearWriteDatabase
    private lateinit var historyDao: HistoryDao
    private lateinit var favoritesDao: FavoritesDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            HearWriteDatabase::class.java,
        ).build()
        historyDao = db.historyDao()
        favoritesDao = db.favoritesDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun favoritedHistorySurvivesTrim_oldestUnfavoritedDrops() = runBlocking {
        val repository = HistoryRepository(historyDao, favoritesDao)

        // Fill to exactly the cap, then favorite the oldest row — the
        // exemption must be in place before the cap is exceeded, because
        // every repository.add() already runs the trim.
        val ids = (1..50).map { repository.add("word list $it", null)!! }
        val oldestId = ids.first()
        favoritesDao.insert(FavoriteEntity(oldestId))

        // Two more lists push the count to 52; the trim must drop the
        // oldest un-favorited row (ids[1]) while the favorited ids[0]
        // survives and the cap is allowed to sit at 50 + 1.
        val newestId = repository.add("word list 51", null)!!
        repository.add("word list 52", null)!!
        val rows = historyDao.all()

        assertTrue("favorited row must survive the cap", rows.any { it.id == oldestId })
        assertTrue("newest row is present", rows.any { it.id == newestId })
        assertFalse("oldest un-favorited row was trimmed", rows.any { it.id == ids[1] })
        assertEquals("50 cap + 1 exempt favorite", 51, rows.size)
    }

    @Test
    fun clearKeepsFavorites_explicitDeletePrunesThem() = runBlocking {
        val repository = HistoryRepository(historyDao, favoritesDao)
        val keptId = repository.add("keep me", null)!!
        val droppedId = repository.add("drop me", null)!!
        favoritesDao.insert(FavoriteEntity(keptId))

        // Bulk clear keeps favorited rows, drops the rest (a favorite must
        // never silently die under a bulk operation).
        repository.clear()
        var rows = historyDao.all()
        assertEquals(listOf(keptId), rows.map { it.id })
        assertTrue(favoritesDao.exists(keptId))

        // Explicit single-row delete still prunes its own favorite (the
        // orphan-cleanup semantics Roadmap #2 preserves).
        repository.delete(keptId)
        rows = historyDao.all()
        assertTrue(rows.isEmpty())
        assertFalse(favoritesDao.exists(keptId))

        // Built-in ids never prune: they resolve from assets, not history.
        favoritesDao.insert(FavoriteEntity("default_人教版小学_一年级上"))
        favoritesDao.pruneHistoryOrphans()
        assertTrue(favoritesDao.exists("default_人教版小学_一年级上"))
    }
}
