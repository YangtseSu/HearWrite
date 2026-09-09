package org.yangtse.hearwrite.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Room schema migration test (Roadmap #1 验收, candidate-pool 测试基础设施):
 * builds a genuine v1 `hearwrite.db` from the committed 1.json, seeds data,
 * migrates to v2 via [MIGRATION_1_2], and asserts:
 *  - existing rows keep their words/times, gain errorCount = 1 and
 *    lastWrongAt = addedAt (the honest floor for a previously-marked word),
 *    and a null sourceLabel;
 *  - the new v2 schema matches the committed 2.json exactly (Room validates
 *    against the exported schema after the migration);
 *  - the recordMark upsert bumps counts / refreshes times / preserves first
 *    marks and concrete sources on real SQLite.
 */
@RunWith(AndroidJUnit4::class)
class WrongWordsMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        HearWriteDatabase::class.java,
    )

    @Test
    fun migrate1To2_preservesRowsAndDefaultsNewColumns() {
        // Create the v1 database from the committed schema fixture.
        helper.createDatabase(DB_NAME_V1, 1).apply {
            execSQL("INSERT INTO wrong_words (word, addedAt) VALUES ('apple', 1000)")
            execSQL("INSERT INTO wrong_words (word, addedAt) VALUES ('月', 2000)")
            close()
        }
        // Migrate to v2 through the registered migration.
        val db = helper.runMigrationsAndValidate(DB_NAME_V1, 2, true, MIGRATION_1_2)

        // Room migration ran; validate the table contents + defaults.
        db.query(
            "SELECT word, addedAt, errorCount, lastWrongAt, sourceLabel FROM wrong_words " +
                "ORDER BY word ASC"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            val rows = mutableListOf<List<Any?>>()
            do {
                rows.add(
                    listOf(
                        cursor.getString(0),
                        cursor.getLong(1),
                        cursor.getInt(2),
                        cursor.getLong(3),
                        if (cursor.isNull(4)) null else cursor.getString(4),
                    )
                )
            } while (cursor.moveToNext())
            assertEquals(
                listOf(
                    listOf("apple", 1000L, 1, 1000L, null),
                    listOf("月", 2000L, 1, 2000L, null),
                ),
                rows,
            )
        }
        db.close()
    }

    @Test
    fun recordMark_bumpsCountOnRealSqlite() = runBlocking {
        helper.createDatabase(DB_NAME_V2, 1).close()
        helper.runMigrationsAndValidate(DB_NAME_V2, 2, true, MIGRATION_1_2).close()
        val room = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            HearWriteDatabase::class.java,
            DB_NAME_V2,
        ).addMigrations(MIGRATION_1_2).build()
        val dao = room.wrongWordsDao()
        try {
            dao.recordMark("apple", addedAt = 10L, lastWrongAt = 10L, sourceLabel = "src1")
            dao.recordMark("apple", addedAt = 20L, lastWrongAt = 20L, sourceLabel = null)
            dao.recordMark("pear", addedAt = 30L, lastWrongAt = 30L, sourceLabel = null)
            val rows = dao.observeAll().first()
            val apple = rows.first { it.word == "apple" }
            assertEquals(2, apple.errorCount)
            assertEquals(10L, apple.addedAt) // first-mark time preserved
            assertEquals(20L, apple.lastWrongAt) // refreshed on the repeat
            assertEquals("src1", apple.sourceLabel) // null did not downgrade
            val pear = rows.first { it.word == "pear" }
            assertEquals(1, pear.errorCount)
            assertEquals(null, pear.sourceLabel) // manual input keeps no source
            assertEquals(30L, pear.lastWrongAt)
        } finally {
            room.close()
        }
    }

    companion object {
        private const val DB_NAME_V1 = "migration-test-v1.db"
        private const val DB_NAME_V2 = "migration-test-v2.db"
    }
}
