package com.steptrack.offline
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.*

/**
 * Radar-style relative path (north up). progress 0..1 = replay. Optional extras (used by the Move tab only):
 * heading = draw compass ring + heading needle at current position, zoom/pan = pinch zoom (2 fingers),
 * colors = one colour per point (movement type).
 */
@Composable
fun PathPlot(pts: List<Pair<Float, Float>>, progress: Float = 1f, modifier: Modifier = Modifier.fillMaxWidth().aspectRatio(1f),
             heading: Float? = null, zoom: Float = 1f, pan: Offset = Offset.Zero, colors: List<Color>? = null,
             onTransform: ((Float, Offset) -> Unit)? = null) {
    val all = listOf(0f to 0f) + pts
    val upd = rememberUpdatedState(onTransform)
    var m = modifier
    if (onTransform != null) m = m.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val e = awaitPointerEvent()
                if (e.changes.size >= 2) { upd.value?.invoke(e.calculateZoom(), e.calculatePan()); e.changes.forEach { it.consume() } }
            } while (e.changes.any { it.pressed })
        }
    }
    Canvas(m) {
        val ctr = center; val rad = size.minDimension / 2 * 0.9f
        val ext = all.maxOf { maxOf(abs(it.first), abs(it.second)) }.coerceAtLeast(5f); val s = rad / ext * zoom
        val n = (all.size * progress).toInt().coerceIn(0, all.size)
        val end = all[(n - 1).coerceAtLeast(0)]
        val t = ((zoom - 1f) / 3f).coerceIn(0f, 1f); val fx = end.first * t; val fy = end.second * t
        val o = ctr + pan
        fun pt(x: Float, y: Float) = Offset(o.x + (x - fx) * s, o.y - (y - fy) * s)
        val grid = Color(0x44FFFFFF)
        drawCircle(grid, rad, ctr, style = Stroke(2f)); drawCircle(grid, rad / 2, ctr, style = Stroke(2f))
        drawLine(grid, Offset(ctr.x - rad, ctr.y), Offset(ctr.x + rad, ctr.y)); drawLine(grid, Offset(ctr.x, ctr.y - rad), Offset(ctr.x, ctr.y + rad))
        if (heading != null) { // compass ring merged with the radar
            for (i in 0 until 8) { val a = Math.toRadians(i * 45.0); val dx = sin(a).toFloat(); val dy = -cos(a).toFloat()
                drawLine(Color(0x99FFFFFF), Offset(ctr.x + dx * rad * 0.95f, ctr.y + dy * rad * 0.95f), Offset(ctr.x + dx * rad, ctr.y + dy * rad), 3f) }
            val pt2 = android.graphics.Paint().apply { textSize = 38f; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true }
            listOf("N", "E", "S", "W").forEachIndexed { i, l ->
                pt2.color = if (i == 0) android.graphics.Color.RED else android.graphics.Color.WHITE
                val a = Math.toRadians(i * 90.0)
                drawContext.canvas.nativeCanvas.drawText(l, ctr.x + (sin(a) * rad * 1.07).toFloat(), ctr.y - (cos(a) * rad * 1.07).toFloat() + 13f, pt2)
            }
            val rm = rad / s; val lab = if (rm >= 1000f) "%.1f km".format(rm / 1000f) else "%.0f m".format(rm)
            val sp = android.graphics.Paint().apply { textSize = 30f; color = android.graphics.Color.LTGRAY; isAntiAlias = true }
            drawContext.canvas.nativeCanvas.drawText("outer ring = $lab", 10f, size.height - 10f, sp)
        }
        if (n > 1) {
            if (colors == null) {
                val p = Path()
                all.take(n).forEachIndexed { i, (x, y) -> val q = pt(x, y); if (i == 0) p.moveTo(q.x, q.y) else p.lineTo(q.x, q.y) }
                drawPath(p, Color.Cyan, style = Stroke(5f))
                drawCircle(Color.Yellow, 9f, pt(end.first, end.second))
            } else for (i in 1 until n) {
                val a = all[i - 1]; val b = all[i]
                drawLine(color = colors.getOrElse(i - 1) { Color.Cyan }, start = pt(a.first, a.second), end = pt(b.first, b.second), strokeWidth = 6f, cap = StrokeCap.Round)
            }
        }
        if (colors == null) drawCircle(Color.Green, 10f, pt(0f, 0f))
        else drawCircle(Color.White, 11f, pt(0f, 0f), style = Stroke(4f))
        if (heading != null) { // current position + heading needle
            val p = pt(end.first, end.second); drawCircle(Color.White, 10f, p)
            val a = Math.toRadians(heading.toDouble()); val dx = sin(a).toFloat(); val dy = -cos(a).toFloat()
            val tip = Offset(p.x + dx * 60f, p.y + dy * 60f)
            drawLine(Color.Red, p, tip, 8f, StrokeCap.Round)
            for (th in listOf(0.5f, -0.5f)) { val vx = -dx; val vy = -dy
                drawLine(Color.Red, tip, Offset(tip.x + (vx * cos(th) - vy * sin(th)) * 22f, tip.y + (vx * sin(th) + vy * cos(th)) * 22f), 8f, StrokeCap.Round) }
        }
    }
}
