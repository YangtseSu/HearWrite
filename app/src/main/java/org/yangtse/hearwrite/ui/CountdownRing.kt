package org.yangtse.hearwrite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The countdown watch face: a ring that depletes with the interval countdown.
 * [progressFraction] null renders a static track (idle/hold/pause); the caller
 * draws the center content (word reveal zone, seconds) and owns the semantics.
 */
@Composable
fun CountdownRing(
    progressFraction: Float?,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 10.dp,
    color: Color,
    trackColor: Color,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val diameter = size.minDimension - stroke
            val arc = Size(diameter, diameter)
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arc,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            val fraction = progressFraction?.coerceIn(0f, 1f)
            if (fraction != null && fraction > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arc,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        content()
    }
}
