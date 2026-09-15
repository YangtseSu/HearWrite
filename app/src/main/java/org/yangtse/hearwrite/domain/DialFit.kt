package org.yangtse.hearwrite.domain

import kotlin.math.sqrt

/**
 * Geometry of the dictation dial.
 *
 * The dial is a disc that `Surface` clips its children to, so content laid out
 * naively loses its corners and anything near the rim disappears. The revealed
 * stack (a two-line word, its POS/拼音, a two-line gloss and the 展开全部 button
 * under it) measured roughly 200–252 dp inside a 204 dp disc: the word's first
 * line touched the rim and the button was clipped away entirely, with nowhere to
 * scroll. The button was invisible to `uiautomator` too — outside the clip, so
 * the screen offered no way at all to read a truncated word (AUDIT C2).
 *
 * The fix is geometric rather than "make the stack shorter": every child is laid
 * out inside the widest rectangle that fits the disc **at that stack's own
 * height**, `width = √(diameter² − height²)`, minus [DialMetrics.marginDp] for
 * font metrics. Width and height trade against each other — a bigger word is a
 * taller stack, hence a narrower box — so [dialFit] searches the font size that
 * a candidate stack can carry. Because the box is computed from the height the
 * children actually occupy, "everything is inside the disc" is a property of the
 * arithmetic, not an estimate to be re-checked by eye.
 *
 * The width model is the repo's fullwidth-aware [displayWidth] (a 汉字 counts 1,
 * halfwidth text 0.5), and fitting is decided by [wrappedLineCount] — the same
 * greedy line breaking the text renderer performs — not by a total-width bound.
 * The two disagree: `0.6 + 0.6 + 0.6` units fits two 1.0-wide lines by total
 * width but wraps to three lines in practice, so a total-width bound would call
 * a word shown that is in fact ellipsized.
 */
data class DialMetrics(
    /** Diameter of the disc the content is clipped to. */
    val diameterDp: Double,
    /** Safety margin shaved off the computed width, covering font metrics. */
    val marginDp: Double = 8.0,
    /** `Density.fontScale`: one sp of text is this many dp wide. */
    val fontScale: Double = 1.0,
    /** Largest font size the word may use (its style's own size). */
    val wordMaxSp: Double,
    /** Smallest readable font size — below this the 展开 card takes over. */
    val wordMinSp: Double,
    /** Lines the word may use before it needs the detail card. */
    val wordMaxLines: Int = 2,
    /** Line height ÷ font size of the word's style. */
    val wordLineRatio: Double,
    /** Font size of the POS/拼音 and gloss lines. */
    val hintFontSizeSp: Double,
    /** Height of one hint line. */
    val hintLineHeightSp: Double,
    /** Gap between the word and its POS/拼音 line. */
    val wordPosGapDp: Double,
    /** Lines the gloss may use before it needs the detail card. */
    val glossMaxLines: Int,
    /** Gap between the POS/拼音 line and the gloss. */
    val glossGapDp: Double,
    /**
     * Narrowest usable content box. Below this a candidate layout is rejected
     * and gloss lines are handed to the detail card instead of squeezing the
     * dial into a sliver.
     */
    val minBoxWidthDp: Double = 96.0,
)

/**
 * Resolved layout of the dial's content — produced by [dialFit] and applied
 * verbatim by the screen, so the guarantee holds in the rendered tree and not
 * only in the arithmetic.
 */
data class DialFit(
    /** Font size the word renders at. */
    val wordFontSizeSp: Double,
    /** Line height for that size (the model's own, not the style's fixed one). */
    val wordLineHeightSp: Double,
    /** Width of the content box: the widest rectangle at [contentHeightDp]. */
    val contentWidthDp: Double,
    /** Height of the content box — what the disc affords at this width. */
    val contentHeightDp: Double,
    /** Lines the word may use (ellipsized beyond, with the detail card offered). */
    val wordLines: Int,
    /** Lines the gloss may use (0 = it is read in the detail card). */
    val glossLines: Int,
    /** The word does not hold [wordLines] lines — read it in the detail card. */
    val wordTruncated: Boolean,
    /** The gloss does not hold [glossLines] lines — read it in the detail card. */
    val glossTruncated: Boolean,
) {
    /** True when something is cut off and the 展开全部 entry is needed. */
    val needsDetail: Boolean get() = wordTruncated || glossTruncated
}

