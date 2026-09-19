package org.yangtse.hearwrite.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room v4 → v5 migration (data-model Phase 3, `docs/2026-09-18-DATA-MODEL.md`
 * §0): `history` drops `enriched_text`, the column that held ECDICT-expanded
 * lines written back into a stored row. Nothing writes it any more — the
 * dictionary is resolved at read time — so the migration is the column's
 * removal, and the authored list must survive it untouched.
 */
@RunWith(AndroidJUnit4::class)
class HistoryEnrichedDropMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        HearWriteDatabase::class.java,
    )

    /** The v4 shape: what was stored had a plain text and a looked-up twin. */
    private fun seedV4(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO history (id, text, enrichedText, createdAt) " +
                "VALUES ('h1', 'apple\npear', 'apple | n. | 苹果\npear | n. | 梨', 500)"
        )
        db.execSQL("INSERT INTO favorites (id) VALUES ('h1')")
        db.execSQL(
            "INSERT INTO wrong_words (word, addedAt, errorCount, lastWrongAt, sourceLabel) " +
                "VALUES ('pear', 100, 1, 100, 'h1')"
        )
    }

    @Test
    fun migrate4To5_dropsTheLookedUpColumnAndKeepsTheAuthoredList() {
        helper.createDatabase(DB, 4).apply {
            seedV4(this)
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 5, true, MIGRATION_4_5)

        // The authored list, its timestamp and every id holding row survive.
        db.query("SELECT id, text, createdAt FROM history").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("h1", c.getString(0))
            assertEquals("apple\npear", c.getString(1))
            assertEquals(500L, c.getLong(2))
            assertEquals(1, c.count)
        }
        db.query("SELECT COUNT(*) FROM favorites").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        // The 错词本 rows that point back at the source are untouched.
        db.query("SELECT sourceLabel FROM wrong_words").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("h1", c.getString(0))
        }
        // And the looked-up column itself is gone from the table.
        db.query("PRAGMA table_info(`history`)").use { c ->
            val columns = buildList {
                while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name")))
            }
            assertFalse("enrichedText still present: $columns", "enrichedText" in columns)
            assertEquals(listOf("id", "text", "createdAt"), columns)
        }
        db.close()
    }

    /**
     * The migrated database is not only schema-valid but usable: the repository
     * reads the surviving row and its dedupe (the text itself, now that there
     * is no enriched twin) still bumps instead of duplicating.
     */
    @Test
    fun historyRow_roundTripsThroughRoomAfterTheMigration() = runBlocking {
        helper.createDatabase(DB_ROOM, 4).apply {
            seedV4(this)
            close()
        }
        helper.runMigrationsAndValidate(DB_ROOM, 5, true, MIGRATION_4_5).close()

        val room = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            HearWriteDatabase::class.java,
            DB_ROOM,
        ).addMigrations(MIGRATION_4_5).build()
        try {
            val repository = HistoryRepository(room.historyDao(), room.favoritesDao())
            val stored = repository.all()
            assertEquals(1, stored.size)
            assertEquals("apple\npear", stored.single().text)
            assertEquals("h1", stored.single().id)

            // Re-dictating the same list bumps the migrated row.
            assertEquals("h1", repository.add("apple\npear"))
            assertEquals(1, repository.all().size)

            val id = repository.add("kiwi")
            assertEquals("kiwi", repository.all().single { it.id == id }.text)
        } finally {
            room.close()
        }
    }

    companion object {
        private const val DB = "history-enriched-migration-v4.db"
        private const val DB_ROOM = "history-enriched-migration-v4-room.db"
    }
}
