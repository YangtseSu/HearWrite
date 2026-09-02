package org.yangtse.hearwrite.data

import kotlinx.coroutines.flow.Flow

/**
 * The global 错词本 (wrong-word book): every word marked wrong across
 * sessions, keyed by the speakable headword and insertion-ordered. Sessions
 * seed their wrong list from here and appends persist immediately — the
 * upstream model (`useWrongWords` seeded from storage, AGENTS.md "Persistence").
 */
class WrongWordsRepository(private val dao: WrongWordsDao) {

    /** Book words, oldest mark first. */
    fun observe(): Flow<List<String>> = dao.observeWords()

    suspend fun add(word: String) {
        if (word.isEmpty()) return
        dao.insert(WrongWordEntity(word = word, addedAt = System.currentTimeMillis()))
    }

    suspend fun remove(word: String) = dao.delete(word)

    suspend fun clear() = dao.clear()
}
