package org.yangtse.hearwrite.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.yangtse.hearwrite.data.Haptics
import org.yangtse.hearwrite.data.NormalizedRect
import org.yangtse.hearwrite.data.OCR_CROP_MIN_SIDE_PX
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 选定识别区域 full-screen step (alice's `allowsEditing` system crop — c4690b3
 * — reimplemented in Compose, because Android has no guaranteed system crop;
 * expo also ships its own). The decoded source image is shown with a
 * draggable/resizable selection; confirming crops to that region. The
 * selection starts at the whole image, so a plain confirm keeps the old
 * whole-page OCR behavior. [bitmap] is the decode-in-flight spinner target.
 *
 * A photographed notebook page lands here at 4096 px → ~350 dp, where one
 * handwritten line is ~2 dp tall: without magnification the region cannot be
 * framed at all. The editor therefore has a **[CropViewport]** (contain fit ×
 * zoom, panned by two fingers, double-tap to toggle 1× / 2×) on top of the
 * selection drag, which stays on one finger.
 */
@Composable
fun OcrCropOverlay(
    bitmap: Bitmap?,
    onConfirm: (NormalizedRect) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            if (bitmap == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            } else {
                CropEditor(bitmap = bitmap, onConfirm = onConfirm, onDismiss = onDismiss)
            }
        }
    }
}

/** Bundle-safe saver for the normalized crop rect (listSaver → ArrayList). */
private val NormalizedRectSaver = listSaver<NormalizedRect, Float>(
    save = { listOf(it.left, it.top, it.right, it.bottom) },
    restore = { NormalizedRect(it[0], it[1], it[2], it[3]) },
)

/** Two-finger minimum gap between limit bumps, so a drag along an edge buzzes
 *  once on arrival instead of every frame. */
private const val LIMIT_TICK_COOLDOWN_MS = 600L

