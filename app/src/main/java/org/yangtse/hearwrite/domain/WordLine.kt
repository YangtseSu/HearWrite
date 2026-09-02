package org.yangtse.hearwrite.domain

/**
 * A parsed word-list line: `word | pos | meaning` (only `word` is required).
 *
 * Column semantics depend on entry kind (see AGENTS.md "Word-line format"):
 * English entries carry `pos` = part of speech and `meaning` = 中文释义;
 * Chinese single-char entries carry `pos` = pinyin and `meaning` = 组词;
 * Chinese words are bare words.
 */
data class WordEntry(
    val word: String,
    val pos: String? = null,
    val meaning: String? = null,
)

private const val PIPE = "|"
private const val FULLWIDTH_PIPE = "｜"
private val PIPE_SPLIT_RE = Regex("[$PIPE$FULLWIDTH_PIPE]")

/** POS prefixes shared by ECDICT and user word lists: "n." "vt." "adj." … */
val POS_PREFIX_RE = Regex(
    """^(n\.|v\.|vt\.|vi\.|adj\.|adv\.|prep\.|conj\.|pron\.|num\.|art\.|int\.|interj\.|aux\.|abbr\.|contr\.|pl\.|a\.|na\.|un\.|vbl\.|pp\.|pn\.|exclam\.|pref\.|suf\.|suff\.|comb\.|quant\.|phr\.|ph\.|st\.|pr\.|ind\.|pers\.|col\.|ing\.|pla\.|stuff\.)\s*""",
    RegexOption.IGNORE_CASE,
)

/**
 * Normalize a POS abbreviation (ECDICT spelling → textbook spelling), same map
 * as the library build script: interj./exclam.→int., na./un./pla./pn.→n.,
 * vbl./pp.→v., pref./suf./suff./comb./stuff.→abbr., a.→adj., pl.→n.
 * Unknown input is returned trimmed and lowercased.
 */
fun normalizePos(pos: String): String {
    val key = pos.trim().lowercase()
    return when (key) {
        "a." -> "adj."
        "pl." -> "n."
        "interj.", "exclam." -> "int."
        "na.", "un.", "pla.", "pn." -> "n."
        "vbl.", "pp." -> "v."
        "pref.", "suf.", "suff.", "comb.", "stuff." -> "abbr."
        else -> key
    }
}

/**
 * Split multiline word-list input into lines (one per non-empty line).
 */
fun parseWords(text: String): List<String> =
    text.split(Regex("[\n\r]+")).map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Parse a single line into a structured entry. Format: `word | pos | meaning`
 * (fullwidth `｜` accepted). Only `word` is required; missing/blank columns
 * are null. Extra pipe-separated columns beyond `meaning` are ignored.
 */
fun parseWordLine(line: String): WordEntry {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return WordEntry("")
    val parts = trimmed.split(PIPE_SPLIT_RE)
    return WordEntry(
        word = parts[0].trim(),
        pos = parts.getOrNull(1)?.trim()?.ifEmpty { null },
        meaning = parts.getOrNull(2)?.trim()?.ifEmpty { null },
    )
}

/** Parse multiline text into structured entries (one per non-empty line). */
fun parseWordEntries(text: String): List<WordEntry> =
    parseWords(text).map(::parseWordLine)

/** Serialize an entry back to its canonical `word | pos | meaning` line. */
fun entryToLine(entry: WordEntry): String {
    if (entry.pos == null && entry.meaning == null) return entry.word
    return listOf(entry.word, entry.pos ?: "", entry.meaning ?: "")
        .joinToString(" $PIPE ")
}
