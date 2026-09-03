package org.yangtse.hearwrite.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One wrong-word mark with its mark time (错词本 drawer rows). */
data class WrongWordMark(val word: String, val addedAt: Long)

/**
 * The global 错词本 (wrong-word book): every word marked wrong across
 * sessions, keyed by the speakable headword and insertion-ordered. Sessions
 * seed their wrong list from here and appends persist immediately — the
 * upstream model (`useWrongWords` seeded from storage, AGENTS.md "Persistence").
 */
class WrongWordsRepository(private val dao: WrongWordsDao) {

    /** Book words, oldest mark first. */
    fun observe(): Flow<List<String>> = dao.observeWords()

    /** Book marks with their times (oldest first) — the 错词本 drawer. */
    fun observeMarks(): Flow<List<WrongWordMark>> =
        dao.observeAll().map { rows -> rows.map { WrongWordMark(it.word, it.addedAt) } }

    suspend fun add(word: String) {
        if (word.isEmpty()) return
        dao.insert(WrongWordEntity(word = word, addedAt = System.currentTimeMillis()))
    }

    suspend fun remove(word: String) = dao.delete(word)

    suspend fun clear() = dao.clear()
}
