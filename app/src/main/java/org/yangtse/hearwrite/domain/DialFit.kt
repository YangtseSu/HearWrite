package org.yangtse.hearwrite.domain

import kotlin.math.sqrt

/**
 * Geometry of the dictation dial.
 *
 * The dial is a disc that `Surface` clips its children to, so content laid out
 * naively loses its corners and anything near the rim disappears. The revealed
 * stack (a two-line word, its 音标/拼音/词性 hint line, a two-line gloss and the
 * 展开全部 button under it) measured roughly 200–252 dp inside a 204 dp disc: the
 * word's first line touched the rim and the button was clipped away entirely,
 * with nowhere to scroll. The button was invisible to `uiautomator` too —
 * outside the clip, so the screen offered no way at all to read a truncated
 * word (AUDIT C2).
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
    /** Font size of the hint line (音标/拼音/词性) and the gloss lines. */
    val hintFontSizeSp: Double,
    /** Height of one hint line. */
    val hintLineHeightSp: Double,
    /** Gap between the word and its hint line. */
    val wordHintGapDp: Double,
    /** Lines the gloss may use before it needs the detail card. */
    val glossMaxLines: Int,
    /** Gap between the hint line and the gloss. */
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
 * The hidden dial's stack (hearing icon, 听写中/已暂停, 点按显示词语) as data, in
 * the units the solver needs. Text is carried as **sp** plus the density's
 * [fontScale], never as pre-multiplied dp: a 16 sp label is 16 dp wide per unit
 * at scale 1 and 24 dp at scale 1.5, and treating the two as one number is what
 * makes a stack "fit" in the arithmetic and overflow on screen.
 */
data class DialHiddenMetrics(
    val iconDp: Double,
    val iconGapDp: Double,
    val stateGapDp: Double,
    val stateFontSp: Double,
    val stateLineSp: Double,
    /** Width of the state word in [displayWidth] units (3 for 听写中). */
    val stateUnits: Double,
    val hintFontSp: Double,
    val hintLineSp: Double,
    /** Width of 点按显示词语 in [displayWidth] units. */
    val hintUnits: Double,
    /** `Density.fontScale`: one sp is this many dp. */
    val fontScale: Double,
) {
    /** Width in dp the widest child needs at [scale]. */
    fun widestChildDp(keepState: Boolean, keepHint: Boolean, scale: Double): Double = maxOf(
        iconDp,
        if (keepState) stateUnits * stateFontSp * fontScale else 0.0,
        if (keepHint) hintUnits * hintFontSp * fontScale else 0.0,
    ) * scale

    /** Height in dp of the stack with the dropped parts costing nothing. */
    fun heightDp(keepState: Boolean, keepHint: Boolean, scale: Double): Double {
        val state = if (keepState) iconGapDp + stateLineSp * fontScale + stateGapDp else 0.0
        val hint = if (keepHint) hintLineSp * fontScale else 0.0
        return (iconDp + state + hint) * scale
    }
}

/**
 * The hidden stack as one disc can actually carry it — every element either
 * drawn at its own size or not drawn at all, because the disc is no longer a
 * fixed 204 dp: a landscape stage hands the dial ~70 dp, where the full 95 dp
 * stack has `√(D²−h²) = 0` and the dial drew nothing at all (AUDIT C6, found on
 * device).
 *
 * Candidates are tried largest-first and the first that fits wins whole: the
 * 点按显示词语 hint goes before the state word (the 显示词语 button beside the
 * dial carries the same words, so dropping the hint costs nothing), and the
 * hearing icon is the last resort — it is a glyph, so it is the one part that
 * *scales* instead of being dropped, and the dial is never empty.
 *
 * Labels are never shrunk to squeeze them in: a 13 sp hint rendered at 9 sp is
 * neither readable nor honest about the layout, and the state word is what the
 * student actually needs. The 204 dp portrait disc returns the whole stack
 * unchanged, so the geometry AUDIT C2 verified is untouched.
 */
fun dialHiddenStack(
    diameterDp: Double,
    metrics: DialMetrics,
    hidden: DialHiddenMetrics,
): DialHiddenStack {
    for ((keepState, keepHint) in HIDDEN_STACK_CANDIDATES) {
        if (hiddenFits(diameterDp, metrics, hidden, keepState, keepHint, scale = 1.0)) {
            return hiddenStack(hidden, keepState, keepHint, scale = 1.0)
        }
    }
    // Icon alone, at the largest size the disc affords: the dial must never be
    // blank, and a scaled glyph reads at any size where a scaled CJK label does
    // not.
    val scale = largestHiddenScale(diameterDp, metrics, hidden, keepState = false, keepHint = false)
    return hiddenStack(hidden, keepState = false, keepHint = false, scale = scale)
}

