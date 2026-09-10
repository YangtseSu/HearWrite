package org.yangtse.hearwrite.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.yangtse.hearwrite.domain.SessionKind

/**
 * Room v2 → v3 migration test (Roadmap #3 验收): seeds a genuine v2 database
 * from the committed 2.json, migrates through [MIGRATION_2_3], and asserts
 *  - existing 错词本/history/favorites rows are untouched (the migration is
 *    purely additive), and
 *  - the new `sessions` table matches the committed 3.json exactly and
 *    accepts rows with an auto-generated id.
 */
@RunWith(AndroidJUnit4::class)
class SessionsMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        HearWriteDatabase::class.java,
    )

    @Test
    fun migrate2To3_addsSessionsAndPreservesExistingTables() {
        helper.createDatabase(DB_NAME, 2).apply {
            execSQL(
                "INSERT INTO wrong_words (word, addedAt, errorCount, lastWrongAt, sourceLabel) " +
                    "VALUES ('apple', 1000, 2, 2000, 'src1')"
            )
            execSQL(
                "INSERT INTO history (id, text, enrichedText, createdAt) " +
                    "VALUES ('h1', 'apple', NULL, 500)"
            )
            execSQL("INSERT INTO favorites (id) VALUES ('default_人教版小学_一年级上')")
            close()
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 3, true, MIGRATION_2_3)

        // Existing rows survive verbatim.
        db.query("SELECT word, errorCount, lastWrongAt, sourceLabel FROM wrong_words").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("apple", c.getString(0))
            assertEquals(2, c.getInt(1))
            assertEquals(2000L, c.getLong(2))
            assertEquals("src1", c.getString(3))
        }
        db.query("SELECT id, createdAt FROM history").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("h1", c.getString(0))
            assertEquals(500L, c.getLong(1))
        }
        db.query("SELECT COUNT(*) FROM favorites").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        // The new table starts empty.
        db.query("SELECT COUNT(*) FROM sessions").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun sessionRow_roundTripsThroughRoomOnRealSqlite() = runBlocking {
        helper.createDatabase(DB_NAME_ROOM, 2).close()
        helper.runMigrationsAndValidate(DB_NAME_ROOM, 3, true, MIGRATION_2_3).close()
        val room = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            HearWriteDatabase::class.java,
            DB_NAME_ROOM,
        ).addMigrations(MIGRATION_2_3).build()
        try {
            val repository = SessionRepository(room.sessionDao())
            repository.record(
                startedAt = 1_700_000_000_000,
                sourceLabel = "default_人教版小学_一年级上",
                totalWords = 20,
                wrongCount = 3,
                durationSec = 240,
                kind = SessionKind.DICTATION,
            )
            repository.record(
                startedAt = 1_700_000_100_000,
                sourceLabel = null,
                totalWords = 5,
                wrongCount = 5,
                durationSec = 60,
                kind = SessionKind.REVIEW,
            )

            val sessions = repository.observe().first()
            assertEquals(2, sessions.size)
            assertEquals(1_700_000_100_000L, sessions.first().startedAt) // newest first
            assertEquals(
                SessionKind.REVIEW,
                sessions.first().kind,
            )
            val older = sessions.last()
            assertEquals("default_人教版小学_一年级上", older.sourceLabel)
            assertEquals(20, older.totalWords)
            assertEquals(17, older.correctCount)
            assertEquals(240L, older.durationSec)
            assertTrue("ids are auto-generated", older.id > 0)
        } finally {
            room.close()
        }
    }

    companion object {
        private const val DB_NAME = "sessions-migration-v2.db"
        private const val DB_NAME_ROOM = "sessions-migration-v2-room.db"
    }
}
