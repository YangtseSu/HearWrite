package org.yangtse.hearwrite.domain

/**
 * Session-start ordering shared by Home and the 词库 preview: slice from
 * [startIndex] (0-based, clamped) and optionally shuffle (stdlib Fisher–Yates
 * over a copy). Applied before staging — the playback engine receives the
 * finished rows and knows nothing about these options (AGENTS.md playback
 * engine).
 */
fun prepareStartRows(
    rows: List<WordRow>,
    startIndex: Int,
    shuffle: Boolean,
): List<WordRow> {
    val from = startIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    val sliced = rows.subList(from, rows.size)
    return if (shuffle) sliced.shuffled() else sliced
}
