package org.yangtse.hearwrite.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v3 → v4 (八上 读读写写 labels gain lesson titles): the migration is a pure
 * id rewrite, so this drives it on real SQLite and asserts every id-holding
 * column follows — favorites (no duplicates when the new id already exists),
 * wrong-word and session provenance, and the `multi:` drawn-run labels that
 * cannot be rewritten and must degrade instead of dangling.
 */
@RunWith(AndroidJUnit4::class)
class RenamedLabelMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        HearWriteDatabase::class.java,
    )

    private val oldId = "default_人教版初中语文_八上 读读写写 15"
    private val newId = "default_人教版初中语文_八上 15 背影 读读写写"

    @Test
    fun migrate3To4_rewritesRenamedIdsAndDegradesMultiLabels() {
        helper.createDatabase(DB, 3).apply {
            execSQL("INSERT INTO favorites (id) VALUES ('$oldId')")
            // Already favorited under the new name: the rename must not duplicate it.
            execSQL("INSERT INTO favorites (id) VALUES ('$newId')")
            // Unrelated ids must survive untouched — including one referencing a
            // list that was never renamed.
            execSQL("INSERT INTO favorites (id) VALUES ('default_人教版初中语文_七上 1 春 读读写写')")
            execSQL("INSERT INTO favorites (id) VALUES ('history-abc')")

            execSQL(
                "INSERT INTO wrong_words (word, addedAt, errorCount, lastWrongAt, sourceLabel) " +
                    "VALUES ('交卸', 100, 1, 100, '$oldId')"
            )
            // A drawn run including the renamed list cannot be rewritten in place.
            execSQL(
                "INSERT INTO wrong_words (word, addedAt, errorCount, lastWrongAt, sourceLabel) " +
                    "VALUES ('狼藉', 200, 1, 200, 'multi:$oldId,default_人教版初中语文_七上 1 春 读读写写')"
            )
            // A `multi:` label over other lists is unaffected.
            execSQL(
                "INSERT INTO wrong_words (word, addedAt, errorCount, lastWrongAt, sourceLabel) " +
                    "VALUES ('簌簌', 300, 1, 300, 'multi:default_人教版初中语文_七上 1 春 读读写写')"
            )
            execSQL(
                "INSERT INTO wrong_words (word, addedAt, errorCount, lastWrongAt, sourceLabel) " +
                    "VALUES ('颓唐', 400, 1, 400, NULL)"
            )

            execSQL(
                "INSERT INTO sessions (startedAt, sourceLabel, totalWords, wrongCount, durationSec, kind) " +
                    "VALUES (500, '$oldId', 12, 1, 60, 'dictation')"
            )
            execSQL(
                "INSERT INTO sessions (startedAt, sourceLabel, totalWords, wrongCount, durationSec, kind) " +
                    "VALUES (600, 'multi:$oldId', 12, 1, 60, 'dictation')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 4, true, MIGRATION_3_4)

        fun column(sql: String): List<String?> =
            db.query(sql).use { c ->
                val out = mutableListOf<String?>()
                while (c.moveToNext()) out.add(if (c.isNull(0)) null else c.getString(0))
                out
            }

        assertEquals(
            listOf(newId, "default_人教版初中语文_七上 1 春 读读写写", "history-abc"),
            // Exactly three rows: the old id was renamed onto the existing new id.
            column("SELECT id FROM favorites ORDER BY rowid"),
        )
        assertEquals(
            listOf("交卸" to newId, "狼藉" to null, "簌簌" to "multi:default_人教版初中语文_七上 1 春 读读写写", "颓唐" to null),
            column("SELECT sourceLabel FROM wrong_words ORDER BY addedAt").mapIndexed { i, label ->
                listOf("交卸", "狼藉", "簌簌", "颓唐")[i] to label
            },
        )
        assertEquals(
            listOf(newId, null),
            column("SELECT sourceLabel FROM sessions ORDER BY startedAt"),
        )
        db.close()
    }

    companion object {
        private const val DB = "migration-test-v3.db"
    }
}
