package org.yangtse.hearwrite.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Locks the dial's layout arithmetic (AUDIT C2). The audit's bug was geometric:
 * the revealed content stack was taller than a 204 dp disc could carry, so the
 * word's first line touched the rim and the 展开全部 button fell outside the
 * clip and vanished — with no way to read the rest of a long word.
 *
 * The dial is now sized from its own contents, so these tests assert the two
 * properties that make that a fix rather than a smaller guess:
 * 1. the box a fit resolves to fits the disc at its own height
 *    (`w² + h² ≤ D²`, from the box's corners), and
 * 2. a word/gloss the box cannot hold is reported as truncated — the signal
 *    that puts the read-everything card on screen — instead of being silently
 *    ellipsized.
 */
class DialFitTest {

    /** The production dial: a 204 dp disc, displayMedium word, bodyMedium hints. */
    private val metrics = DialMetrics(
        diameterDp = 204.0,
        wordMaxSp = 40.0,
        wordMinSp = 22.0,
        wordLineRatio = 52.0 / 40.0,
        hintFontSizeSp = 15.0,
        hintLineHeightSp = 24.0,
        wordPosGapDp = 6.0,
        glossMaxLines = 2,
        glossGapDp = 2.0,
    )

    private fun assertInsideDisc(fit: DialFit, label: String) {
        val w = fit.contentWidthDp
        val h = fit.contentHeightDp
        // The box is centred in the disc, so its corners are the farthest
        // points: w² + h² ≤ D² is exactly "the rectangle fits the circle".
        assertTrue(
            "$label: box ${w}x$h does not fit the ${metrics.diameterDp} disc",
            sqrt(w * w + h * h) <= metrics.diameterDp + 1e-6,
        )
    }

    // --- the disc's inscribed rectangle ---

    @Test
    fun `a square box inscribes with the diagonal as its side`() {
        val side = 204.0 / sqrt(2.0)
        assertEquals(side, dialContentWidthDp(204.0, side), 1e-9)
    }

    @Test
    fun `a flat box may use the full diameter`() {
        assertEquals(204.0, dialContentWidthDp(204.0, 0.0), 1e-9)
    }

    @Test
    fun `a box taller than the disc has no width`() {
        assertEquals(0.0, dialContentWidthDp(204.0, 204.0), 1e-9)
        assertEquals(0.0, dialContentWidthDp(204.0, 300.0), 1e-9)
    }

    @Test
    fun `the fit of every shipped word shape stays inside the disc`() {
        // Real headwords from the shipped lists, including the longest ones:
        // `the Great Hall of the people` and the two-line `doing morning
        // exercises` are the audit's own examples.
        val words = listOf(
            null,
            "月",
            "apple",
            "you're",
            "Dragon Boat Festival",
            "doing morning exercises",
            "the Great Hall of the people",
            "telephone/phone number",
            "stop sb (from) doing sth",
        )
        val glosses = listOf(
            null,
            "苹果",
            "月亮，月球",
            "n. 苹果；苹果树",
            "惊奇，惊讶,(对...)感到怀疑；惊奇，惊讶(对. . . . )感到怀",
        )
        for (word in words) {
            for (gloss in glosses) {
                for (hasPos in listOf(true, false)) {
                    val fit = dialFit(word, gloss, hasPos, metrics)
                    assertInsideDisc(fit, "$word / $gloss / pos=$hasPos")
                    assertTrue("font below the floor", fit.wordFontSizeSp >= metrics.wordMinSp - 1e-9)
                    assertTrue("font above the style", fit.wordFontSizeSp <= metrics.wordMaxSp + 1e-9)
                }
            }
        }
    }

    // --- word sizing ---

    @Test
    fun `a short word keeps the style's own size`() {
        val fit = dialFit("apple", null, hasPos = false, metrics = metrics)
        assertEquals(40.0, fit.wordFontSizeSp, 1e-9)
        assertFalse(fit.wordTruncated)
    }

    @Test
    fun `a long headword shrinks to hold its lines instead of being cut`() {
        // 14 display units of Latin: at 40 sp it cannot hold a line of the box,
        // so the solver trades the font size instead of ellipsizing the tail.
        val fit = dialFit("the Great Hall of the people", null, hasPos = false, metrics = metrics)
        assertTrue("expected a shrink", fit.wordFontSizeSp < 40.0)
        assertFalse(fit.wordTruncated)
    }

    @Test
    fun `a single token too wide for one line is reported as truncated`() {
        // No whitespace to break at: a 60-unit run cannot fit one line at the
        // 22 sp floor, so the card must carry it — the dial must not pretend
        // the two-line clamp will show it.
        val fit = dialFit("a".repeat(120), null, hasPos = false, metrics = metrics)
        assertEquals(22.0, fit.wordFontSizeSp, 1e-9)
        assertTrue(fit.wordTruncated)
        assertTrue(fit.needsDetail)
    }

    @Test
    fun `a two-line name is not truncated when the box can hold it`() {
        val fit = dialFit("doing morning exercises", null, hasPos = false, metrics = metrics)
        assertFalse(fit.wordTruncated)
        assertTrue("the two-line area should be used", fit.wordLines >= 1)
    }

    @Test
    fun `a longer system font scale shrinks the word rather than overflowing`() {
        val scaled = metrics.copy(fontScale = 1.5)
        val fit = dialFit("the Great Hall of the people", null, hasPos = false, metrics = scaled)
        assertInsideDisc(fit, "fontScale 1.5")
        assertTrue(fit.wordFontSizeSp < 40.0)
    }

    // --- gloss sizing ---

    @Test
    fun `a long gloss is reported for the card rather than silently cut`() {
        val fit = dialFit(
            "surprise",
            "惊奇，惊讶,(对...)感到怀疑；惊奇，惊讶(对. . . . )感到怀",
            hasPos = true,
            metrics = metrics,
        )
        assertTrue(fit.glossTruncated)
        assertTrue(fit.needsDetail)
        // The word is what is being dictated: it stays whole.
        assertFalse(fit.wordTruncated)
        assertInsideDisc(fit, "long gloss")
    }

    @Test
    fun `a short gloss needs no card`() {
        val fit = dialFit("apple", "苹果", hasPos = true, metrics = metrics)
        assertFalse(fit.glossTruncated)
        assertFalse(fit.needsDetail)
        assertTrue(fit.glossLines > 0)
    }

    @Test
    fun `a gloss is judged by width, not character count`() {
        // 24 characters, but half of them fullwidth: the old `length > 26`
        // gate is blind to the difference (AGENTS.md: the repo measures
        // display width, fullwidth 1 / halfwidth 0.5).
        val cjk = "月亮，月球，卫星，月份，月光" // 14 units
        val latin = "a".repeat(24) // 12 units
        assertEquals(14.0, displayWidth(cjk), 1e-9)
        assertEquals(12.0, displayWidth(latin), 1e-9)
        assertTrue(displayWidth(cjk) > displayWidth(latin))
    }

    @Test
    fun `a word with no gloss leaves the height to the word`() {
        val withGloss = dialFit("apple", "苹果，苹果树，苹果汁的一种", hasPos = true, metrics = metrics)
        val without = dialFit("apple", null, hasPos = true, metrics = metrics)
        assertTrue(without.contentHeightDp < withGloss.contentHeightDp)
    }

    // --- greedy line breaking (the wrapping the renderer performs) ---

    @Test
    fun `wrapping counts the lines the renderer would use`() {
        // "apple" is 2.5 units: one 3.0-wide line holds it, a 2.0-wide one
        // cannot (a token wider than the line overflows rather than wrapping).
        assertEquals(1, wrappedLineCount("apple", 3.0))
        assertEquals(Int.MAX_VALUE, wrappedLineCount("apple", 2.0))
        assertEquals(0, wrappedLineCount(null, 3.0))
        assertEquals(0, wrappedLineCount("  ", 3.0))
    }

    @Test
    fun `a space between tokens costs half a unit`() {
        // "ab cd" = 1.0 + 0.5 + 1.0 = 2.5 units on one line, not 2.0.
        assertEquals(1, wrappedLineCount("ab cd", 2.5))
        assertEquals(2, wrappedLineCount("ab cd", 2.4))
    }

    @Test
    fun `three tokens fit two lines by total width but wrap to three`() {
        // The counter-example that retired the closed-form bound: 0.6 + 0.6 +
        // 0.6 = 1.8 units fits two 1.0-wide lines by sum, but no wrapping of
        // 0.7-unit tokens leaves two lines holding them. A total-width bound
        // would call this shown while the renderer ellipsizes it.
        assertEquals(3, wrappedLineCount("abc abc abc", 1.5))
    }

    @Test
    fun `cjk glyphs break between themselves`() {
        // Six 1.0-unit glyphs on a 3.0-wide line: two lines.
        assertEquals(2, wrappedLineCount("月亮月球卫星", 3.0))
        assertEquals(3, wrappedLineCount("月亮月球卫星", 2.0))
    }

    @Test
    fun `a glued latin string is one unbreakable token`() {
        // 120 halfwidth chars = 60 units: it fits a 60-unit line exactly and
        // overflows anything narrower, with nowhere to break.
        assertEquals(1, wrappedLineCount("a".repeat(120), 60.0))
        assertEquals(Int.MAX_VALUE, wrappedLineCount("a".repeat(120), 50.0))
    }
}
