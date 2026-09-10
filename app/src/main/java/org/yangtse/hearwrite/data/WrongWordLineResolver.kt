package org.yangtse.hearwrite.data

import org.yangtse.hearwrite.domain.findLineByHeadword
import org.yangtse.hearwrite.domain.parseWords

/** Id prefix of a built-in library list (`default_<category>_<label>`). */
private const val BUILTIN_PREFIX = "default_"

/**
 * Restore the original word-list lines behind 错词本 marks (Roadmap #7).
 *
 * A mark carries the id of the list it was dictated from — a built-in list id
 * (`default_<category>_<label>`, shipped as an asset) or a history row id
 * (the user's own pasted text); null means manual input. Resolving a mark back
 * to its stored line brings the 词性/释义 or 拼音/组词 columns back for
 * 复习错词 / 听写错词 instead of dictating a bare headword; a mark whose source
 * no longer resolves (deleted history row, renamed list) or never existed
 * stays a bare headword — the book outlives its sources.
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
     * One line per mark, in mark order: the run's own lines ([preferred]) win,
     * then the mark's source, then the bare headword. Each source is loaded
     * once per call — a book whose marks share a list reads that list once.
     */
    suspend fun linesFor(
        marks: List<WrongWordMark>,
        preferred: List<String> = emptyList(),
    ): List<String> {
        val cache = HashMap<String, List<String>>()
        return marks.map { mark ->
            findLineByHeadword(preferred, mark.word)
                ?: findLineByHeadword(sourceLines(mark.sourceLabel, cache), mark.word)
                ?: mark.word
        }
    }

    /** Lines of one source, cached in [cache] (null source → nothing to load). */
    private suspend fun sourceLines(
        sourceLabel: String?,
        cache: MutableMap<String, List<String>>,
    ): List<String> {
        if (sourceLabel == null) return emptyList()
        cache[sourceLabel]?.let { return it }
        val lines = try {
            if (sourceLabel.startsWith(BUILTIN_PREFIX)) {
                builtinLines(sourceLabel)
            } else {
                historyLines(sourceLabel)
            }
        } catch (e: Exception) {
            // A source that no longer loads (pruned history row, list gone,
            // asset read failure) degrades to the bare headword; the mark is
            // never dropped.
            emptyList()
        }
        cache[sourceLabel] = lines
        return lines
    }

    private suspend fun builtinLines(id: String): List<String> {
        // `default_<category>_<label>` — labels never contain underscores.
        val parts = id.removePrefix(BUILTIN_PREFIX).split("_", limit = 2)
        if (parts.size != 2) return emptyList()
        return builtinListLines(parts[0], parts[1])
    }

    private suspend fun historyLines(id: String): List<String> =
        historyText(id)?.let(::parseWords).orEmpty()
}
