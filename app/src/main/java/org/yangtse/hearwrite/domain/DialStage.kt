package org.yangtse.hearwrite.domain

/**
 * Geometry of the dictation stage — the dial and the readouts that surround it.
 *
 * The stage is a `weight(1f)` box between the app bar and the playback panel,
 * and its dial used to be a fixed 248 dp ring inside a box that could not
 * scroll. On a landscape phone that box is ~103 dp tall — and the readouts
 * alone (展开全部, the seconds, two 52 dp actions) stack to ~150 dp — so the
 * dial both overflowed the box and buried the reveal / 标记错词 buttons off
 * screen (AUDIT C6, measured on device).
 *
 * The answer is to stop assuming one arrangement. The stage asks here, from its
 * measured box, for:
 *
 * - **STACKED** — ring with the readouts under it: the phone-portrait layout,
 *   unchanged, at the preferred dial size whenever the box can carry it.
 * - **BESIDE** — ring with the readouts *next to* it, laid out as a row so that
 *   arrangement is only as tall as its tallest control: the short-and-wide
 *   windows (landscape phone, unfolded foldable) where nothing can stack.
 * - **Shrinking** — the dial gives up size down to [DialStageMetrics.minDiscDp]
 *   before the stage admits it must scroll. Below that disc floor the 展开全部
 *   card is the only place a word can live, so scrolling beats cropping.
 *
 * Two columns are gated on the **window** ([twoColumnsAllowed], from
 * `WindowSizeClass`), never on the stage box: the box is ~411 dp wide on a
 * portrait phone, which is more than a ring plus a readout column need, so a
 * locally derived threshold would split a phone in half.
 *
 * All of it is pure arithmetic on the constraints, so the rendered stage is
 * reproducible in a unit test instead of by eye.
 */
enum class DialStageLayout {
    /** Ring with the readouts under it — the phone-portrait arrangement. */
    STACKED,

    /** Ring with the readouts beside it, as a row — short-and-wide windows. */
    BESIDE,
}

/**
 * Fixed dimensions the stage is built from. Kept as data so the composable that
 * renders them and the arithmetic that solves them cannot drift apart.
 */
data class DialStageMetrics(
    /**
     * Smallest disc worth drawing, measured on the real target: a landscape
     * phone leaves ~86 dp of stage, and the beside arrangement must tile that
     * between the ring and the readout row without giving up the dial. Below
     * this the disc no longer holds a dictated glyph, so the stage stops
     * shrinking and scrolls instead — a word the disc cannot hold already has
     * its escape hatch (the 展开全部 card), but a disc with no letter in it has
     * stopped being a dial at all.
     */
    val minDiscDp: Double = 64.0,
    /**
     * dp between the ring's outer edge and the disc it clips its content to at
     * the preferred size — the CountdownRing's stroke plus its breathing room.
     * [insetRatio] takes over as the ring shrinks, so a short stage does not
     * spend its whole dial on the ring's own stroke.
     */
    val ringInsetDp: Double = 44.0,
    /** The inset's share of the ring once the ring is smaller than `ringInsetDp / insetRatio`. */
    val insetRatio: Double = 0.18,
) {
    /** Disc the ring of [ringDp] clips its content to. */
    fun discDp(ringDp: Double): Double = ringDp - minOf(ringInsetDp, ringDp * insetRatio)

    /**
     * Smallest ring that still holds [minDiscDp] — the point at which the stage
     * stops shrinking and starts scrolling.
     */
    val minRingDp: Double get() = minDiscDp / (1.0 - insetRatio)
}

/**
 * Resolved stage: which arrangement to draw and how big the dial is. Applied
 * verbatim by the screen, so "the dial fits the stage" is a property of the
 * arithmetic rather than an estimate to be re-checked by eye.
 */
data class DialStageGeometry(
    val layout: DialStageLayout,
    /** Outer ring diameter. */
    val ringDp: Double,
    /** Disc the ring clips its content to — what [DialMetrics.diameterDp] gets. */
    val discDp: Double,
    /**
     * The chosen arrangement does not fit the measured box even at the ring's
     * floor: the stage must scroll, or the dial would be cropped (AUDIT C6).
     */
    val scrolls: Boolean,
)

