package org.yangtse.hearwrite.ui

import org.yangtse.hearwrite.data.HistoryEntry
import org.yangtse.hearwrite.domain.BUILTIN_LIST_ID_PREFIX
import org.yangtse.hearwrite.domain.MULTI_SOURCE_PREFIX
import org.yangtse.hearwrite.domain.multiSourceIds
import org.yangtse.hearwrite.domain.parseBuiltinListId

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
        ?.text
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

/**
 * A built-in library list a stored source id resolves back to: the category to
 * open and the label its preview is routed by.
 */
data class SourceJump(val category: String, val label: String)

/**
 * The library list a stored source id points back to, or null when there is
 * nothing to jump to: a history row (whose text is not a list), a 抽词听写
 * `multi:` pool (its members are named in the resolved title instead), a manual
 * mark, and a built-in list that no longer resolves ([title] is the resolved
 * label, so a renamed-away list yields null rather than a dead route).
 *
 * Shared by the 错词本 drawer's 查看词表 and the 听写统计 rows' jump so both
 * offer it on exactly the same sources.
 */
fun resolveSourceJump(sourceLabel: String?, title: String?): SourceJump? {
    val parts = parseBuiltinListId(sourceLabel ?: return null) ?: return null
    return title?.let { SourceJump(parts.first, it) }
}
