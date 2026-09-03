package org.yangtse.hearwrite.domain

/**
 * Session-start ordering shared by Home and the 词库 preview: slice from
 * [startIndex] (0-based, clamped) and optionally shuffle (stdlib Fisher–Yates
 * over a copy). Applied before staging — the playback engine receives the
 * finished list and knows nothing about these options (AGENTS.md playback
 * engine).
 */
fun prepareStartLines(
    lines: List<String>,
    startIndex: Int,
    shuffle: Boolean,
): List<String> {
    val from = startIndex.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
    val sliced = lines.subList(from, lines.size)
    return if (shuffle) sliced.shuffled() else sliced
}
