package org.yangtse.hearwrite.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yangtse.hearwrite.data.NormalizedRect

/**
 * 选定识别区域 geometry: the image↔viewport mapping (contain fit, zoom about a
 * point, pan clamping) and the drag results the overlay applies. A wrong
 * mapping drags the wrong handle, and a wrong clamp check either spams the
 * limit haptic or never fires it — both are invisible to the UI tests, so the
 * arithmetic is pinned here.
 */
class CropViewportTest {

    /** 4096×3072 source (4:3) in a 1000×1000 viewport: the fit is width-bound. */
    private fun viewport() = CropViewport(areaW = 1000f, areaH = 1000f, imageW = 4096, imageH = 3072)

    @Test
    fun `contain fit letterboxes and centres the image`() {
        val v = viewport()
        // 1000 / 4096 < 1000 / 3072, so the width decides the scale.
        assertEquals(1000f, v.width, 0.01f)
        assertEquals(750f, v.height, 0.01f)
        assertEquals(0f, v.offsetX, 0.01f)
        // The 3:4 height is centred in the square area.
        assertEquals(125f, v.offsetY, 0.01f)
    }

    @Test
    fun `normalized coords map to the displayed image`() {
        val v = viewport()
        assertEquals(0f, v.screenX(0f), 0.01f)
        assertEquals(1000f, v.screenX(1f), 0.01f)
        assertEquals(125f, v.screenY(0f), 0.01f)
        assertEquals(875f, v.screenY(1f), 0.01f)
        // … and back: the mapping the drag handler inverts.
        assertEquals(0.5f, v.imageX(500f), 0.001f)
        assertEquals(0.5f, v.imageY(500f), 0.001f)
    }

    @Test
    fun `zoom keeps the point under the finger fixed`() {
        val v = viewport()
        val atX = 300f
        val atY = 400f
        val u = v.imageX(atX)
        val vv = v.imageY(atY)
        val zoomed = v.zoomBy(2f, atX, atY)
        assertEquals(2f, zoomed.zoom, 0.001f)
        // The image pixel that was under the finger is still under it.
        assertEquals(atX, zoomed.screenX(u), 0.5f)
        assertEquals(atY, zoomed.screenY(vv), 0.5f)
    }

    @Test
    fun `zoom is clamped to the supported range`() {
        val v = viewport()
        assertEquals(CROP_MAX_ZOOM, v.zoomBy(100f, 500f, 500f).zoom, 0.001f)
        // Already at 1×: zooming out is a no-op, never a shrink below the fit.
        assertEquals(1f, v.zoomBy(0.5f, 500f, 500f).zoom, 0.001f)
    }

    @Test
    fun `pan is clamped so the image always covers the viewport`() {
        val v = viewport().zoomBy(2f, 500f, 500f)
        // At 2× the 2000 px-wide image can move ±500 px; beyond that it would
        // expose the letterboxing behind it.
        val far = v.panBy(5000f, 5000f)
        assertEquals(500f, far.panX, 0.01f)
        assertEquals(250f, far.panY, 0.01f)
        val farBack = v.panBy(-5000f, -5000f)
        assertEquals(-500f, farBack.panX, 0.01f)
        assertEquals(-250f, farBack.panY, 0.01f)
    }

    @Test
    fun `pan is ignored on an axis where the image still fits`() {
        val v = viewport()
        // Un-zoomed the image fits both axes; a pan must not shift it off-centre.
        val panned = v.panBy(200f, 200f)
        assertEquals(0f, panned.panX, 0.001f)
        assertEquals(0f, panned.panY, 0.001f)
    }

    @Test
    fun `reset returns the whole image`() {
        val v = viewport().zoomBy(4f, 100f, 900f).panBy(50f, -50f)
        val reset = v.reset()
        assertEquals(1f, reset.zoom, 0.001f)
        assertEquals(0f, reset.panX, 0.001f)
        assertEquals(0f, reset.panY, 0.001f)
    }

    @Test
    fun `margin keeps the fitted image off the viewport edges`() {
        val m = 40f
        val v = CropViewport(1000f, 1000f, 4096, 3072, margin = m)
        // The fit must leave the margin free on the constrained axis and stay
        // centred on the other; without it the whole-image selection's handles
        // land on the screen edge, inside the system gesture strips.
        assertEquals(m, v.offsetX, 0.01f)
        assertEquals(1000f - m, v.offsetX + v.width, 0.01f)
        assertTrue(v.offsetY >= m - 0.01f)
        assertEquals(500f, v.offsetY + v.height / 2f, 0.01f)
    }

