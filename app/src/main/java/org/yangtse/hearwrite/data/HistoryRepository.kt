package org.yangtse.hearwrite.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One user history row, newest first in [observe]. */
data class HistoryEntry(
    val id: String,
    val text: String,
    val createdAt: Long,
)

/**
 * History persistence with the upstream row semantics (AGENTS.md "Persistence"
 * + `alice/src/lib/storage.ts`): user-pasted lists only, dedupe by exact
 * text (a re-run bumps the row to the front instead of duplicating), hard cap
 * of 50 dropping the oldest. Favorite rows whose history entry disappears are
 * pruned in the same call.
 *
 * The stored text is the authored list and nothing else: 词性/释义 are read
 * from the offline lexicon when they are shown, never baked into a row
 * (AGENTS.md *Persistence* — the `enrichedText` column this
 * repository used to write was dropped in Room v5).
 */
class HistoryRepository(
    private val historyDao: HistoryDao,
    private val favoritesDao: FavoritesDao,
) {
    /** Newest first; mapped to plain rows for the UI. */
    fun observe(): Flow<List<HistoryEntry>> =
        historyDao.observeAll().map { entities ->
            entities.map { HistoryEntry(it.id, it.text, it.createdAt) }
        }

    /**
     * One-shot read of the stored rows, newest first — for callers that need
     * a snapshot rather than the live flow (the 错词本 source resolution reads
     * a dictation source's stored text, Roadmap #7).
     */
    suspend fun all(): List<HistoryEntry> =
        historyDao.all().map { HistoryEntry(it.id, it.text, it.createdAt) }

    /**
     * Record a started user list: the authored [text], verbatim. An existing
     * row with the same text is bumped to the front with a fresh timestamp.
     *
     * @return the stored row id — the provenance key a dictation started from
     *         this list carries into the 错词本 (Roadmap #1 `sourceLabel`).
     */
    suspend fun add(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val now = System.currentTimeMillis()
        val existing = historyDao.all().firstOrNull { it.text == trimmed }
        val id: String = if (existing != null) {
            historyDao.insert(existing.copy(createdAt = now))
            existing.id
        } else {
            val fresh = HistoryEntity(
                id = "${now}_${randomSuffix()}",
                text = trimmed,
                createdAt = now,
            )
            historyDao.insert(fresh)
            fresh.id
        }
        historyDao.trimTo(MAX_HISTORY)
        favoritesDao.pruneHistoryOrphans()
        return id
    }

    suspend fun delete(id: String) {
        historyDao.delete(id)
        favoritesDao.pruneHistoryOrphans()
    }

    /**
     * Undo a [delete]: put [entry] back as the same row id (so a 错词本 source
     * or favorite pointing at it resolves again). Deleting a favorited row also
     * prunes its favorite ([pruneHistoryOrphans]), so [wasFavorited] restores
     * that too — a 撤销 that silently dropped the star would be worse than no
     * undo. No [trimTo]: restoring one just-removed row cannot exceed the cap
     * the delete itself brought the list under.
     */
    suspend fun restore(entry: HistoryEntry, wasFavorited: Boolean) {
        historyDao.insertExact(
            HistoryEntity(
                id = entry.id,
                text = entry.text,
                createdAt = entry.createdAt,
            )
        )
        if (wasFavorited) favoritesDao.insert(FavoriteEntity(entry.id))
    }

    suspend fun clear() {
        historyDao.clear()
        favoritesDao.pruneHistoryOrphans()
    }

    companion object {
        /** AGENTS.md: history cap — drop the oldest beyond this. */
        const val MAX_HISTORY = 50

        /** Uniqueness suffix, like upstream (`Date.now() + random base36`). */
        private fun randomSuffix(): String {
            val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
            return buildString(6) {
                repeat(6) { append(alphabet.random()) }
            }
        }
    }
}