/**
 * The stack candidates in drop order: the whole thing, then without the hint,
 * then the icon alone ([HIDDEN_STACK_CANDIDATES] covers the first two — the
 * icon-only case is the scaled fallback above).
 */
private val HIDDEN_STACK_CANDIDATES = listOf(true to true, true to false)

/** Whether [keepState]/[keepHint]'s stack fits the disc at [scale]. */
private fun hiddenFits(
    diameterDp: Double,
    metrics: DialMetrics,
    hidden: DialHiddenMetrics,
    keepState: Boolean,
    keepHint: Boolean,
    scale: Double,
): Boolean {
    val box = dialContentWidthDp(diameterDp, hidden.heightDp(keepState, keepHint, scale)) -
        metrics.marginDp
    return box > 0.0 &&
        hidden.widestChildDp(keepState, keepHint, scale) <= box + FIT_EPSILON_DP
}

/** Largest uniform scale ≤ 1 at which the icon fits its own box. */
private fun largestHiddenScale(
    diameterDp: Double,
    metrics: DialMetrics,
    hidden: DialHiddenMetrics,
    keepState: Boolean,
    keepHint: Boolean,
): Double {
    if (hiddenFits(diameterDp, metrics, hidden, keepState, keepHint, scale = 1.0)) return 1.0
    var low = 0.0
    var high = 1.0
    repeat(HIDDEN_BISECTION_STEPS) {
        val mid = (low + high) / 2.0
        if (hiddenFits(diameterDp, metrics, hidden, keepState, keepHint, mid)) low = mid else high = mid
    }
    return low
}

/** Resolved hidden stack: what the dial renders while the word is concealed. */
data class DialHiddenStack(
    val iconDp: Double,
    val iconGapDp: Double,
    /** 0 → the state word does not fit this disc and is not drawn. */
    val stateFontSp: Double,
    val stateLineSp: Double,
    val stateGapDp: Double,
    /** 0 → the 点按显示词语 hint does not fit this disc and is not drawn. */
    val hintFontSp: Double,
) {
    val showsState: Boolean get() = stateFontSp > 0.0
    val showsHint: Boolean get() = hintFontSp > 0.0
}

private fun hiddenStack(
    hidden: DialHiddenMetrics,
    keepState: Boolean,
    keepHint: Boolean,
    scale: Double,
): DialHiddenStack = DialHiddenStack(
    iconDp = hidden.iconDp * scale,
    iconGapDp = hidden.iconGapDp * scale,
    stateFontSp = if (keepState) hidden.stateFontSp * scale else 0.0,
    stateLineSp = if (keepState) hidden.stateLineSp * scale else 0.0,
    stateGapDp = if (keepState) hidden.stateGapDp * scale else 0.0,
    hintFontSp = if (keepHint) hidden.hintFontSp * scale else 0.0,
)

/** Probes the hidden stack's bisection makes (it resolves 0.6 in ~1e-6). */
private const val HIDDEN_BISECTION_STEPS = 20

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
fun dialFit(word: String?, gloss: String?, hasHint: Boolean, metrics: DialMetrics): DialFit {
    val glossSteps = if (gloss.isNullOrEmpty()) listOf(0) else metrics.glossMaxLines downTo 0
    var best: DialFit? = null
    var bestScore = 0.0
    var bestWholeWord = false
    for (wordLines in 1..metrics.wordMaxLines) {
        for (glossLines in glossSteps) {
            val fit = layoutDial(word, gloss, hasHint, wordLines, glossLines, metrics)
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
    return best ?: layoutDial(word, gloss, hasHint, 1, 0, metrics)
}

private fun layoutDial(
    word: String?,
    gloss: String?,
    hasHint: Boolean,
    wordLines: Int,
    glossLines: Int,
    m: DialMetrics,
): DialFit {
    val hintLineDp = m.hintLineHeightSp * m.fontScale
    // Only what is drawn inside the disc counts: 展开全部 lives under the dial,
    // outside the clip, so it costs the content no height (AUDIT C2 — inside
    // the disc it was what pushed the stack past the rim).
    val tailDp = (if (hasHint) m.wordHintGapDp + hintLineDp else 0.0) +
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
