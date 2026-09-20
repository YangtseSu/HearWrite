package org.yangtse.hearwrite.domain

/**
 * Kind of one word-list row, decided from the **headword the engine speaks**
 * ([WordRow.speak] — the left side of an `=` expansion, [WordRow.display]
 * otherwise) at parse time (AGENTS.md *Word-line format*).
 *
 * The previous model overloaded the `pos` column — 词性 for English, 拼音 for
 * 汉字 — and every consumer re-guessed the kind from a CJK regex on the raw
 * line; the explicit field is computed once, where the line is parsed.
 */
enum class WordKind {
    /** English headword (no 汉字): `pos` = 词性, `gloss` = 中文释义. */
    EN,

    /** Single 汉字 (识字表/写字表): `pos` = 拼音, `gloss` = 组词. */
    HANZI,

    /** Multi-char Chinese word or sentence (词语表): spoken as-is, no columns. */
    WORD,
}

/**
 * Kind of a speakable headword ([WordRow.speak]): no 汉字 → [WordKind.EN];
 * exactly one 汉字 → [WordKind.HANZI]; anything longer → [WordKind.WORD]. The
 * headword alone decides — a Chinese gloss column never makes an English row
 * Chinese, and neither does a Chinese `=` expansion tail (`apple = 苹果` is
 * an English row).
 */
fun kindOf(headword: String): WordKind = when {
    !CJK_RE.containsMatchIn(headword) -> WordKind.EN
    headword.length == 1 -> WordKind.HANZI
    else -> WordKind.WORD
}
