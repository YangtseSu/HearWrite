package org.yangtse.hearwrite.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * One 错词本 row for the UI: the headword plus its error count, most-recent
 * mark time, and the raw source id it was dictated from — a built-in list id
 * (`default_<category>_<label>`), a history row id, or null = manual input /
 * bare-word review. Display titles and library jumps resolve in the UI layer,
 * which holds the history rows and asset access.
 */
data class WrongWordMark(
    val word: String,
    val errorCount: Int,
    val lastWrongAt: Long,
    val sourceLabel: String?,
) {
    /** Sort groups the book by most-wrong first, then most-recent mark. */
    val sortKey: Pair<Int, Long> get() = errorCount to lastWrongAt
}

/**
 * The global 错词本 (wrong-word book): every word marked wrong across
 * sessions, keyed by the speakable headword (AGENTS.md "Persistence").
 *
 * v2 semantics (Roadmap #1): [add] records one mark of a **new dictation
 * run** — an existing headword bumps its error count +1 and refreshes the
 * mark time instead of being re-inserted. The caller passes the source of
 * the run; manual/bare sessions pass null and never downgrade a stored
 * source. Source ids degrade to "未知来源" in the UI when their row/list is
 * gone — the wrong word is never deleted with its source.
 */
class WrongWordsRepository(private val dao: WrongWordsDao) {

    /** Book words, most-wrong first (count desc, then most-recent mark). */
    fun observe(): Flow<List<String>> = dao.observeWords()

    /** Book rows with their raw source ids, most-wrong first — the 错词本. */
    fun observeMarks(): Flow<List<WrongWordMark>> =
        dao.observeAll().map { rows ->
            rows.map { WrongWordMark(it.word, it.errorCount, it.lastWrongAt, it.sourceLabel) }
        }

    /**
     * Record one wrong-word mark of a new run. [sourceLabel] is the run's
     * provenance id (`default_*` built-in id, history row id) or null for a
     * bare-word run (听写错词 over the book / manual input). The count bumps
     * once per run — re-marks inside a single run dedupe at the caller.
     */
    suspend fun add(word: String, sourceLabel: String?) {
        if (word.isEmpty()) return
        val now = System.currentTimeMillis()
        dao.recordMark(word, addedAt = now, lastWrongAt = now, sourceLabel = sourceLabel)
    }

    suspend fun remove(word: String) = dao.delete(word)

    suspend fun clear() = dao.clear()
}
