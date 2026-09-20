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
 * §0): `history` drops `enrichedText`, the column that held ECDICT-expanded
 * lines written back into a stored row. Nothing writes it any more — the
 * dictionary is resolved at read time — so the migration is the column's
 * removal, and the authored list must survive it untouched.
 *
 * Runs on the framework SQLite of whatever device/emulator it is given, which
 * is the whole point: CI's instrumented job is pinned to API 33 (the app's
 * `minSdk` floor, SQLite 3.32), because a migration written against a newer
 * engine is not a migration — `ALTER TABLE … DROP COLUMN` needs 3.35 (API 34+)
 * and failed outright at the floor while this suite, run on API 36, stayed
 * green. Never "upgrade" the job's API level without keeping one run at the
 * floor.
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
        // A second row: the rebuild is a copy, and a copy that loses rows (or
        // reorders them) is exactly what a hand-written rebuild gets wrong.
        // `enrichedText` is NULL here — a v4 row that never got a lookup.
        db.execSQL("INSERT INTO history (id, text, enrichedText, createdAt) VALUES ('h2', 'kiwi', NULL, 501)")
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

        // Every authored row, its timestamp and its order survive — the copy is
        // a `SELECT` of the three real columns, so a NULL `enrichedText` (a row
        // that never got a lookup) must not drop the row.
        db.query("SELECT id, text, createdAt FROM history ORDER BY createdAt").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("h1", c.getString(0))
            assertEquals("apple\npear", c.getString(1))
            assertEquals(500L, c.getLong(2))
            assertTrue(c.moveToNext())
            assertEquals("h2", c.getString(0))
            assertEquals("kiwi", c.getString(1))
            assertEquals(501L, c.getLong(2))
            assertEquals(2, c.count)
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
        // The rebuild's scratch table is consumed by the RENAME. Room's schema
        // validation only compares the entities it knows, so a leftover
        // `history_new` would sail past it — assert the scratch table is gone
        // (an exact table list would also have to enumerate SQLite's own
        // bookkeeping tables, `android_metadata` and `sqlite_sequence`, which
        // are not this migration's business).
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'history%'").use { c ->
            val tables = buildList {
                while (c.moveToNext()) add(c.getString(0))
            }
            assertEquals(listOf("history"), tables)
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
            assertEquals(2, stored.size)
            assertEquals("apple\npear", stored.single { it.id == "h1" }.text)
            assertEquals("kiwi", stored.single { it.id == "h2" }.text)

            // Re-dictating the same list bumps the migrated row.
            assertEquals("h1", repository.add("apple\npear"))
            assertEquals(2, repository.all().size)

            val id = repository.add("pear")
            assertEquals("pear", repository.all().single { it.id == id }.text)
        } finally {
            room.close()
        }
    }

    companion object {
        private const val DB = "history-enriched-migration-v4.db"
        private const val DB_ROOM = "history-enriched-migration-v4-room.db"
    }
}
