package org.yangtse.hearwrite.data

import org.yangtse.hearwrite.domain.BUILTIN_LIST_ID_PREFIX
import org.yangtse.hearwrite.domain.MULTI_SOURCE_PREFIX
import org.yangtse.hearwrite.domain.WordRow
import org.yangtse.hearwrite.domain.findRowByHeadword
import org.yangtse.hearwrite.domain.multiSourceIds
import org.yangtse.hearwrite.domain.parseBuiltinListId
import org.yangtse.hearwrite.domain.parseWordLine
import org.yangtse.hearwrite.domain.parseWordRows
import org.yangtse.hearwrite.domain.wordRowOf

/**
 * Restore the original word-list rows behind 错词本 marks (Roadmap #7).
 *
 * A mark carries the id of the list it was dictated from — a built-in list id
 * (`default_<category>_<label>`, shipped as an asset), a history row id (the
 * user's own pasted text), or a `multi:` label naming the several built-in
 * lists a 抽词听写 pool was assembled from (Roadmap #9); null means manual
 * input. Resolving a mark back to its stored row brings the 词性/释义 or
 * 拼音/组词 columns back for 复习错词 / 听写错词 instead of dictating a bare
 * headword; a mark whose source no longer resolves (deleted history row,
 * renamed list) or never existed stays a bare headword row — the book
 * outlives its sources.
 *
 * The two loaders are the seam: the application wires them to the asset
 * library and Room, JVM tests pass plain lambdas — no Android dependency in
 * this file.
 */
class WrongWordLineResolver(
    /** A built-in list's canonical `word | pos | meaning` lines. */
    private val builtinListLines: suspend (category: String, label: String) -> List<String>,
    /** A history row's stored text, enriched (`word | pos | meaning`) when it has one. */
    private val historyText: suspend (historyId: String) -> String?,
) {

    /**
     * One row per mark, in mark order: the run's own rows ([preferred]) win,
     * then the mark's source, then the bare headword. Each source is loaded
     * once per call — a book whose marks share a list reads that list once.
     */
    suspend fun rowsFor(
        marks: List<WrongWordMark>,
        preferred: List<WordRow> = emptyList(),
    ): List<WordRow> {
        val cache = HashMap<String, List<WordRow>>()
        return marks.map { mark ->
            findRowByHeadword(preferred, mark.word)
                ?: findRowByHeadword(sourceRows(mark.sourceLabel, cache), mark.word)
                ?: wordRowOf(mark.word)
        }
    }

    /** Rows of one source, cached in [cache] (null source → nothing to load). */
    private suspend fun sourceRows(
        sourceLabel: String?,
        cache: MutableMap<String, List<WordRow>>,
    ): List<WordRow> {
        if (sourceLabel == null) return emptyList()
        cache[sourceLabel]?.let { return it }
        val rows = try {
            when {
                // A 抽词听写 pool: search every member list for the word.
                sourceLabel.startsWith(MULTI_SOURCE_PREFIX) ->
                    multiSourceIds(sourceLabel).flatMap { builtinRows(it) }
                sourceLabel.startsWith(BUILTIN_LIST_ID_PREFIX) -> builtinRows(sourceLabel)
                else -> historyRows(sourceLabel)
            }
        } catch (e: Exception) {
            // A source that no longer loads (pruned history row, list gone,
            // asset read failure) degrades to the bare headword; the mark is
            // never dropped.
            emptyList()
        }
        cache[sourceLabel] = rows
        return rows
    }

    private suspend fun builtinRows(id: String): List<WordRow> {
        val (category, label) = parseBuiltinListId(id) ?: return emptyList()
        return builtinListLines(category, label).map(::parseWordLine)
    }

    private suspend fun historyRows(id: String): List<WordRow> =
        historyText(id)?.let(::parseWordRows).orEmpty()
}
