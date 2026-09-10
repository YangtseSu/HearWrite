package org.yangtse.hearwrite.ui

import org.yangtse.hearwrite.data.HistoryEntry

/**
 * Resolve a stored source id to a display title, shared by the 错词本 drawer
 * (HomeViewModel) and the 听写统计 page: a built-in list id resolves through
 * the library title map, a history row id through the row's own first
 * headword, and a null/unresolvable id to null — the caller degrades that to
 * 未知来源 rather than dropping the row (the record outlives its sources).
 */
fun resolveSourceTitle(
    sourceLabel: String?,
    history: List<HistoryEntry>,
    libraryTitles: Map<String, String>,
): String? = when {
    sourceLabel == null -> null
    sourceLabel.startsWith("default_") -> libraryTitles[sourceLabel]
    else -> history.firstOrNull { it.id == sourceLabel }
        ?.let { it.enrichedText ?: it.text }
        ?.lineSequence()?.firstOrNull { it.isNotBlank() }
        // The stored line is `word | pos | meaning`; the source title is the
        // list's headword, not the whole gloss.
        ?.substringBefore('|')?.trim()
}