@Composable
private fun CropEditor(
    bitmap: Bitmap,
    onConfirm: (NormalizedRect) -> Unit,
    onDismiss: () -> Unit,
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    // Per-axis drag floor in normalized units: never squeeze a crop below
    // ~96 source pixels — OCR needs real text resolution in the region.
    val minSideX = remember(bitmap) {
        (OCR_CROP_MIN_SIDE_PX.toFloat() / bitmap.width).coerceAtMost(0.5f)
    }
    val minSideY = remember(bitmap) {
        (OCR_CROP_MIN_SIDE_PX.toFloat() / bitmap.height).coerceAtMost(0.5f)
    }
    var rect by rememberSaveable(
        bitmap,
        stateSaver = NormalizedRectSaver,
    ) { mutableStateOf(NormalizedRect(0f, 0f, 1f, 1f)) }
    val density = LocalDensity.current
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "拖动选框，选定要识别的区域（双指缩放，双击放大）",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
        }
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val areaW = with(density) { maxWidth.toPx() }
            val areaH = with(density) { maxHeight.toPx() }
            if (areaW > 0f && areaH > 0f) {
                // The keep-out inset: with the whole image selected, the
                // handles sit exactly on the selection edge — i.e. on the
                // screen edge — where the system's back gesture owns the touch
                // (its strip is ~24dp wide on each horizontal edge). Measured on
                // device: a drag started 25dp from the right edge left the app
                // instead of grabbing the handle. 40dp clears the strip with
                // room for the finger.
                val margin = with(density) { 40.dp.toPx() }
                var viewport by remember(bitmap, areaW, areaH) {
                    mutableStateOf(
                        CropViewport(areaW, areaH, bitmap.width, bitmap.height, margin = margin),
                    )
                }
                val fit = remember(bitmap, areaW, areaH) {
                    CropViewport(areaW, areaH, bitmap.width, bitmap.height, margin = margin)
                }
                val zoomed = viewport.zoom != fit.zoom ||
                    viewport.panX != fit.panX ||
                    viewport.panY != fit.panY

                // TalkBack moves the selection by ~10% of the *visible* crop —
                // coarse enough to cross a page region in a few swipes, and
                // unaffected by the viewport zoom (it is image-relative).
                val stepX = (viewport.width * 0.1f).coerceAtLeast(1f) / viewport.width
                val stepY = (viewport.height * 0.1f).coerceAtLeast(1f) / viewport.height

                // Hit tolerances. Corner tolerance covers a ~56 dp square and
                // edge tolerance a 48 dp band — the guidance size, where the
                // old 28/22 dp pair left the edges unhittable on a phone.
                val cornerTol = with(density) { 28.dp.toPx() }
                val edgeTol = with(density) { 24.dp.toPx() }

                // Image layer: reads no drag state, so it is not redrawn while
                // the selection moves.
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val vp = viewport
                    drawImage(
                        image = imageBitmap,
                        srcSize = IntSize(bitmap.width, bitmap.height),
                        dstOffset = IntOffset(vp.offsetX.roundToInt(), vp.offsetY.roundToInt()),
                        dstSize = IntSize(vp.width.roundToInt(), vp.height.roundToInt()),
                        filterQuality = FilterQuality.High,
                    )
                }
                // Selection layer: dim scrim, thirds grid, border, grips.
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val vp = viewport
                    val r = rect
                    val sel = Rect(
                        vp.screenX(r.left),
                        vp.screenY(r.top),
                        vp.screenX(r.right),
                        vp.screenY(r.bottom),
                    )
                    val scrim = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(0f, 0f, areaW, areaH))
                        addRect(sel)
                    }
                    drawPath(scrim, Color(0f, 0f, 0f, 0.6f))
                    val gridColor = Color.White.copy(alpha = 0.25f)
                    for (i in 1..2) {
                        val x = sel.left + sel.width * i / 3f
                        drawLine(gridColor, Offset(x, sel.top), Offset(x, sel.bottom), 1.dp.toPx())
                        val y = sel.top + sel.height * i / 3f
                        drawLine(gridColor, Offset(sel.left, y), Offset(sel.right, y), 1.dp.toPx())
                    }
                    drawRect(
                        color = Color.White,
                        topLeft = sel.topLeft,
                        size = sel.size,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                    drawGrips(sel)
                }
                // Gesture layer: one finger drags the selection (corner → edge
                // → inside hit test); two fingers zoom/pan the viewport under
                // it; a double tap toggles 1× / 2× about the tap. The
                // selection gestures are also semantics customActions so
                // TalkBack can drive them.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics {
                            contentDescription =
                                "拖动选框，选定要识别的区域。当前选区：左 ${(rect.left * 100).roundToInt()}%、上 ${(rect.top * 100).roundToInt()}%、右 ${(rect.right * 100).roundToInt()}%、下 ${(rect.bottom * 100).roundToInt()}%"
                            liveRegion = LiveRegionMode.Polite
                            customActions = listOf(
                                CustomAccessibilityAction("向左移动") {
                                    rect = moveRectBy(rect, -stepX, 0f, minSideX, minSideY); true
                                },
                                CustomAccessibilityAction("向右移动") {
                                    rect = moveRectBy(rect, stepX, 0f, minSideX, minSideY); true
                                },
                                CustomAccessibilityAction("向上移动") {
                                    rect = moveRectBy(rect, 0f, -stepY, minSideX, minSideY); true
                                },
                                CustomAccessibilityAction("向下移动") {
                                    rect = moveRectBy(rect, 0f, stepY, minSideX, minSideY); true
                                },
                                CustomAccessibilityAction("放大选区") {
                                    rect = growRect(rect, stepX, stepY, minSideX, minSideY); true
                                },
                                CustomAccessibilityAction("缩小选区") {
                                    rect = shrinkRect(rect, stepX, stepY, minSideX, minSideY); true
                                },
                                CustomAccessibilityAction("重置为整张图片") {
                                    rect = NormalizedRect(0f, 0f, 1f, 1f); true
                                },
                                CustomAccessibilityAction("放大画面") {
                                    viewport = viewport.zoomBy(1.5f, areaW / 2f, areaH / 2f); true
                                },
                                CustomAccessibilityAction("缩小画面") {
                                    viewport = viewport.zoomBy(1f / 1.5f, areaW / 2f, areaH / 2f); true
                                },
                                CustomAccessibilityAction("画面恢复整页") {
                                    viewport = viewport.reset(); true
                                },
                            )
                        }
                        .pointerInput(bitmap, areaW, areaH) {
                            // The gesture loop keys on geometry that does not
                            // change mid-gesture (bitmap + viewport area) and
                            // reads `rect`/`viewport` through their delegates,
                            // so a drag is never restarted by its own result.
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var mode = hitTest(down.position, rect, viewport, cornerTol, edgeTol)
                                var last = down.position
                                var lastLimitTick = 0L
                                // A pinch took over: the remaining finger must
                                // not resume the selection drag, or lifting one
                                // of two fingers would jump a handle.
                                var multiTouch = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.count { it.pressed }
                                    if (pressed == 0) break
                                    if (pressed >= 2) {
                                        // Two fingers own the viewport; the
                                        // selection stays put.
                                        val centroid = event.calculateCentroid(useCurrent = true)
                                        viewport = viewport
                                            .zoomBy(event.calculateZoom(), centroid.x, centroid.y)
                                            .panBy(event.calculatePan().x, event.calculatePan().y)
                                        event.changes.forEach { if (it.pressed) it.consume() }
                                        mode = DragMode.NONE
                                        multiTouch = true
                                        continue
                                    }
                                    val change = event.changes.firstOrNull { it.pressed } ?: break
                                    if (multiTouch) {
                                        // Stay inert until every finger is up.
                                        last = change.position
                                        change.consume()
                                        continue
                                    }
                                    val delta = change.position - last
                                    last = change.position
                                    if (mode != DragMode.NONE && viewport.width > 0f) {
                                        val dxN = delta.x / viewport.width
                                        val dyN = delta.y / viewport.height
                                        val next = resizeRect(rect, mode, dxN, dyN, minSideX, minSideY)
                                        val clamped = clampedBy(rect, next, mode, dxN, dyN)
                                        rect = next
                                        // A drag that silently stops reads as a
                                        // dropped gesture; bump once on arrival
                                        // at the minimum size or the image edge.
                                        val now = System.currentTimeMillis()
                                        if (clamped && now - lastLimitTick > LIMIT_TICK_COOLDOWN_MS) {
                                            lastLimitTick = now
                                            Haptics.tick(context)
                                        }
                                    }
                                    change.consume()
                                }
                            }
                        }
                        .pointerInput(bitmap, areaW, areaH) {
                            // Double-tap zoom, dispatched after the drag loop
                            // above (a tap never moves the selection).
                            detectTapGestures(
                                onDoubleTap = { at ->
                                    val target = if (viewport.zoom > 1.01f) 1f / viewport.zoom else 2f
                                    viewport = viewport.zoomBy(target, at.x, at.y)
                                },
                            )
                        },
                )
                if (zoomed) {
                    TextButton(
                        onClick = { viewport = viewport.reset() },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                    ) { Text("适应屏幕") }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
            ) {
                Text("取消")
            }
            TextButton(
                onClick = { rect = NormalizedRect(0f, 0f, 1f, 1f) },
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
            ) {
                Text("整张图片")
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { onConfirm(rect) }) {
                Text("识别所选区域")
            }
        }
    }
}

