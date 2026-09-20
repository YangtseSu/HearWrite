package org.yangtse.hearwrite.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dictation stage's arrangement, pinned against the window sizes the app
 * meets (AUDIT C6). The regression these lock down is a landscape phone: the
 * stage box is ~103 dp tall there while the readouts alone stack to ~150 dp, so
 * a fixed 248 dp ring both overflowed the box and buried the reveal / 标记错词
 * buttons off screen with no way to reach them.
 */
class DialStageTest {

    /** The readouts' height stacked: 展开全部 row + seconds line + gap + action. */
    private val stackedReadouts = 40.0 + 42.0 + 16.0 + 52.0

    /** The readouts' height beside the ring: the tallest control only. */
    private val besideReadouts = 52.0

    /** The phone dial, and the value every window without room to grow uses. */
    private val phone = 248.0

    /**
     * The beside-row width budget the screen hands the solver, at font_scale 1:
     * the 16 dp beside gap, the 展开全部 slot (≈80 dp: the 14 sp label plus its
     * 12 dp content padding on each side), the 12 dp row gap, the seconds slot
     * (≈85 dp: `10 秒` at displaySmall's 34 sp), 12 dp again, and the actions
     * block pinned to its 420 dp maximum — ≈625 dp in all. The screen *measures*
     * the two text slots with a `TextMeasurer`; a JVM test cannot, so it uses
     * those slot figures, which is what makes 923 dp fit and 600 dp not.
     */
    private val besideRowWidth = 16.0 + 80.0 + 12.0 + 85.0 + 12.0 + 420.0

    /** The stage box a landscape phone actually leaves: 2424×1080 px at 420 dpi. */
    private val landscapeHeight = 103.0

    private fun solve(
        width: Double,
        height: Double,
        stacked: Double = stackedReadouts,
        beside: Double = besideReadouts,
        twoColumns: Boolean,
        preferred: Double = phone,
    ) = dialStageGeometry(
        availableWidthDp = width,
        availableHeightDp = height,
        stackedReadoutsHeightDp = stacked,
        besideReadoutsHeightDp = beside,
        readoutsWidthDp = besideRowWidth,
        twoColumnsAllowed = twoColumns,
        preferredRingDp = preferred,
    )

    @Test
    fun `tall phone window keeps the stacked dial at its preferred size`() {
        val geometry = solve(width = 411.0, height = 500.0, twoColumns = false)
        assertEquals(DialStageLayout.STACKED, geometry.layout)
        assertEquals(248.0, geometry.ringDp, 1e-6)
        // 248 − 44: the disc the DIAL_* metrics are solved against.
        assertEquals(204.0, geometry.discDp, 1e-6)
        assertFalse(geometry.scrolls)
    }

    @Test
    fun `landscape phone moves the readouts beside the ring`() {
        val geometry = solve(width = 923.0, height = landscapeHeight, twoColumns = true)
        assertEquals(DialStageLayout.BESIDE, geometry.layout)
        // The readout row is 52 dp, so a 103 dp box carries a 103 dp ring next
        // to it — no scroll, and no control pushed off screen.
        assertEquals(103.0, geometry.ringDp, 1e-6)
        assertEquals(103.0 - 103.0 * 0.18, geometry.discDp, 1e-6)
        assertFalse(geometry.scrolls)
    }

    @Test
    fun `the beside arrangement never exceeds the box height`() {
        // The invariant the landscape fix rests on: whatever ring comes out,
        // the ring and the readout row share the height rather than adding up.
        for (height in listOf(60.0, 103.0, 150.0, 240.0, 400.0)) {
            val geometry = solve(width = 923.0, height = height, twoColumns = true)
            if (geometry.layout == DialStageLayout.BESIDE && !geometry.scrolls) {
                assertTrue(
                    "ring ${geometry.ringDp} must not exceed the box $height",
                    geometry.ringDp <= height + 1e-6,
                )
            }
        }
    }

    @Test
    fun `a portrait phone shrinks the stacked dial instead of splitting`() {
        // Same short stage but a window too narrow for two columns: the honest
        // answer is a smaller dial, not a lopsided split with a squeezed disc.
        val geometry = solve(width = 411.0, height = 360.0, twoColumns = false)
        assertEquals(DialStageLayout.STACKED, geometry.layout)
        assertEquals(360.0 - stackedReadouts, geometry.ringDp, 1e-6)
        assertTrue(geometry.ringDp < phone)
        assertFalse(geometry.scrolls)
    }

    @Test
    fun `the real landscape stage fits beside without scrolling`() {
        // 2424x1080 px at 420 dpi leaves ~86 dp for the stage: the ring takes all
        // of it (disc ≈ 70 dp) and the 52 dp readout row sits beside, so the
        // stage is NOT a scroll container and every control is on screen.
        val geometry = solve(width = 923.0, height = 86.0, twoColumns = true)
        assertEquals(DialStageLayout.BESIDE, geometry.layout)
        assertEquals(86.0, geometry.ringDp, 1e-6)
        assertTrue(geometry.discDp >= DialStageMetrics().minDiscDp)
        assertFalse(geometry.scrolls)
    }

