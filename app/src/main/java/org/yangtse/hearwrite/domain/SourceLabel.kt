package org.yangtse.hearwrite.domain

/** Prefix of a built-in library list id (`default_<category>_<label>`). */
const val BUILTIN_LIST_ID_PREFIX = "default_"

/**
 * Source label prefix of a 抽词听写 session assembled from several lists
 * (Roadmap #9): `multi:<id>,<id>,…` over built-in list ids. The 错词本 and the
 * `sessions` record store one string per run, so a drawn session keeps the
 * member lists instead of degrading to "no source": the resolver looks the
 * mark up in every member list and the UI titles the group 多词表. Safe
 * because built-in ids, category names and labels never contain `,`
 * (AGENTS.md "Built-in library").
 */
const val MULTI_SOURCE_PREFIX = "multi:"

/** Encode the member list ids of a drawn session (selection order). */
fun multiSourceLabel(ids: List<String>): String =
    MULTI_SOURCE_PREFIX + ids.joinToString(",")

/** Member list ids behind a [multiSourceLabel] — empty for any other label. */
fun multiSourceIds(label: String): List<String> {
    if (!label.startsWith(MULTI_SOURCE_PREFIX)) return emptyList()
    return label.removePrefix(MULTI_SOURCE_PREFIX).split(',').filter { it.isNotEmpty() }
}

/** Parse a `default_<category>_<label>` id back into its parts; null otherwise. */
fun parseBuiltinListId(id: String): Pair<String, String>? {
    if (!id.startsWith(BUILTIN_LIST_ID_PREFIX)) return null
    val parts = id.removePrefix(BUILTIN_LIST_ID_PREFIX).split("_", limit = 2)
    return if (parts.size == 2) parts[0] to parts[1] else null
}