/**
 * Which part of the selection a press at [p] grabbed: a corner, an edge, the
 * inside (move) or nothing. Corners win over edges so the overlap zone resizes
 * the corner — the finer adjustment.
 */
private fun hitTest(
    p: Offset,
    rect: NormalizedRect,
    viewport: CropViewport,
    cornerTol: Float,
    edgeTol: Float,
): DragMode {
    val l = viewport.screenX(rect.left)
    val t = viewport.screenY(rect.top)
    val r = viewport.screenX(rect.right)
    val b = viewport.screenY(rect.bottom)
    fun corner(cx: Float, cy: Float) = abs(p.x - cx) <= cornerTol && abs(p.y - cy) <= cornerTol
    if (corner(l, t)) return DragMode.TL
    if (corner(r, t)) return DragMode.TR
    if (corner(l, b)) return DragMode.BL
    if (corner(r, b)) return DragMode.BR
    if (abs(p.y - t) <= edgeTol && p.x >= l - edgeTol && p.x <= r + edgeTol) return DragMode.TOP
    if (abs(p.y - b) <= edgeTol && p.x >= l - edgeTol && p.x <= r + edgeTol) return DragMode.BOTTOM
    if (abs(p.x - l) <= edgeTol && p.y >= t - edgeTol && p.y <= b + edgeTol) return DragMode.LEFT
    if (abs(p.x - r) <= edgeTol && p.y >= t - edgeTol && p.y <= b + edgeTol) return DragMode.RIGHT
    if (p.x > l && p.x < r && p.y > t && p.y < b) return DragMode.MOVE
    return DragMode.NONE
}

/**
 * Corner handles plus mid-edge bars. The bars are what makes the four edges
 * discoverable at all: the old overlay drew only corner dots, so the edge
 * resize zones it hit-tested were invisible, and half of every corner dot fell
 * outside the selection it belonged to.
 */
private fun DrawScope.drawGrips(sel: Rect) {
    val stroke = 4.dp.toPx()
    val arm = 22.dp.toPx()
    val bar = 4.dp.toPx()
    val length = 26.dp.toPx()
    // Corners: an L on each, drawn inside the selection.
    fun cornerL(cx: Float, cy: Float, sx: Float, sy: Float) {
        drawLine(Color.White, Offset(cx, cy), Offset(cx + sx * arm, cy), stroke)
        drawLine(Color.White, Offset(cx, cy), Offset(cx, cy + sy * arm), stroke)
    }
    cornerL(sel.left, sel.top, 1f, 1f)
    cornerL(sel.right, sel.top, -1f, 1f)
    cornerL(sel.left, sel.bottom, 1f, -1f)
    cornerL(sel.right, sel.bottom, -1f, -1f)
    // Mid-edge bars, centred on each side.
    val cx = sel.center.x
    val cy = sel.center.y
    drawRect(
        color = Color.White,
        topLeft = Offset(cx - length / 2f, sel.top - bar / 2f),
        size = Size(length, bar),
    )
    drawRect(
        color = Color.White,
        topLeft = Offset(cx - length / 2f, sel.bottom - bar / 2f),
        size = Size(length, bar),
    )
    drawRect(
        color = Color.White,
        topLeft = Offset(sel.left - bar / 2f, cy - length / 2f),
        size = Size(bar, length),
    )
    drawRect(
        color = Color.White,
        topLeft = Offset(sel.right - bar / 2f, cy - length / 2f),
        size = Size(bar, length),
    )
}
