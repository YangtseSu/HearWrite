package org.yangtse.hearwrite.ui

import org.yangtse.hearwrite.data.NormalizedRect
import kotlin.math.abs

/**
 * Selection geometry of the 选定识别区域 editor: which part of the selection a
 * drag grabbed, and how a normalized drag delta moves it. Pure and
 * Android-free — a wrong mapping drags the wrong handle, and the clamp
 * detection below is what turns "the drag did nothing" into feedback, so both
 * are kept out of the composable and unit-tested.
 */

/** Which part of the selection a drag started on. */
internal enum class DragMode { NONE, MOVE, TL, TR, BL, BR, LEFT, RIGHT, TOP, BOTTOM }

/**
 * Apply a drag delta (normalized to the image) to [r]: move translates and
 * clamps to the image; corners/edges resize with the opposite side fixed.
 * Sides never cross: clamps keep every side ≥ [minX]/[minY] apart and inside
 * the [0,1] image space.
 */
internal fun resizeRect(
    r: NormalizedRect,
    mode: DragMode,
    dx: Float,
    dy: Float,
    minX: Float,
    minY: Float,
): NormalizedRect = when (mode) {
    DragMode.NONE -> r
    DragMode.MOVE -> {
        var l = r.left + dx
        var rt = r.right + dx
        if (l < 0f) {
            rt -= l
            l = 0f
        } else if (rt > 1f) {
            l -= rt - 1f
            rt = 1f
        }
        var t = r.top + dy
        var b = r.bottom + dy
        if (t < 0f) {
            b -= t
            t = 0f
        } else if (b > 1f) {
            t -= b - 1f
            b = 1f
        }
        NormalizedRect(l, t, rt, b)
    }
    DragMode.TL -> NormalizedRect(
        (r.left + dx).coerceIn(0f, r.right - minX),
        (r.top + dy).coerceIn(0f, r.bottom - minY),
        r.right,
        r.bottom,
    )
    DragMode.TR -> NormalizedRect(
        r.left,
        (r.top + dy).coerceIn(0f, r.bottom - minY),
        (r.right + dx).coerceIn(r.left + minX, 1f),
        r.bottom,
    )
    DragMode.BL -> NormalizedRect(
        (r.left + dx).coerceIn(0f, r.right - minX),
        r.top,
        r.right,
        (r.bottom + dy).coerceIn(r.top + minY, 1f),
    )
    DragMode.BR -> NormalizedRect(
        r.left,
        r.top,
        (r.right + dx).coerceIn(r.left + minX, 1f),
        (r.bottom + dy).coerceIn(r.top + minY, 1f),
    )
    DragMode.LEFT -> NormalizedRect(
        (r.left + dx).coerceIn(0f, r.right - minX),
        r.top,
        r.right,
        r.bottom,
    )
    DragMode.RIGHT -> NormalizedRect(
        r.left,
        r.top,
        (r.right + dx).coerceIn(r.left + minX, 1f),
        r.bottom,
    )
    DragMode.TOP -> NormalizedRect(
        r.left,
        (r.top + dy).coerceIn(0f, r.bottom - minY),
        r.right,
        r.bottom,
    )
    DragMode.BOTTOM -> NormalizedRect(
        r.left,
        r.top,
        r.right,
        (r.bottom + dy).coerceIn(r.top + minY, 1f),
    )
}

/** Translate [r] by a normalized delta, clamped to the image (size preserved). */
internal fun moveRectBy(
    r: NormalizedRect,
    dx: Float,
    dy: Float,
    minX: Float,
    minY: Float,
): NormalizedRect = resizeRect(r, DragMode.MOVE, dx, dy, minX, minY)

/**
 * Grow [r] outward on every side by normalized [dx]/[dy], clamped inside the
 * image; the per-side clamps keep each side ≥ [minX]/[minY] apart.
 */
internal fun growRect(
    r: NormalizedRect,
    dx: Float,
    dy: Float,
    minX: Float,
    minY: Float,
): NormalizedRect {
    var out = resizeRect(r, DragMode.LEFT, -dx, 0f, minX, minY)
    out = resizeRect(out, DragMode.RIGHT, dx, 0f, minX, minY)
    out = resizeRect(out, DragMode.TOP, 0f, -dy, minX, minY)
    return resizeRect(out, DragMode.BOTTOM, 0f, dy, minX, minY)
}

/** Shrink [r] inward on every side by normalized [dx]/[dy]; never inverts
 *  (the drag clamps keep every side ≥ [minX]/[minY] apart). */
internal fun shrinkRect(
    r: NormalizedRect,
    dx: Float,
    dy: Float,
    minX: Float,
    minY: Float,
): NormalizedRect {
    var out = resizeRect(r, DragMode.LEFT, dx, 0f, minX, minY)
    out = resizeRect(out, DragMode.RIGHT, -dx, 0f, minX, minY)
    out = resizeRect(out, DragMode.TOP, 0f, dy, minX, minY)
    return resizeRect(out, DragMode.BOTTOM, 0f, -dy, minX, minY)
}

/**
 * True when [mode]'s requested delta ([dx]/[dy], normalized) did not fully
 * land on [after] — the side hit the minimum crop size, a side crossed the
 * image edge, or a move ran out of image. The caller answers with a haptic
 * tick: a drag that silently stops is indistinguishable from a dropped
 * gesture, and the minimum-size floor is otherwise invisible.
 *
 * Compared per edge the mode actually drives, so a clamped left side never
 * reports for a drag that only moved the right one.
 */
internal fun clampedBy(
    before: NormalizedRect,
    after: NormalizedRect,
    mode: DragMode,
    dx: Float,
    dy: Float,
): Boolean {
    fun lost(requested: Float, achieved: Float): Boolean = abs(requested - achieved) > EPSILON
    fun left() = lost(dx, after.left - before.left)
    fun right() = lost(dx, after.right - before.right)
    fun top() = lost(dy, after.top - before.top)
    fun bottom() = lost(dy, after.bottom - before.bottom)
    return when (mode) {
        DragMode.NONE -> false
        DragMode.MOVE -> left() || top()
        DragMode.LEFT -> left()
        DragMode.RIGHT -> right()
        DragMode.TOP -> top()
        DragMode.BOTTOM -> bottom()
        DragMode.TL -> left() || top()
        DragMode.TR -> right() || top()
        DragMode.BL -> left() || bottom()
        DragMode.BR -> right() || bottom()
    }
}

/**
 * Normalized slack below which a delta counts as fully applied. A drag at the
 * floor achieves exactly 0 while its request is a gesture-worth of movement
 * (≈1e-3 normalized on a 4096 px source), so this only has to separate "0"
 * from "something".
 */
private const val EPSILON = 1e-5f