/**
 * Width of the widest rectangle inscribed in a circle of [diameterDp] at height
 * [contentHeightDp] (0 when that height does not fit the disc at all). A square
 * gives `diameter / √2`; a flat box gives the full diameter.
 */
fun dialContentWidthDp(diameterDp: Double, contentHeightDp: Double): Double {
    if (diameterDp <= 0.0 || contentHeightDp < 0.0) return 0.0
    val squared = diameterDp * diameterDp - contentHeightDp * contentHeightDp
    return if (squared <= 0.0) 0.0 else sqrt(squared)
}

/**
 * Widest content box for a stack of a known height — the hidden dial, whose
 * stack (hearing icon + two labels) is fixed-length and needs no solving.
 */
fun dialBoxWidthDp(contentHeightDp: Double, metrics: DialMetrics): Double =
    (dialContentWidthDp(metrics.diameterDp, contentHeightDp) - metrics.marginDp).coerceAtLeast(0.0)

/**
 * Lines [text] occupies when broken greedily into lines of [unitsPerLine] —
 * the model the text renderer follows: a line takes tokens until the next one
 * would overflow, a space between tokens costs [SPACE_UNITS], and a token wider
 * than a line overflows it alone (the renderer ellipsizes that case; the caller
 * treats it as truncated rather than counting it as a line).
 */
fun wrappedLineCount(text: String?, unitsPerLine: Double): Int {
    if (text.isNullOrBlank()) return 0
    if (unitsPerLine <= 0.0) return Int.MAX_VALUE
    val tokens = textTokens(text)
    if (tokens.isEmpty()) return 0
    // A token that cannot fit any line never wraps into shape.
    if (tokens.any { it.width > unitsPerLine + FIT_EPSILON_DP }) return Int.MAX_VALUE
    var lines = 1
    var current = 0.0
    for ((index, token) in tokens.withIndex()) {
        // A token's own flag says a space precedes it. That space costs width
        // when the line continues, and is dropped when the line breaks there —
        // so it is part of `needed`, never of the new line's width. Leading
        // whitespace of the whole text costs nothing.
        val needed = token.width + if (index > 0 && token.spaced) SPACE_UNITS else 0.0
        if (current > 0.0 && current + needed > unitsPerLine + FIT_EPSILON_DP) {
            lines++
            current = token.width
        } else {
            current += needed
        }
    }
    return lines
}

/** One unbreakable run of text and whether a space precedes it. */
private class Token(val width: Double, val spaced: Boolean)

/** A halfwidth space's display width. */
private const val SPACE_UNITS = 0.5

/**
 * Split [text] into unbreakable runs: whitespace separates tokens, a fullwidth
 * glyph (汉字 and friends, `code > 0x2e7f`) is a breakable one-unit token of its
 * own, and a halfwidth run accumulates 0.5 units per character.
 */
private fun textTokens(text: String?): List<Token> {
    if (text.isNullOrBlank()) return emptyList()
    val tokens = mutableListOf<Token>()
    var run = 0.0
    var spaced = false
    for (ch in text) {
        if (ch.isWhitespace()) {
            if (run > 0.0) {
                tokens += Token(run, spaced)
                run = 0.0
            }
            spaced = true
        } else if (ch.code > 0x2e7f) {
            if (run > 0.0) {
                tokens += Token(run, spaced)
                run = 0.0
            }
            tokens += Token(1.0, spaced)
            spaced = false
        } else {
            run += 0.5
        }
    }
    if (run > 0.0) tokens += Token(run, spaced)
    return tokens
}

/**
 * Lay the dial's content out for one entry.
 *
 * Candidates are tried widest-first, because in a disc width and height trade
 * against each other: every extra line costs height and therefore width. Order:
 * one word line before two (a one-line word leaves the widest box), then more
 * gloss lines before fewer. The first candidate that truncates nothing wins, so
 * a word that holds one line keeps a large font and its gloss keeps both lines.
 *
 * When no candidate is clean, the layout that shows the most text is returned
 * with its truncation flags set — a whole word first, since the word is what is
 * being dictated and a gloss is annotation — and the screen offers the card.
 * Nothing that truncates is ever shown without [DialFit.needsDetail] being true.
 */
