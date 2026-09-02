package org.yangtse.hearwrite.domain

private val CJK_RE = Regex("[\u4e00-\u9fff]")
private val SENSE_SPLIT_RE = Regex("[；;]")
private val GLOSS_SPLIT_RE = Regex("[，,、]")
private val MEANING_PAREN_RE = Regex("[（(][^（）()]*[）)]")
private val MEANING_EDGE_PUNCT_RE = Regex("^[\\s，,、。.：:；;]+|[\\s，,、。.：:；;]+$")
/** 朗读释义的长度上限（视觉宽度，全角 1、半角 0.5），超过则截取首个词条。 */
private const val SPEAK_MEANING_MAX_WIDTH = 12

/**
 * Text to speak for a list line.
 *
 * Strips the `|`-delimited pos/meaning suffix (TTS must not read them) and
 * supports expansion-style entries like `you're = you are`: speak the left
 * side (`you're`) while the full line remains the display/answer text.
 */
fun speakTextFromEntry(entry: String): String {
    var text = entry.trim()
    if (text.isEmpty()) return ""

    // Strip pos/meaning after the pipe delimiter (ASCII only, as upstream).
    val pipe = text.indexOf('|')
    if (pipe != -1) text = text.substring(0, pipe).trim()
    if (text.isEmpty()) return ""

    val eq = text.indexOfFirst { it == '=' || it == '＝' }
    if (eq == -1) return text

    val left = text.substring(0, eq).trim()
    return left.ifEmpty { text }
}

/** True when the speakable headword is Chinese (汉字/词语听写). */
fun isCjkEntry(entry: String): Boolean =
    CJK_RE.containsMatchIn(speakTextFromEntry(entry))

/** Fullwidth chars count 1, halfwidth 0.5 — same measure as the gloss display. */
private fun meaningWidth(text: String): Double {
    var width = 0.0
    for (ch in text) width += if (ch.code > 0x2e7f) 1.0 else 0.5
    return width
}

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
        var text = raw.trim()
        val pos = POS_PREFIX_RE.matchAt(text, 0)
        if (pos != null) text = text.substring(pos.range.last + 1).trim()
        text = text.replace(MEANING_PAREN_RE, "").trim()
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
