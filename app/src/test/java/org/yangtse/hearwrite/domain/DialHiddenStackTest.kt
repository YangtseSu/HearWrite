package org.yangtse.hearwrite.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hidden dial's stack (hearing icon + 听写中/已暂停 + 点按显示词语) against the
 * disc it is handed (AUDIT C6). Found on device: the stage's beside arrangement
 * gives the dial ~70 dp on a landscape phone, where the old fixed stack
 * (`√(D²−h²)` over a 95 dp column) evaluated to width 0 and the dial rendered
 * nothing at all — not even the hearing icon.
 */
class DialHiddenStackTest {

    private val metrics = DialMetrics(
        diameterDp = 0.0,
        wordMaxSp = 40.0,
        wordMinSp = 22.0,
        wordLineRatio = 52.0 / 40.0,
        hintFontSizeSp = 15.0,
        hintLineHeightSp = 24.0,
        wordPosGapDp = 6.0,
        glossMaxLines = 2,
        glossGapDp = 2.0,
    )

    /** `titleMedium` (16/24) and `labelMedium` (13/19) at font scale 1. */
    private val hidden = DialHiddenMetrics(
        iconDp = 40.0,
        iconGapDp = 8.0,
        stateGapDp = 4.0,
        stateFontSp = 16.0,
        stateLineSp = 24.0,
        stateUnits = 3.0,
        hintFontSp = 13.0,
        hintLineSp = 19.0,
        hintUnits = 6.0,
        fontScale = 1.0,
    )

    private fun solve(disc: Double) = dialHiddenStack(
        diameterDp = disc,
        metrics = metrics.copy(diameterDp = disc),
        hidden = hidden,
    )

    @Test
    fun `the portrait disc keeps the whole stack at its own size`() {
        val stack = solve(204.0)
        assertTrue(stack.showsState)
        assertTrue(stack.showsHint)
        assertEquals(40.0, stack.iconDp, 1e-6)
        assertEquals(16.0, stack.stateFontSp, 1e-6)
        assertEquals(13.0, stack.hintFontSp, 1e-6)
    }

    @Test
    fun `a landscape disc still renders the icon`() {
        // 70 dp is what the beside arrangement leaves the dial on a landscape
        // phone: the regression was a completely empty disc here.
        val stack = solve(70.0)
        assertTrue(stack.iconDp > 0.0)
        assertTrue(stack.iconDp <= 40.0)
    }

    @Test
    fun `elements are dropped whole rather than clipped`() {
        // Whatever the disc, every part that is drawn is drawn at a real size —
        // a text is either absent or legible, never a sliver.
        for (disc in listOf(60.0, 70.0, 90.0, 120.0, 160.0, 204.0)) {
            val stack = solve(disc)
            assertTrue("disc $disc loses the icon", stack.iconDp > 0.0)
            // A label that is drawn is drawn at its own size: labels are never
            // squeezed, only dropped (the icon alone is what scales).
            if (stack.showsState) assertEquals(16.0, stack.stateFontSp, 1e-6)
            if (stack.showsHint) assertEquals(13.0, stack.hintFontSp, 1e-6)
            // The hint is the first thing to go: it duplicates the 显示词语
            // button that sits beside the dial, so dropping it costs nothing.
            if (!stack.showsState) assertFalse(stack.showsHint)
        }
    }

    /** The stack's own height, reconstructed the way the solver measures it. */
    private fun stackHeight(stack: DialHiddenStack, fontScale: Double = 1.0): Double =
        stack.iconDp +
            (if (stack.showsState) stack.iconGapDp + stack.stateLineSp + stack.stateGapDp else 0.0) +
            (if (stack.showsHint) hidden.hintLineSp * fontScale * (stack.hintFontSp / hidden.hintFontSp) else 0.0)

    @Test
    fun `the stack that is drawn fits its own box`() {
        // The invariant the solver exists for: what it returns is inside the
        // disc at that stack's own height, for every disc the stage can hand it.
        for (disc in listOf(60.0, 70.0, 90.0, 120.0, 140.0, 160.0, 204.0, 276.0)) {
            val stack = solve(disc)
            val box = dialContentWidthDp(disc, stackHeight(stack)) - metrics.marginDp
            val widest = maxOf(
                stack.iconDp,
                if (stack.showsState) hidden.stateUnits * stack.stateFontSp else 0.0,
                if (stack.showsHint) hidden.hintUnits * stack.hintFontSp else 0.0,
            )
            assertTrue(
                "disc $disc: $widest wide must fit the ${"%.1f".format(box)} box",
                widest <= box + 1e-6,
            )
        }
    }

    @Test
    fun `a bigger font scale drops elements on a disc that used to keep them`() {
        // At 140 dp the whole stack fits at scale 1; at font_scale 1.5 the labels
        // are half again as wide and the column taller, which no longer fits —
        // so the hint is dropped rather than clipped.
        val scaled = hidden.copy(fontScale = 1.5)
        val atOne = dialHiddenStack(140.0, metrics.copy(diameterDp = 140.0), hidden)
        val atScale = dialHiddenStack(140.0, metrics.copy(diameterDp = 140.0), scaled)
        assertTrue(atOne.showsHint)
        assertFalse(atScale.showsHint)
        // Dropping the hint costs the *hint*, never the state word.
        assertTrue(atScale.showsState)
        // And what it does draw is still inside the disc at the scaled metrics.
        val box = dialContentWidthDp(140.0, stackHeight(atScale, 1.5)) - metrics.marginDp
        val widest = maxOf(
            atScale.iconDp,
            hidden.stateUnits * atScale.stateFontSp * 1.5,
        )
        assertTrue(widest <= box + 1e-6)
    }
}