    @Test
    fun `shrinking stops at the disc floor and the stage scrolls instead`() {
        // Below the floor (64 dp disc, i.e. a ~78 dp ring) the stage stops
        // shrinking and reports that it must scroll, rather than drawing a disc
        // with no glyph in it.
        val clamped = solve(width = 411.0, height = 60.0, twoColumns = false)
        assertEquals(DialStageMetrics().minRingDp, clamped.ringDp, 1e-6)
        assertEquals(DialStageMetrics().minDiscDp, clamped.discDp, 1e-6)
        assertTrue(clamped.scrolls)
    }

    @Test
    fun `an expanded window gets a larger dial and still stacks`() {
        val geometry = solve(
            width = 1280.0,
            height = 820.0,
            twoColumns = true,
            preferred = 320.0,
        )
        assertEquals(DialStageLayout.STACKED, geometry.layout)
        assertEquals(320.0, geometry.ringDp, 1e-6)
        assertEquals(276.0, geometry.discDp, 1e-6)
        assertFalse(geometry.scrolls)
    }

    @Test
    fun `a bigger font scale re-arranges where the same box used to stack`() {
        // font_scale 1.5 grows the readouts — the countdown line box above all —
        // which is what pushes a short window from stack to beside.
        val base = solve(width = 923.0, height = 420.0, twoColumns = true)
        val scaled = solve(
            width = 923.0,
            height = 420.0,
            stacked = stackedReadouts * 1.5,
            beside = besideReadouts * 1.5,
            twoColumns = true,
        )
        assertEquals(DialStageLayout.STACKED, base.layout)
        assertEquals(DialStageLayout.BESIDE, scaled.layout)
    }

    @Test
    fun `a narrow window never splits even when it cannot stack`() {
        // The two-column branch needs the width for a ring plus the readout row;
        // a phone-width window that cannot stack simply shrinks.
        val geometry = solve(width = 371.0, height = 390.0, twoColumns = true)
        assertEquals(DialStageLayout.STACKED, geometry.layout)
        assertEquals(390.0 - stackedReadouts, geometry.ringDp, 1e-6)
    }

    @Test
    fun `a bigger disc keeps a proportionally smaller ring inset`() {
        // The inset is the ring's stroke: 44 dp on the 248/320 dp dials, but a
        // share of the ring once it shrinks, so a short stage does not spend the
        // whole dial on its own stroke.
        val metrics = DialStageMetrics()
        assertEquals(44.0, metrics.ringInsetDp, 1e-6)
        assertEquals(248.0 - 44.0, metrics.discDp(248.0), 1e-6)
        assertEquals(103.0 * (1 - metrics.insetRatio), metrics.discDp(103.0), 1e-6)
        // The disc stays strictly inside its ring at every size.
        for (ring in listOf(80.0, 103.0, 200.0, 248.0, 320.0)) {
            assertTrue(metrics.discDp(ring) < ring)
        }
    }

    // --- the beside width budget (review §5.1) ---

    @Test
    fun `a wide short window splits only when the real row fits`() {
        // The stage only makes BESIDE work when ring + the *whole* beside row
        // fits sideways. The row is 16 + the 展开全部 slot + 12 + the seconds
        // slot + 12 + the 420 dp actions block (≈625 dp at font_scale 1), and a
        // 600 dp window is narrower than that plus any ring: the honest answer
        // is the shrunk stacked arrangement, not a split that squeezes the two
        // actions into a fixed 52 dp height.
        val geometry = solve(width = 600.0, height = 300.0, twoColumns = true)
        assertEquals(DialStageLayout.STACKED, geometry.layout)
        assertEquals(300.0 - stackedReadouts, geometry.ringDp, 1e-6)
        assertFalse(geometry.scrolls)
        // The budget really is what it claims: ring + row does not fit 600 dp.
        assertTrue(besideRowWidth + geometry.ringDp > 600.0)
    }

    @Test
    fun `the real landscape phone still splits and fits`() {
        // The device case AUDIT C6 was verified on: 923 dp wide is wider than
        // the ring (103 dp) plus the real row, so BESIDE still wins there and
        // still needs no scrolling.
        val geometry = solve(width = 923.0, height = landscapeHeight, twoColumns = true)
        assertEquals(DialStageLayout.BESIDE, geometry.layout)
        assertEquals(103.0, geometry.ringDp, 1e-6)
        assertFalse(geometry.scrolls)
        assertTrue(besideRowWidth + geometry.ringDp <= 923.0 + 1e-6)
    }
}
