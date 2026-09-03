package org.yangtse.hearwrite.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.yangtse.hearwrite.data.NormalizedRect
import org.yangtse.hearwrite.data.OCR_CROP_MIN_SIDE_PX
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 选定识别区域 full-screen step (alice's `allowsEditing` system crop — c4690b3
 * — reimplemented in Compose, because Android has no guaranteed system crop;
 * expo also ships its own). The decoded source image is shown with a
 * draggable/resizable selection; confirming crops to that region. The
 * selection starts at the whole image, so a plain confirm keeps the old
 * whole-page OCR behavior. [bitmap] is the decode-in-flight spinner target.
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

/** Which part of the selection a drag started on. */
private enum class DragMode { NONE, MOVE, TL, TR, BL, BR, LEFT, RIGHT, TOP, BOTTOM }

/** Bundle-safe saver for the normalized crop rect (listSaver → ArrayList). */
private val NormalizedRectSaver = listSaver<NormalizedRect, Float>(
    save = { listOf(it.left, it.top, it.right, it.bottom) },
    restore = { NormalizedRect(it[0], it[1], it[2], it[3]) },
)

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

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "拖动选框，选定要识别的区域",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        )
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val areaW = with(density) { maxWidth.toPx() }
            val areaH = with(density) { maxHeight.toPx() }
            if (areaW > 0f && areaH > 0f) {
                // Image fit (contain) within the available area.
                val scale = min(areaW / bitmap.width, areaH / bitmap.height)
                val dispW = bitmap.width * scale
                val dispH = bitmap.height * scale
                val offX = (areaW - dispW) / 2f
                val offY = (areaH - dispH) / 2f

                // Image layer: reads no drag state, so it is not redrawn
                // while the selection moves.
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawImage(
                        image = imageBitmap,
                        dstOffset = IntOffset(offX.roundToInt(), offY.roundToInt()),
                        dstSize = IntSize(dispW.roundToInt(), dispH.roundToInt()),
                        filterQuality = FilterQuality.High,
                    )
                }
                // Selection layer: dim scrim, thirds grid, border, grips.
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val r = rect
                    val sel = Rect(
                        offX + r.left * dispW,
                        offY + r.top * dispH,
                        offX + r.right * dispW,
                        offY + r.bottom * dispH,
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
                    val grip = 7.dp.toPx()
                    drawCircle(Color.White, grip, sel.topLeft)
                    drawCircle(Color.White, grip, sel.topRight)
                    drawCircle(Color.White, grip, sel.bottomLeft)
                    drawCircle(Color.White, grip, sel.bottomRight)
                }
                // Gesture layer: corner → edge → inside hit test; drags move
                // or resize the normalized rect.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(bitmap, areaW, areaH, dispW, dispH, offX, offY) {
                            var mode = DragMode.NONE
                            val cornerTol = 28.dp.toPx()
                            val edgeTol = 22.dp.toPx()
                            fun hitTest(p: Offset): DragMode {
                                val r = rect
                                val l = offX + r.left * dispW
                                val t = offY + r.top * dispH
                                val rt = offX + r.right * dispW
                                val b = offY + r.bottom * dispH
                                fun corner(cx: Float, cy: Float) =
                                    abs(p.x - cx) <= cornerTol && abs(p.y - cy) <= cornerTol
                                if (corner(l, t)) return DragMode.TL
                                if (corner(rt, t)) return DragMode.TR
                                if (corner(l, b)) return DragMode.BL
                                if (corner(rt, b)) return DragMode.BR
                                if (abs(p.y - t) <= edgeTol && p.x >= l - edgeTol && p.x <= rt + edgeTol) {
                                    return DragMode.TOP
                                }
                                if (abs(p.y - b) <= edgeTol && p.x >= l - edgeTol && p.x <= rt + edgeTol) {
                                    return DragMode.BOTTOM
                                }
                                if (abs(p.x - l) <= edgeTol && p.y >= t - edgeTol && p.y <= b + edgeTol) {
                                    return DragMode.LEFT
                                }
                                if (abs(p.x - rt) <= edgeTol && p.y >= t - edgeTol && p.y <= b + edgeTol) {
                                    return DragMode.RIGHT
                                }
                                if (p.x > l && p.x < rt && p.y > t && p.y < b) return DragMode.MOVE
                                return DragMode.NONE
                            }
                            fun applyDrag(dxN: Float, dyN: Float) {
                                rect = resizeRect(rect, mode, dxN, dyN, minSideX, minSideY)
                            }
                            detectDragGestures(
                                onDragStart = { pos -> mode = hitTest(pos) },
                                onDragEnd = { mode = DragMode.NONE },
                                onDragCancel = { mode = DragMode.NONE },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    if (mode != DragMode.NONE && dispW > 0f && dispH > 0f) {
                                        applyDrag(dragAmount.x / dispW, dragAmount.y / dispH)
                                    }
                                },
                            )
                        },
                )
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
            Spacer(Modifier.weight(1f))
            Button(onClick = { onConfirm(rect) }) {
                Text("识别所选区域")
            }
        }
    }
}

/**
 * Apply a drag delta (normalized to the image) to [r]: move translates and
 * clamps to the image; corners/edges resize with the opposite side fixed.
 * Sides never cross: clamps keep every side ≥ [minX]/[minY] apart and inside
 * the [0,1] image space.
 */
private fun resizeRect(
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
