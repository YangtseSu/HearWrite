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
import org.yangtse.hearwrite.domain.BUILTIN_LIST_ID_PREFIX

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

/**
 * One completed dictation run (Roadmap #3 听写统计). Only runs that reached the
 * end of their list are recorded; [sourceLabel] uses the same provenance ids
 * as the 错词本 ([WrongWordEntity.sourceLabel]) and [kind] is a
 * [SessionKind.wire] value. [durationSec] is the run's wall-clock length —
 * the same number the finish card shows. Rows accumulate forever (a run is a
 * few dozen bytes and the stats page reads them all; no cap, no pruning).
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val startedAt: Long,
    val sourceLabel: String?,
    val totalWords: Int,
    val wrongCount: Int,
    val durationSec: Long,
    val kind: String,
)

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

    /**
     * Put one row back exactly as it was — the 撤销 of a single-row 移除. Unlike
     * [recordMark] this does not touch counts: the restored row is the same
     * mark, not a new one.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExact(row: WrongWordEntity)
}

@Dao
interface SessionDao {
    /** Every recorded run, newest first (the stats page reads them all). */
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC, id DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Insert
    suspend fun insert(session: SessionEntity)

    @Query("DELETE FROM sessions")
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

    /**
     * Put one deleted row back exactly as it was (撤销 of a single-row 删除):
     * REPLACE on the primary key id keeps it the same row, so a favorite that
     * pointed at it still resolves.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertExact(entry: HistoryEntity)

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

/**
 * v2 → v3 (Roadmap #3 听写统计): new `sessions` table recording one row per
 * completed dictation run. Purely additive — no existing table is touched, so
 * the migration is a plain CREATE TABLE matching the exported 3.json exactly
 * (Room validates the schema after the migration).
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sessions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`startedAt` INTEGER NOT NULL, " +
                "`sourceLabel` TEXT, " +
                "`totalWords` INTEGER NOT NULL, " +
                "`wrongCount` INTEGER NOT NULL, " +
                "`durationSec` INTEGER NOT NULL, " +
                "`kind` TEXT NOT NULL)"
        )
    }
}

private const val RENAME_CATEGORY = "人教版初中语文"

/** Old 八上 label → new label, in the same order the files were renamed. */
private val EIGHT_UP_LABEL_RENAMES = mapOf(
    "八上 读读写写 1" to "八上 1 消息二则 读读写写",
    "八上 读读写写 2" to "八上 2 中国人首次进入自己的空间站 读读写写",
    "八上 读读写写 3" to "八上 3 首届诺贝尔奖颁发 读读写写",
    "八上 读读写写 4" to "八上 4 “飞天”凌空 读读写写",
    "八上 读读写写 5" to "八上 5 一着惊海天 读读写写",
    "八上 读读写写 6" to "八上 6 国行公祭，为佑世界和平 读读写写",
    "八上 读读写写 7" to "八上 7 藤野先生 读读写写",
    "八上 读读写写 8" to "八上 8 回忆鲁迅先生（节选） 读读写写",
    "八上 读读写写 9" to "八上 9 天上有颗“南仁东星” 读读写写",
    "八上 读读写写 10" to "八上 10 美丽的颜色 读读写写",
    "八上 读读写写 15" to "八上 15 背影 读读写写",
    "八上 读读写写 16" to "八上 16 白杨礼赞 读读写写",
    "八上 读读写写 17" to "八上 17 散文二篇 读读写写",
    "八上 读读写写 18" to "八上 18 昆明的雨 读读写写",
    "八上 读读写写 19" to "八上 19 中国石拱桥 读读写写",
    "八上 读读写写 20" to "八上 20 苏州园林 读读写写",
    "八上 读读写写 21" to "八上 21 人民英雄永垂不朽 读读写写",
    "八上 读读写写 22" to "八上 22 梦回繁华 读读写写",
)

/**
 * v3 → v4 (八上 读读写写 labels gain their lesson titles): the built-in list
 * labels were renamed `八上 读读写写 <N>` → `八上 <N> <标题> 读读写写`, so every
 * stored id pointing at one of them is rewritten in place. The schema is
 * untouched — this is a pure data migration over the three id-holding columns
 * (`favorites.id`, `wrong_words.sourceLabel`, `sessions.sourceLabel`).
 *
 * Why migrate instead of leaving the old ids: id resolution is exact-match
 * (library browser, favorite titles, 错词本 groups, the 查看词表 jump, the
 * `sessions` titles), so an unrewritten id silently degrades to a bare
 * headword / 未知来源. These labels shipped in v0.6.0, so real installs hold
 * them. Favorites are `INSERT OR IGNORE` + `DELETE` so an id the user already
 * holds under its new name is not duplicated. A `multi:<id>,<id>,…` label
 * (Roadmap #9 抽词听写) embeds member ids and is not expressible as one
 * equality update: those degrade to NULL — "a bare headword, never drops the
 * row" (AGENTS.md "Persistence") — rather than pointing at a dead id.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for ((old, new) in EIGHT_UP_LABEL_RENAMES) {
            val oldId = "$BUILTIN_LIST_ID_PREFIX$RENAME_CATEGORY" + "_" + old
            val newId = "$BUILTIN_LIST_ID_PREFIX$RENAME_CATEGORY" + "_" + new

            db.execSQL(
                "INSERT OR IGNORE INTO `favorites` (`id`) " +
                    "SELECT REPLACE(`id`, ?, ?) FROM `favorites` WHERE `id` = ?",
                arrayOf(old, new, oldId),
            )
            db.execSQL("DELETE FROM `favorites` WHERE `id` = ?", arrayOf(oldId))

            // `instr` on the comma-wrapped member list avoids LIKE wildcards
            // entirely (labels are data, never patterns).
            db.execSQL(
                "UPDATE `wrong_words` SET `sourceLabel` = ? WHERE `sourceLabel` = ?",
                arrayOf(newId, oldId),
            )
            db.execSQL(
                "UPDATE `wrong_words` SET `sourceLabel` = NULL WHERE `sourceLabel` LIKE 'multi:%' " +
                    "AND instr(',' || substr(`sourceLabel`, 7) || ',', ?) > 0",
                arrayOf(",$oldId,"),
            )
            db.execSQL(
                "UPDATE `sessions` SET `sourceLabel` = ? WHERE `sourceLabel` = ?",
                arrayOf(newId, oldId),
            )
            db.execSQL(
                "UPDATE `sessions` SET `sourceLabel` = NULL WHERE `sourceLabel` LIKE 'multi:%' " +
                    "AND instr(',' || substr(`sourceLabel`, 7) || ',', ?) > 0",
                arrayOf(",$oldId,"),
            )
        }
    }
}

@Database(
    entities = [
        WrongWordEntity::class,
        HistoryEntity::class,
        FavoriteEntity::class,
        SessionEntity::class,
    ],
    version = 4,
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
    abstract fun sessionDao(): SessionDao

    companion object {
        /** Database name (kept stable across versions — migrations run in place). */
        const val NAME = "hearwrite.db"

        /** Production builder with the registered migrations. */
        fun create(context: Context): HearWriteDatabase =
            Room.databaseBuilder(context, HearWriteDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
