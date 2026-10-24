package com.mohamed.dev.ui.storageanalyzer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Minimal dependency-free line chart for the snapshot timeline.
 *
 * Values are raw bytes; the chart normalizes them for display only. A flat
 * series renders as a centered line rather than dividing by a zero range.
 */
@Composable
fun FootprintChart(
    values: List<Long>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    fillColor: Color = MaterialTheme.colorScheme.primary,
) {
    if (values.isEmpty()) return
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val lastPointColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(170.dp),
    ) {
        val gridLines = 4
        repeat(gridLines + 1) { index ->
            val y = size.height * index / gridLines
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
            )
        }

        val min = values.minOrNull() ?: 0L
        val max = values.maxOrNull() ?: 0L
        val flat = max == min
        val span = (max - min).coerceAtLeast(1L).toDouble()
        val horizontalPadding = 2.dp.toPx()
        val verticalPadding = 12.dp.toPx()
        val usableWidth = (size.width - horizontalPadding * 2).coerceAtLeast(1f)
        val usableHeight = (size.height - verticalPadding * 2).coerceAtLeast(1f)

        fun xAt(index: Int): Float =
            if (values.size == 1) size.width / 2f
            else horizontalPadding + usableWidth * index / (values.size - 1)

        fun yAt(value: Long): Float {
            if (flat) return size.height / 2f
            val ratio = (value - min) / span
            return (size.height - verticalPadding - ratio * usableHeight).toFloat()
        }

        val linePath = Path()
        values.forEachIndexed { index, value ->
            val x = xAt(index)
            val y = yAt(value)
            if (index == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
        }

        val fillPath = Path().apply {
            addPath(linePath)
            lineTo(xAt(values.lastIndex), size.height)
            lineTo(xAt(0), size.height)
            close()
        }
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(fillColor.copy(alpha = 0.30f), Color.Transparent),
            ),
        )
        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(
                width = 3.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )

        val lastX = xAt(values.lastIndex)
        val lastY = yAt(values.last())
        drawCircle(color = lastPointColor.copy(alpha = 0.20f), radius = 9.dp.toPx(), center = Offset(lastX, lastY))
        drawCircle(color = lastPointColor, radius = 4.dp.toPx(), center = Offset(lastX, lastY))
    }
}