fun dialFit(word: String?, gloss: String?, hasPos: Boolean, metrics: DialMetrics): DialFit {
    val glossSteps = if (gloss.isNullOrEmpty()) listOf(0) else metrics.glossMaxLines downTo 0
    var best: DialFit? = null
    var bestScore = 0.0
    var bestWholeWord = false
    for (wordLines in 1..metrics.wordMaxLines) {
        for (glossLines in glossSteps) {
            val fit = layoutDial(word, gloss, hasPos, wordLines, glossLines, metrics)
            if (!fit.needsDetail && fit.contentWidthDp >= metrics.minBoxWidthDp) return fit
            // Nothing is clean, so rank by how much text each layout shows: the
            // dp each text occupies inside its box at the size this layout
            // renders it, so a wider-but-shorter box and a narrower-but-taller
            // one compare on one scale.
            val score = minOf(
                displayWidth(word) * metrics.fontScale * fit.wordFontSizeSp,
                wordLines * fit.contentWidthDp,
            ) + minOf(
                displayWidth(gloss) * metrics.fontScale * metrics.hintFontSizeSp,
                glossLines * fit.contentWidthDp,
            )
            val wholeWord = !fit.wordTruncated
            if (best == null || (wholeWord && !bestWholeWord) ||
                (wholeWord == bestWholeWord && score > bestScore)
            ) {
                best = fit
                bestScore = score
                bestWholeWord = wholeWord
            }
        }
    }
    return best ?: layoutDial(word, gloss, hasPos, 1, 0, metrics)
}

private fun layoutDial(
    word: String?,
    gloss: String?,
    hasPos: Boolean,
    wordLines: Int,
    glossLines: Int,
    m: DialMetrics,
): DialFit {
    val hintLineDp = m.hintLineHeightSp * m.fontScale
    // Only what is drawn inside the disc counts: 展开全部 lives under the dial,
    // outside the clip, so it costs the content no height (AUDIT C2 — inside
    // the disc it was what pushed the stack past the rim).
    val tailDp = (if (hasPos) m.wordPosGapDp + hintLineDp else 0.0) +
        (if (glossLines > 0) m.glossGapDp + glossLines * hintLineDp else 0.0)

    // A bigger word is a taller stack, hence a narrower box, hence a word that
    // wraps more: "this size fits" is monotone in the size, so the largest
    // fitting size is found by bisection. Each probe lays the text out with the
    // same greedy wrapping the renderer uses.
    val wordUnits = displayWidth(word)
    val areaDpPerSp = wordLines * m.wordLineRatio * m.fontScale
    val fits = { size: Double ->
        val height = areaDpPerSp * size + tailDp
        val width = dialContentWidthDp(m.diameterDp, height) - m.marginDp
        // The word's ems and the gloss's coincide per line, so one width serves
        // both: `units = width / (fontSize × fontScale)`.
        width > 0.0 &&
            wrappedLineCount(word, width / (size * m.fontScale)) <= wordLines &&
            wrappedLineCount(gloss, width / (m.hintFontSizeSp * m.fontScale)) <= glossLines
    }
    var fontSizeSp = m.wordMaxSp
    if (wordUnits > 0.0 && !fits(m.wordMaxSp)) {
        var low = m.wordMinSp
        var high = m.wordMaxSp
        repeat(FONT_BISECTION_STEPS) {
            val mid = (low + high) / 2.0
            if (fits(mid)) low = mid else high = mid
        }
        fontSizeSp = low
    }

    val heightDp = areaDpPerSp * fontSizeSp + tailDp
    val widthDp = (dialContentWidthDp(m.diameterDp, heightDp) - m.marginDp).coerceAtLeast(0.0)
    val unitsPerWordLine = if (fontSizeSp > 0.0) widthDp / (fontSizeSp * m.fontScale) else 0.0
    val unitsPerGlossLine = if (m.hintFontSizeSp > 0.0) {
        widthDp / (m.hintFontSizeSp * m.fontScale)
    } else {
        0.0
    }
    return DialFit(
        wordFontSizeSp = fontSizeSp,
        wordLineHeightSp = fontSizeSp * m.wordLineRatio,
        contentWidthDp = widthDp,
        contentHeightDp = heightDp,
        wordLines = wordLines,
        glossLines = glossLines,
        wordTruncated = wordUnits > 0.0 &&
            wrappedLineCount(word, unitsPerWordLine) > wordLines,
        glossTruncated = !gloss.isNullOrEmpty() &&
            wrappedLineCount(gloss, unitsPerGlossLine) > glossLines,
    )
}

/** Floating-point slack when asking whether text still fits its box. */
private const val FIT_EPSILON_DP = 1e-6

/** Probes the font-size bisection makes; 30 halvings resolve 18 sp to <0.001. */
private const val FONT_BISECTION_STEPS = 30
