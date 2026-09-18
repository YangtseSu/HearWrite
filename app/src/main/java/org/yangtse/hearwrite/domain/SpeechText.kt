package org.yangtse.hearwrite.domain

/** Shared by the speech-text rules of this file, [kindOf] and [cjkWordSpeech]. */
internal val CJK_RE = Regex("[\u4e00-\u9fff]")
internal val SENSE_SPLIT_RE = Regex("[；;]")
internal val GLOSS_SPLIT_RE = Regex("[，,、]")
internal val MEANING_PAREN_RE = Regex("[（(][^（）()]*[）)]")
internal val MEANING_EDGE_PUNCT_RE = Regex("^[\\s，,、。.：:；;]+|[\\s，,、。.：:；;]+$")
/** 朗读释义的长度上限（视觉宽度，全角 1、半角 0.5），超过则截取首个词条。 */
private const val SPEAK_MEANING_MAX_WIDTH = 12

/**
 * True when a gloss visibly truncates under the shared 2-line list-row clamp
 * (Home 展示态 rows and the library preview rows): long text or multi-sense
 * (`；;`-split) glosses offer tap-to-expand. Mirrors the display rules in the
 * alice fork. The dictation dial does **not** use this gate — its content box is
 * a fraction of a disc, so its clamp is solved geometrically in
 * [org.yangtse.hearwrite.domain.dialFit].
 */
fun glossNeedsExpansion(meaning: String?): Boolean =
    (meaning?.length ?: 0) > 24 ||
        (meaning?.count { it == '；' || it == ';' } ?: 0) > 0

/**
 * Visual width of [text] in display units: a fullwidth char (汉字, CJK
 * punctuation, fullwidth forms) counts 1, anything else 0.5 — so a 12-char
 * Chinese gloss and a 24-char Latin one measure the same. The single measure
 * behind the spoken gloss cap, the 释义 display and the dial's clamp gates
 * (a character count gets CJK wrong by a factor of two).
 */
fun displayWidth(text: String?): Double {
    var width = 0.0
    for (ch in text.orEmpty()) width += if (ch.code > 0x2e7f) 1.0 else 0.5
    return width
}

/** Internal alias kept so the speech rules read in gloss terms. */
private fun meaningWidth(text: String): Double = displayWidth(text)

/**
 * 朗读用的中文释义。与释义展示不同，TTS 只需要最核心的一个意思:
 *
 * - strip a leading POS prefix ("n." "vt." … would be spelled out letter by
 *   letter by TTS);
 * - take the first non-empty sense of the `；;`-split gloss;
 * - parentheticals (English expansions etc.) are not spoken;
 * - if the first sense still exceeds the visual width cap 12, cut it at the
 *   first `，,、` boundary;
 * - edge punctuation is trimmed at every stage.
 *
 * Returns "" when there is nothing speakable (e.g. a POS-only gloss).
 */
fun speakableMeaning(meaning: String?): String {
    if (meaning == null) return ""
    for (raw in meaning.split(SENSE_SPLIT_RE)) {
        var text = jsEdgeTrim(raw)
        val pos = POS_PREFIX_RE.matchAt(text, 0)
        if (pos != null) text = jsEdgeTrim(text.substring(pos.range.last + 1))
        text = jsEdgeTrim(text.replace(MEANING_PAREN_RE, ""))
        text = text.replace(MEANING_EDGE_PUNCT_RE, "")
        if (text.isEmpty()) continue

        if (meaningWidth(text) > SPEAK_MEANING_MAX_WIDTH) {
            text = text.split(GLOSS_SPLIT_RE, limit = 2)[0]
            text = text.replace(MEANING_EDGE_PUNCT_RE, "")
            if (text.isEmpty()) continue
        }
        return text
    }
    return ""
}
