package org.yangtse.hearwrite.ui

import kotlin.math.max
import kotlin.math.min

/**
 * Maximum zoom of the crop editor's viewport. A 4096 px page (the decode cap)
 * shown in ~350 dp leaves one handwritten line ≈2 dp tall, so the editor has to
 * be magnifiable before a region can be framed; 8× puts a line back at a
 * comfortable editing size without letting the user get lost in pixels.
 */
const val CROP_MAX_ZOOM = 8f

/**
 * Image → viewport mapping of the 选定识别区域 editor: a **contain** fit of the
 * decoded source, scaled by [zoom] and shifted [panX]/[panY] px from the centred
 * position. Plain floats, no Compose geometry — the mapping is what a drag's
 * correctness depends on (a wrong one drags the wrong handle), so it is kept
 * unit-testable and free of any Android dependency.
 *
 * [panX]/[panY] are clamped to the pannable range: forced to zero on an axis
 * where the scaled image still fits (it stays centred there), otherwise capped
 * at ±(display − area)/2 so the image always covers the viewport with no gap.
 * [clamped] is what enforces that; every transform funnels through it, so an
 * instance obtained from [zoomBy]/[panBy]/[reset] is always legal.
 *
 * [scale]/[width]/[height]/[offsetX]/[offsetY] are derived once per instance —
 * the crop canvas reads them on every drag frame.
 */
data class CropViewport(
    val areaW: Float,
    val areaH: Float,
    val imageW: Int,
    val imageH: Int,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    /**
     * Gap kept between the fitted image and the viewport edges. At the default
     * whole-image selection every handle sits exactly on the selection's own
     * edge — with no gap that is the **screen** edge, where the system's back
     * and home gestures own the touch and a handle cannot be grabbed at all.
     */
    val margin: Float = 0f,
) {
    /** Viewport px → image px. */
    val scale: Float = min(
        innerW / imageW,
        innerH / imageH,
    ) * zoom

    val width: Float = imageW * scale
    val height: Float = imageH * scale
    val offsetX: Float = margin + (innerW - width) / 2f + panX
    val offsetY: Float = margin + (innerH - height) / 2f + panY

    /** Area the image may occupy: the viewport minus the keep-out margin. */
    private val innerW: Float get() = (areaW - 2f * margin).coerceAtLeast(1f)
    private val innerH: Float get() = (areaH - 2f * margin).coerceAtLeast(1f)

    /** Normalized image coord → viewport px. */
    fun screenX(u: Float): Float = offsetX + u * width
    fun screenY(v: Float): Float = offsetY + v * height

    /** Viewport px → normalized image coord (the inverse of [screenX]/[screenY]). */
    fun imageX(x: Float): Float = (x - offsetX) / width
    fun imageY(y: Float): Float = (y - offsetY) / height

    /** Whole image, un-zoomed and centred. */
    fun reset(): CropViewport = copy(zoom = 1f, panX = 0f, panY = 0f).clamped()

    /**
     * Scale by [factor] about the viewport point ([atX], [atY]) — the image
     * pixel under the finger stays put, which is what makes a pinch feel
     * attached to the photo. A factor that would leave the zoom range is a
     * no-op.
     */
    fun zoomBy(factor: Float, atX: Float, atY: Float): CropViewport {
        if (width <= 0f || height <= 0f) return this
        val target = (zoom * factor).coerceIn(1f, CROP_MAX_ZOOM)
        if (target == zoom) return this
        val u = imageX(atX)
        val v = imageY(atY)
        val probe = copy(zoom = target, panX = 0f, panY = 0f)
        // The point (u,v) must land on (atX,atY): offset = at − u × display,
        // and the offset is margin + centring + pan.
        return copy(
            zoom = target,
            panX = atX - u * probe.width - (margin + (innerW - probe.width) / 2f),
            panY = atY - v * probe.height - (margin + (innerH - probe.height) / 2f),
        ).clamped()
    }

    /** Shift the view by a viewport-px delta, clamped to the image bounds. */
    fun panBy(dx: Float, dy: Float): CropViewport =
        copy(panX = panX + dx, panY = panY + dy).clamped()

    /** This mapping with [zoom]/[panX]/[panY] pulled back inside their ranges. */
    fun clamped(): CropViewport {
        val z = zoom.coerceIn(1f, CROP_MAX_ZOOM)
        val probe = copy(zoom = z)
        // A pan may use the margin too: the image is allowed to travel until
        // its own edge reaches the viewport edge.
        val limitX = max(0f, (probe.width + 2f * margin - areaW) / 2f)
        val limitY = max(0f, (probe.height + 2f * margin - areaH) / 2f)
        return copy(
            zoom = z,
            panX = panX.coerceIn(-limitX, limitX),
            panY = panY.coerceIn(-limitY, limitY),
        )
    }
}
