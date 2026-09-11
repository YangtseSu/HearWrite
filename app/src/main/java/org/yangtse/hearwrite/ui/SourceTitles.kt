package org.yangtse.hearwrite.ui

import org.yangtse.hearwrite.data.HistoryEntry
import org.yangtse.hearwrite.domain.BUILTIN_LIST_ID_PREFIX
import org.yangtse.hearwrite.domain.MULTI_SOURCE_PREFIX
import org.yangtse.hearwrite.domain.multiSourceIds

/**
 * Resolve a stored source id to a display title, shared by the 错词本 drawer
 * (HomeViewModel) and the 听写统计 page: a built-in list id resolves through
 * the library title map, a 抽词听写 `multi:` label to its first member list
 * plus the member count, a history row id through the row's own first
 * headword, and a null/unresolvable id to null — the caller degrades that to
 * 未知来源 rather than dropping the row (the record outlives its sources).
 */
fun resolveSourceTitle(
    sourceLabel: String?,
    history: List<HistoryEntry>,
    libraryTitles: Map<String, String>,
): String? = when {
    sourceLabel == null -> null
    sourceLabel.startsWith(MULTI_SOURCE_PREFIX) -> multiSourceTitle(sourceLabel, libraryTitles)
    sourceLabel.startsWith(BUILTIN_LIST_ID_PREFIX) -> libraryTitles[sourceLabel]
    else -> history.firstOrNull { it.id == sourceLabel }
        ?.let { it.enrichedText ?: it.text }
        ?.lineSequence()?.firstOrNull { it.isNotBlank() }
        // The stored line is `word | pos | meaning`; the source title is the
        // list's headword, not the whole gloss.
        ?.substringBefore('|')?.trim()
}

/**
 * Title of a multi-list (抽词听写) source: the first member list that still
 * resolves plus the total member count, or a bare 多词表 when none do (the
 * member lists were renamed away — the group still shows and never drops).
 */
private fun multiSourceTitle(label: String, libraryTitles: Map<String, String>): String {
    val ids = multiSourceIds(label)
    if (ids.size == 1) return libraryTitles[ids[0]] ?: "多词表"
    val first = ids.firstNotNullOfOrNull { libraryTitles[it] }
    return if (first == null) "多词表（${ids.size} 个词表）" else "$first 等 ${ids.size} 个词表"
}