    @Test
    fun `a panned image stops on the margin line, never on the screen edge`() {
        // [clamped]'s pan limit counts the margin, so a travelled image ends up
        // exactly where an unpanned one sits: the near edge on the margin line
        // (40 dp), the far side overflowing the viewport (which is what keeps
        // the viewport covered). Deliberate, not a slip: the margin-less limit
        // would put that same edge at **0** — the screen edge, where the
        // system's back/home gesture owns the touch and a selection handle
        // cannot be grabbed, which is exactly what the margin exists to prevent.
        val m = 40f
        val v = CropViewport(1000f, 1000f, 4096, 4096, margin = m).zoomBy(2f, 500f, 500f)
        val overflowPerSide = (v.width - 1000f) / 2f
        assertEquals(420f, overflowPerSide, 0.01f)
        val far = v.panBy(9999f, 9999f)
        // The limit is the overflow *plus the margin*, so the near edge of the
        // travelled image lands exactly where an unpanned one leaves it (40 dp)
        // rather than on the screen edge (0).
        assertEquals(overflowPerSide + m, far.panX, 0.01f)
        assertEquals(m, far.offsetX, 0.01f)
        // The mirrored drag is symmetric, so the contract holds from both
        // directions.
        assertEquals(-(overflowPerSide + m), v.panBy(-9999f, -9999f).panX, 0.01f)
    }
}

/**
 * Selection drags: the clamps that keep a crop inside the image and above the
 * minimum size, and [clampedBy] — the signal that turns a silent stop into the
 * limit haptic.
 */
class CropRectTest {

    private val minSide = 0.1f

    @Test
    fun `move preserves size and stops at the image edge`() {
        val r = NormalizedRect(0.2f, 0.2f, 0.4f, 0.4f)
        val moved = resizeRect(r, DragMode.MOVE, -1f, 0f, minSide, minSide)
        assertEquals(0f, moved.left, 1e-6f)
        assertEquals(0.2f, moved.right, 1e-6f)
        // Height untouched by a horizontal drag.
        assertEquals(0.2f, moved.top, 1e-6f)
        assertEquals(0.4f, moved.bottom, 1e-6f)
    }

    @Test
    fun `a side never crosses its opposite and never passes the minimum`() {
        val r = NormalizedRect(0.4f, 0.4f, 0.6f, 0.6f)
        // Dragging the right side far past the left stops one minSide away.
        val squeezed = resizeRect(r, DragMode.RIGHT, -1f, 0f, minSide, minSide)
        assertEquals(0.4f, squeezed.left, 1e-6f)
        assertEquals(0.5f, squeezed.right, 1e-6f)
    }

    @Test
    fun `clampedBy reports the floor and quiet drags`() {
        val r = NormalizedRect(0.4f, 0.4f, 0.6f, 0.6f)
        // At the floor: the request (-0.3) is not what happened (-0.1).
        val floored = resizeRect(r, DragMode.RIGHT, -0.3f, 0f, minSide, minSide)
        assertTrue(clampedBy(r, floored, DragMode.RIGHT, -0.3f, 0f))
        // A drag that landed in full is not a clamp.
        val free = resizeRect(r, DragMode.RIGHT, 0.2f, 0f, minSide, minSide)
        assertFalse(clampedBy(r, free, DragMode.RIGHT, 0.2f, 0f))
        // A drag that grabbed nothing (outside the selection) never reports.
        assertFalse(clampedBy(r, free, DragMode.NONE, 0.2f, 0f))
        // A left edge that has room to move is not a clamp either.
        val movedLeft = resizeRect(r, DragMode.LEFT, 0.05f, 0f, minSide, minSide)
        assertEquals(0.45f, movedLeft.left, 1e-6f)
        assertFalse(clampedBy(r, movedLeft, DragMode.LEFT, 0.05f, 0f))
    }

    @Test
    fun `clampedBy reports an image-edge stop`() {
        val r = NormalizedRect(0f, 0.2f, 0.2f, 0.4f)
        val blocked = resizeRect(r, DragMode.LEFT, -0.5f, 0f, minSide, minSide)
        assertTrue(clampedBy(r, blocked, DragMode.LEFT, -0.5f, 0f))
    }

    @Test
    fun `grow and shrink keep every side above the minimum`() {
        val r = NormalizedRect(0.4f, 0.4f, 0.6f, 0.6f)
        val grown = growRect(r, 0.1f, 0.1f, minSide, minSide)
        assertEquals(0.3f, grown.left, 1e-6f)
        assertEquals(0.7f, grown.right, 1e-6f)
        val out = shrinkRect(grown, 1f, 1f, minSide, minSide)
        assertTrue(out.right - out.left >= minSide - 1e-6f)
        assertTrue(out.bottom - out.top >= minSide - 1e-6f)
    }
}
