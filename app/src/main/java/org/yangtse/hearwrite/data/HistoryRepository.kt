package org.yangtse.hearwrite.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One user history row, newest first in [observe]. */
data class HistoryEntry(
    val id: String,
    val text: String,
    val enrichedText: String?,
    val createdAt: Long,
)

/**
 * History persistence with the upstream row semantics (AGENTS.md "Persistence"
 * + `alice/src/lib/storage.ts`): user-pasted lists only, dedupe by exact
 * text (a re-run bumps the row to the front instead of duplicating), ECDICT
 * enriched text attached to the row, hard cap of 50 dropping the oldest.
 * Favorite rows whose history entry disappears are pruned in the same call.
 */
class HistoryRepository(
    private val historyDao: HistoryDao,
    private val favoritesDao: FavoritesDao,
) {
    /** Newest first; mapped to plain rows for the UI. */
    fun observe(): Flow<List<HistoryEntry>> =
        historyDao.observeAll().map { entities ->
            entities.map { HistoryEntry(it.id, it.text, it.enrichedText, it.createdAt) }
        }

    /**
     * One-shot read of the stored rows, newest first — for callers that need
     * a snapshot rather than the live flow (the 错词本 source resolution reads
     * a dictation source's stored text, Roadmap #7).
     */
    suspend fun all(): List<HistoryEntry> =
        historyDao.all().map { HistoryEntry(it.id, it.text, it.enrichedText, it.createdAt) }

    /**
     * Record a started user list. [enrichedText] is the ECDICT-expanded text
     * (null when enrichment changed nothing — the plain text is then the
     * row's only form). An existing row whose text or enriched text matches
     * is bumped to the front with a fresh timestamp.
     *
     * @return the stored row id — the provenance key a dictation started from
     *         this list carries into the 错词本 (Roadmap #1 `sourceLabel`).
     */
    suspend fun add(text: String, enrichedText: String?): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val effective = enrichedText?.trim()?.takeIf { it.isNotEmpty() && it != trimmed }
        val now = System.currentTimeMillis()
        val existing = historyDao.all().firstOrNull { e ->
            e.text == trimmed || e.text == effective ||
                (effective != null && e.enrichedText == effective) ||
                // Re-dictating a stored enriched row submits the enriched
                // text itself (enrich() is then a no-op → effective == null);
                // it is the same content, not a new row.
                (effective == null && e.enrichedText == trimmed)
        }
        val id: String = if (existing != null) {
            // Bump to the front; attach enrichment that was missing before.
            val mergedEnriched = existing.enrichedText ?: effective
            historyDao.insert(
                existing.copy(createdAt = now, enrichedText = mergedEnriched)
            )
            existing.id
        } else {
            val fresh = HistoryEntity(
                id = "${now}_${randomSuffix()}",
                text = trimmed,
                enrichedText = effective,
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