/**
 * Solve the stage for one measured box.
 *
 * The three readout numbers are supplied by the caller rather than assumed
 * here, because only the screen knows how tall its own controls are: at
 * font_scale 1.5 the countdown line box and the actions grow, and a hard-coded
 * dp figure would silently overflow. [stackedReadoutsHeightDp] is the readouts'
 * total height in the stacked arrangement (a column) and
 * [besideReadoutsHeightDp] their height beside the ring (a row) — the whole
 * reason the landscape stage fits is that the row is far shorter than the
 * column.
 *
 * [readoutsWidthDp] is the same row's **width**, and it is the full width the
 * beside arrangement consumes next to the ring: the gap between the two, the
 * 展开全部 slot, the seconds slot and the actions block, with the gaps that
 * separate them. It is measured from those controls rather than estimated, so
 * the BESIDE verdict and the rendered row cannot disagree — a budget that
 * omitted the gaps or the fixed-width actions would admit a split whose row
 * then does not fit the window.
 */
fun dialStageGeometry(
    availableWidthDp: Double,
    availableHeightDp: Double,
    stackedReadoutsHeightDp: Double,
    besideReadoutsHeightDp: Double,
    readoutsWidthDp: Double,
    twoColumnsAllowed: Boolean,
    preferredRingDp: Double,
    metrics: DialStageMetrics = DialStageMetrics(),
): DialStageGeometry {
    // 1. The phone-portrait answer, and the only one that keeps the dial at its
    // preferred size without scrolling.
    if (availableHeightDp >= preferredRingDp + stackedReadoutsHeightDp) {
        return stage(
            DialStageLayout.STACKED,
            preferredRingDp,
            stackedReadoutsHeightDp,
            metrics,
            availableHeightDp,
        )
    }
    // 2. Nothing can stack, so try beside: it needs the width for the ring it
    // would actually draw plus the readout row, but only the height of the
    // taller of the two because they sit next to each other. The ring is
    // resolved before the width test: checking the *minimum* ring would admit a
    // layout whose real ring (up to the preferred size) then overflows the
    // window sideways.
    val besideRing = availableHeightDp.coerceIn(metrics.minRingDp, preferredRingDp)
    val besideFits = twoColumnsAllowed &&
        availableWidthDp >= besideRing + readoutsWidthDp &&
        availableHeightDp >= besideReadoutsHeightDp
    if (besideFits) {
        return stage(
            DialStageLayout.BESIDE,
            besideRing,
            besideReadoutsHeightDp,
            metrics,
            availableHeightDp,
        )
    }
    // 3. Shrink the stacked arrangement, and say so when even the floor does not
    // fit — the screen scrolls rather than cropping the dial.
    return stage(
        DialStageLayout.STACKED,
        availableHeightDp - stackedReadoutsHeightDp,
        stackedReadoutsHeightDp,
        metrics,
        availableHeightDp,
    )
}

/**
 * One arrangement at a candidate ring size: floored at [DialStageMetrics.minRingDp]
 * (below it the disc carries no word) and [DialStageGeometry.scrolls] reports
 * when that arrangement's own content no longer fits the box.
 */
private fun stage(
    layout: DialStageLayout,
    candidateRingDp: Double,
    readoutsHeightDp: Double,
    metrics: DialStageMetrics,
    availableHeightDp: Double,
): DialStageGeometry {
    val ring = candidateRingDp.coerceAtLeast(metrics.minRingDp)
    val required = when (layout) {
        DialStageLayout.STACKED -> ring + readoutsHeightDp
        DialStageLayout.BESIDE -> maxOf(ring, readoutsHeightDp)
    }
    return DialStageGeometry(
        layout = layout,
        ringDp = ring,
        discDp = metrics.discDp(ring),
        scrolls = required > availableHeightDp + FIT_EPSILON_DP,
    )
}

/** Floating-point slack when asking whether the stage still fits. */
private const val FIT_EPSILON_DP = 1e-6
