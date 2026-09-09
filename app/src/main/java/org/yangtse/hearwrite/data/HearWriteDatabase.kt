package org.yangtse.hearwrite.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** A headword marked wrong during dictation; keyed by the speakable headword
 *  exactly like `alice/src/lib/storage.ts` (AGENTS.md "Persistence").
 *
 *  v2 (Roadmap #1): [errorCount] counts **distinct dictation runs** in which
 *  the word was marked wrong (+1 per run; re-marks inside one run dedupe at
 *  the caller), [lastWrongAt] is the most recent mark time, and [sourceLabel]
 *  records where the word was first/wrongly dictated: a built-in list id
 *  (`default_<category>_<label>`), a history row id, or null = manual input.
 *  A stale source (history row deleted) degrades to "未知来源" in the UI —
 *  the wrong word is never deleted with its source. */
@Entity(tableName = "wrong_words")
data class WrongWordEntity(
    @PrimaryKey val word: String,
    val addedAt: Long,
    val errorCount: Int,
    val lastWrongAt: Long,
    val sourceLabel: String?,
)

/** One user-pasted word list (built-in lists are never persisted — they ship
 *  as assets). `text` keeps the original input for display; `enrichedText`
 *  holds the ECDICT-expanded lines (`word | pos | meaning`) when available. */
@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val id: String,
    val text: String,
    val enrichedText: String?,
    val createdAt: Long,
)

/** A favorited entry id: `default_<category>_<label>` or a history row id. */
@Entity(tableName = "favorites")
data class FavoriteEntity(@PrimaryKey val id: String)

@Dao
interface WrongWordsDao {
    /** Book words, most-wrong first (count desc, then most-recent mark). */
    @Query(
        "SELECT word FROM wrong_words " +
            "ORDER BY errorCount DESC, lastWrongAt DESC, word ASC"
    )
    fun observeWords(): Flow<List<String>>

    /** Book rows with their counts/sources, most-wrong first. */
    @Query(
        "SELECT * FROM wrong_words " +
            "ORDER BY errorCount DESC, lastWrongAt DESC, word ASC"
    )
    fun observeAll(): Flow<List<WrongWordEntity>>

    /**
     * Record one wrong-word mark of a **new dictation run**: inserts the row
     * (count 1) or bumps [errorCount] +1 and refreshes [lastWrongAt] when the
     * headword is already in the book. [sourceLabel] never downgrades an
     * existing known source to null — a concrete source replaces the stored
     * one, a null mark (bare-word sessions such as 听写错词) keeps it.
     * [addedAt] is the first-mark time and is only written on insert.
     */
    @Query(
        "INSERT INTO wrong_words (word, addedAt, errorCount, lastWrongAt, sourceLabel) " +
            "VALUES (:word, :addedAt, 1, :lastWrongAt, :sourceLabel) " +
            "ON CONFLICT(word) DO UPDATE SET " +
            "errorCount = errorCount + 1, " +
            "lastWrongAt = excluded.lastWrongAt, " +
            "sourceLabel = CASE WHEN excluded.sourceLabel IS NULL " +
            "THEN wrong_words.sourceLabel ELSE excluded.sourceLabel END"
    )
    suspend fun recordMark(word: String, addedAt: Long, lastWrongAt: Long, sourceLabel: String?)

    @Query("DELETE FROM wrong_words WHERE word = :word")
    suspend fun delete(word: String)

    @Query("DELETE FROM wrong_words")
    suspend fun clear()
}

@Dao
abstract class HistoryDao {
    @Query("SELECT * FROM history ORDER BY createdAt DESC, rowid DESC")
    abstract fun observeAll(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history")
    abstract suspend fun all(): List<HistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(entry: HistoryEntity)

    /** Keep only the newest [limit] non-favorited rows (cap 50, drop oldest —
     *  AGENTS.md; favorited rows are exempt so an explicit favorite never
     *  silently disappears under the cap, Roadmap #2). */
    @Query(
        "DELETE FROM history WHERE id NOT IN " +
            "(SELECT id FROM history ORDER BY createdAt DESC, rowid DESC LIMIT :limit) " +
            "AND id NOT IN (SELECT id FROM favorites)"
    )
    abstract suspend fun trimTo(limit: Int)

    @Query("DELETE FROM history WHERE id = :id")
    abstract suspend fun delete(id: String)

    /** 清空历史: keep favorited rows (a favorited user list outliving its
     *  history row is a documented Roadmap #2 behavior — a user's explicit
     *  favorite must never be silently deleted by a bulk clear). */
    @Query("DELETE FROM history WHERE id NOT IN (SELECT id FROM favorites)")
    abstract suspend fun clear()
}

@Dao
interface FavoritesDao {
    @Query("SELECT id FROM favorites ORDER BY rowid")
    fun observeIds(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun delete(id: String)

    /** Drop favorite rows whose history entry no longer exists. Library ids
     *  (`default_*`) are never pruned — built-in lists live as assets. */
    @Query(
        "DELETE FROM favorites WHERE id NOT LIKE 'default\\_%' ESCAPE '\\' " +
            "AND id NOT IN (SELECT id FROM history)"
    )
    suspend fun pruneHistoryOrphans()
}

/**
 * v1 → v2 (Roadmap #1 错词本升级): `wrong_words` gains errorCount /
 * lastWrongAt / sourceLabel. Existing v1 rows were marked wrong in at least
 * one run, so the honest floor is errorCount = 1 (not 0); lastWrongAt starts
 * at the row's first-mark time. New rows only ever come in through
 * [WrongWordsDao.recordMark].
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `wrong_words` ADD COLUMN `errorCount` INTEGER NOT NULL DEFAULT 1"
        )
        db.execSQL(
            "ALTER TABLE `wrong_words` ADD COLUMN `lastWrongAt` INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL("UPDATE `wrong_words` SET `lastWrongAt` = `addedAt`")
        db.execSQL("ALTER TABLE `wrong_words` ADD COLUMN `sourceLabel` TEXT")
    }
}

@Database(
    entities = [WrongWordEntity::class, HistoryEntity::class, FavoriteEntity::class],
    version = 2,
    // Schema JSON is exported to app/schemas (ksp arg in app/build.gradle.kts)
    // and committed — the v1 baseline future migrations diff against. When a
    // later version changes entities, bump `version` and add an
    // AutoMigration/fallback migration; never change entities silently.
    exportSchema = true,
)
abstract class HearWriteDatabase : RoomDatabase() {
    abstract fun wrongWordsDao(): WrongWordsDao
    abstract fun historyDao(): HistoryDao
    abstract fun favoritesDao(): FavoritesDao

    companion object {
        /** Database name (kept stable across versions — migrations run in place). */
        const val NAME = "hearwrite.db"

        /** Production builder with the registered migrations. */
        fun create(context: Context): HearWriteDatabase =
            Room.databaseBuilder(context, HearWriteDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
