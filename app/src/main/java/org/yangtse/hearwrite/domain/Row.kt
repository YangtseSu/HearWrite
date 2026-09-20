package org.yangtse.hearwrite.domain

/**
 * One row of a word list — author data (`docs/2026-09-18-DATA-MODEL.md` §1.1):
 * ordered, hand-editable, diffable, and **never rewritten by the app**. The
 * dictionary is a shared lookup table resolved at read time, so no consumer
 * ever writes a looked-up column back into a row (the old model's
 * `enrichLines` round-trip is what silently dropped columns and left rows
 * without 音标).
 *
 * Column semantics by [kind] (AGENTS.md "Word-line format"):
 * - [WordKind.EN] — `pos` = part of speech, `gloss` = 中文释义;
 * - [WordKind.HANZI] — `pos` = pinyin with tone marks, `gloss` = 组词;
 * - [WordKind.WORD] — bare word, spoken as-is.
 *
 * [kind] describes [speak], not [display]: an `=` expansion tail is gloss-like
 * text the engine never says, so `apple = 苹果` is an [WordKind.EN] row.
 */
data class WordRow(
    /** 展示/作答/错词键 — as printed in the list (`you're = you are`). */
    val display: String,
    /** TTS text: the left side of an `=` expansion, else [display]. */
    override val speak: String,
    override val kind: WordKind,
    val pos: String? = null,
    val gloss: String? = null,
) : SpeakableWord

private const val PIPE = "|"
private const val FULLWIDTH_PIPE = "｜"
private val PIPE_SPLIT_RE = Regex("[$PIPE$FULLWIDTH_PIPE]")

/**
 * JS `String.trim()`-parity edge trim for user-supplied list text: Kotlin's
 * no-arg trim strips Unicode whitespace (U+3000, U+00A0, …) but keeps
 * U+FEFF (BOM / ZWNBSP), which upstream JS trim removes — a pasted file
 * saved as UTF-8 with BOM would otherwise prefix its first headword with an
 * invisible char and silently break single-char 组词 and wrong-word keys.
 */
internal fun jsEdgeTrim(s: String): String =
    s.trim { it.isWhitespace() || it == '\uFEFF' }

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
 * Speech text of a display headword: the left side of an expansion
 * (`you're = you are` → `you're`; an empty left side falls back to the whole
 * text). The only kind-independent parsing rule the old raw-line helper
 * carried — it is now resolved once, at parse time, into [WordRow.speak].
 */
internal fun speakOf(display: String): String {
    val text = jsEdgeTrim(display)
    if (text.isEmpty()) return ""
    val eq = text.indexOfFirst { it == '=' || it == '＝' }
    if (eq == -1) return text
    val left = jsEdgeTrim(text.substring(0, eq))
    return left.ifEmpty { text }
}

/**
 * Build a row from a display headword: [WordRow.speak] is the left side of an
 * `=` expansion, and [WordRow.kind] is read off **that** speakable side — the
 * text the engine actually says, so `apple = 苹果` stays `EN` (a display tail
 * never decides how a row is spoken or looked up).
 */
fun wordRowOf(display: String, pos: String? = null, gloss: String? = null): WordRow {
    val speak = speakOf(display)
    return WordRow(display, speak, kindOf(speak), pos, gloss)
}

/**
 * Split multiline word-list input into lines (one per non-empty line).
 */
fun parseWords(text: String): List<String> =
    text.split(Regex("[\n\r]+")).map(::jsEdgeTrim).filter { it.isNotEmpty() }

/**
 * Parse a single line into a [WordRow]. Format: `word | pos | meaning`
 * (fullwidth `｜` accepted). Only `word` is required; missing/blank columns
 * are null. `word | pinyin` (two columns) is the hint-only 生字 shape an
 * author writes for a char whose 组词 is unknown. Extra pipe-separated columns
 * beyond `meaning` are ignored.
 */
fun parseWordLine(line: String): WordRow {
    val trimmed = jsEdgeTrim(line)
    if (trimmed.isEmpty()) return wordRowOf("")
    val parts = trimmed.split(PIPE_SPLIT_RE)
    return wordRowOf(
        display = jsEdgeTrim(parts[0]),
        pos = parts.getOrNull(1)?.let(::jsEdgeTrim)?.ifEmpty { null },
        gloss = parts.getOrNull(2)?.let(::jsEdgeTrim)?.ifEmpty { null },
    )
}

/** Parse multiline text into rows (one per non-empty line). */
fun parseWordRows(text: String): List<WordRow> =
    parseWords(text).map(::parseWordLine)

/**
 * Serialize a row back to its canonical line: `word`, or as many columns as
 * carry content — `word | pinyin` for a hint-only 生字 row, `word | pos |
 * gloss` otherwise. A gap before a present column keeps its empty
 * placeholder (`word |  | gloss`), because the parser reads columns by
 * position. The one place a row becomes text again: the authored list — draft
 * persistence, history, and each writer that round-trips a user list.
 */
fun rowToLine(row: WordRow): String = when {
    row.gloss != null -> listOf(row.display, row.pos ?: "", row.gloss)
        .joinToString(" $PIPE ")
    row.pos != null -> "${row.display} $PIPE ${row.pos}"
    else -> row.display
}


