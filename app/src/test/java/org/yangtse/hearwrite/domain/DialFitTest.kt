package org.yangtse.hearwrite.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yangtse.hearwrite.data.LexiconRepository
import java.io.File
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
 * 2. a word/gloss/hint the box cannot hold is reported as truncated — the
 *    signal that puts the read-everything card on screen — instead of being
 *    silently ellipsized.
 *
 * (2) is then closed over the real data: `no shipped row shows a hint the
 * resolved box cannot hold` sweeps every English row of the shipped library.
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
        wordHintGapDp = 6.0,
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
        // The hint line in the three shapes the dial meets: absent, a 拼音, and
        // the composed `/音标/ 词性` an English row prints.
        val hints = listOf(null, "yuè", "/ˈæpəl/ n.")
        for (word in words) {
            for (gloss in glosses) {
                for (hint in hints) {
                    val fit = dialFit(word, gloss, hint, metrics)
                    assertInsideDisc(fit, "$word / $gloss / hint=$hint")
                    assertTrue("font below the floor", fit.wordFontSizeSp >= metrics.wordMinSp - 1e-9)
                    assertTrue("font above the style", fit.wordFontSizeSp <= metrics.wordMaxSp + 1e-9)
                }
            }
        }
    }

    // --- word sizing ---

    @Test
    fun `a short word keeps the style's own size`() {
        val fit = dialFit("apple", null, hint = null, metrics = metrics)
        assertEquals(40.0, fit.wordFontSizeSp, 1e-9)
        assertFalse(fit.wordTruncated)
    }

    @Test
    fun `a long headword shrinks to hold its lines instead of being cut`() {
        // 14 display units of Latin: at 40 sp it cannot hold a line of the box,
        // so the solver trades the font size instead of ellipsizing the tail.
        val fit = dialFit("the Great Hall of the people", null, hint = null, metrics = metrics)
        assertTrue("expected a shrink", fit.wordFontSizeSp < 40.0)
        assertFalse(fit.wordTruncated)
    }

    @Test
    fun `a single token too wide for one line is reported as truncated`() {
        // No whitespace to break at: a 60-unit run cannot fit one line at the
        // 22 sp floor, so the card must carry it — the dial must not pretend
        // the two-line clamp will show it.
        val fit = dialFit("a".repeat(120), null, hint = null, metrics = metrics)
        assertEquals(22.0, fit.wordFontSizeSp, 1e-9)
        assertTrue(fit.wordTruncated)
        assertTrue(fit.needsDetail)
    }

    @Test
    fun `a two-line name is not truncated when the box can hold it`() {
        val fit = dialFit("doing morning exercises", null, hint = null, metrics = metrics)
        assertFalse(fit.wordTruncated)
        assertTrue("the two-line area should be used", fit.wordLines >= 1)
    }

    @Test
    fun `a longer system font scale shrinks the word rather than overflowing`() {
        val scaled = metrics.copy(fontScale = 1.5)
        val fit = dialFit("the Great Hall of the people", null, hint = null, metrics = scaled)
        assertInsideDisc(fit, "fontScale 1.5")
        assertTrue(fit.wordFontSizeSp < 40.0)
    }

    // --- gloss sizing ---

    @Test
    fun `a long gloss is reported for the card rather than silently cut`() {
        val fit = dialFit(
            "surprise",
            "惊奇，惊讶,(对...)感到怀疑；惊奇，惊讶(对. . . . )感到怀",
            hint = "/ˈæpəl/ n.",
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
        val fit = dialFit("apple", "苹果", hint = "/ˈæpəl/ n.", metrics = metrics)
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
        val withGloss = dialFit("apple", "苹果，苹果树，苹果汁的一种", hint = "/ˈæpəl/ n.", metrics = metrics)
        val without = dialFit("apple", null, hint = "/ˈæpəl/ n.", metrics = metrics)
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

    // --- the hint line (§4.3) ---

    @Test
    fun `a hint wider than the box asks for the card`() {
        // The review's worst real row, verbatim: 初中2182/第二册 常见.txt:104,
        // `/məʊst/ adj. & adv. & pron.` = 13.5 display units. The word and the
        // gloss are short enough that this layout shows both whole, so before
        // the hint was measured `needsDetail` stayed false and the 词性 was
        // eaten with no 展开全部 entry to read it from.
        val fit = dialFit("most", "最多的", "/məʊst/ adj. & adv. & pron.", metrics)
        assertTrue(fit.hintTruncated)
        assertTrue(fit.needsDetail)
        assertEquals(13.5, displayWidth("/məʊst/ adj. & adv. & pron."), 1e-9)
        // The invariant, stated as arithmetic: the hint is wider than the box
        // the solver resolved, which is exactly what the renderer ellipsizes.
        val unitsPerHintLine = fit.contentWidthDp / (metrics.hintFontSizeSp * metrics.fontScale)
        assertTrue("hint must exceed the box", 13.5 > unitsPerHintLine)
        assertInsideDisc(fit, "wide hint")
    }

    @Test
    fun `a hint that fits needs no card`() {
        // `/ˈæpəl/ n.` = 5.0 units against a box that holds ~11 — the ordinary
        // English row, which must not grow a 展开全部 entry just because the
        // hint is now measured.
        val fit = dialFit("apple", "苹果", "/ˈæpəl/ n.", metrics)
        assertFalse(fit.hintTruncated)
        assertFalse(fit.needsDetail)
    }

    @Test
    fun `an absent hint composes exactly as it did before`() {
        // A null hint and an empty one are both "no hint line drawn": same
        // height, same box, same flags — the pre-change `hasHint = false` case.
        val withoutHint = dialFit("apple", "苹果", null, metrics)
        val emptyHint = dialFit("apple", "苹果", "", metrics)
        assertEquals(withoutHint, emptyHint)
        assertFalse(withoutHint.hintTruncated)
        assertFalse(withoutHint.needsDetail)
        // And the line it would have cost is really in the stack: same word,
        // same gloss, the hint's own gap plus its line box taller.
        val withHint = dialFit("apple", "苹果", "/ˈæpəl/ n.", metrics)
        assertEquals(
            metrics.wordHintGapDp + metrics.hintLineHeightSp,
            withHint.contentHeightDp - withoutHint.contentHeightDp,
            1e-9,
        )
    }

    // --- the invariant, over the shipped library ---

    /**
     * The class of bug §4.3 belongs to, closed over the real data: whatever the
     * solver resolves, no row may end up showing a hint that does not fit the
     * box it resolved to without [DialFit.needsDetail] set.
     *
     * The review counted 125 shipped English rows whose hint exceeds the
     * 9.83-unit box implied by the one-word-line geometry. This sweep asserts
     * against the **resolved** box instead, which is strictly stronger: a
     * two-line word resolves a narrower box, so its tolerance is smaller. Run
     * against the pre-change solver (a presence boolean in place of the text)
     * it fails on 56 rows.
     *
     * Reads the shipped lexicon once through the same asset seam the app uses
     * (`LexiconRepositoryTest`'s pattern) and every English list once, resolving
     * each row exactly as the screen does. ~0.9 s.
     */
    @Test
    fun `no shipped row shows a hint the resolved box cannot hold`() = runTest {
        val repo = LexiconRepository { path -> File("src/main/assets/$path").inputStream() }
        val lists = File("src/main/assets").listFiles().orEmpty()
            .filter { it.isDirectory && it.name !in NON_LIBRARY_DIRS }
            .flatMap { dir -> dir.listFiles().orEmpty().filter { it.extension == "txt" }.toList() }
        // A guard against the vacuous pass, not a pin on the library's size: a
        // sweep that read nothing would otherwise report zero offenders. The
        // floors are well under the shipped 651 lists / 15,300 English rows.
        assertTrue("the sweep read no library lists", lists.size > 500)

        var englishRows = 0
        var overflowing = 0
        var worst: String? = null
        for (list in lists) {
            for (line in list.readLines()) {
                if (line.isBlank()) continue
                val row = parseWordLine(line)
                if (row.kind != WordKind.EN) continue
                val resolved = repo.resolve(row)
                englishRows++
                val hint = resolved.dialHint() ?: continue
                val fit = dialFit(resolved.display, resolved.glossText(), hint, metrics)
                val unitsPerHintLine = fit.contentWidthDp / (metrics.hintFontSizeSp * metrics.fontScale)
                if (displayWidth(hint) > unitsPerHintLine && !fit.needsDetail) {
                    overflowing++
                    if (worst == null) {
                        worst = "${list.parentFile?.name}/${list.name}: $hint " +
                            "(${displayWidth(hint)} u > $unitsPerHintLine)"
                    }
                }
            }
        }
        assertTrue("the sweep resolved no English rows", englishRows > 10_000)
        assertEquals(
            "rows whose hint overflows the resolved box but ask for no card (e.g. $worst)",
            0,
            overflowing,
        )
    }

    private companion object {
        /**
         * Asset directories that are not browsable word lists: the lexicon and
         * compound tables, the sounds, and the in-app GPL text
         * (`BuiltinLibraryRepository`'s own non-library set).
         */
        val NON_LIBRARY_DIRS = setOf("dict", "compounds", "audio", "licenses")
    }
}
