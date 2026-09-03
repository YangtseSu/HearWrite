package org.yangtse.hearwrite.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A headword marked wrong during dictation; keyed by the speakable headword
 *  exactly like `alice/src/lib/storage.ts` (AGENTS.md "Persistence"). */
@Entity(tableName = "wrong_words")
data class WrongWordEntity(
    @PrimaryKey val word: String,
    val addedAt: Long,
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
    @Query("SELECT word FROM wrong_words ORDER BY addedAt ASC, rowid ASC")
    fun observeWords(): Flow<List<String>>

    @Query("SELECT * FROM wrong_words ORDER BY addedAt ASC, rowid ASC")
    fun observeAll(): Flow<List<WrongWordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(word: WrongWordEntity)

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

    /** Keep only the newest [limit] rows (cap 50, drop oldest — AGENTS.md). */
    @Query(
        "DELETE FROM history WHERE id NOT IN " +
            "(SELECT id FROM history ORDER BY createdAt DESC, rowid DESC LIMIT :limit)"
    )
    abstract suspend fun trimTo(limit: Int)

    @Query("DELETE FROM history WHERE id = :id")
    abstract suspend fun delete(id: String)

    @Query("DELETE FROM history")
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

@androidx.room.Database(
    entities = [WrongWordEntity::class, HistoryEntity::class, FavoriteEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class HearWriteDatabase : androidx.room.RoomDatabase() {
    abstract fun wrongWordsDao(): WrongWordsDao
    abstract fun historyDao(): HistoryDao
    abstract fun favoritesDao(): FavoritesDao
}
