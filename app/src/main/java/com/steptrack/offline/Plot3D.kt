package com.steptrack.offline
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.*

data class P3(val t: Long, val x: Float, val y: Float, val z: Float)

/** Orthographic 3D path: x=east, y=north, z=height. yaw/tilt in degrees (tilt 90 = top view). Colour = height (blue low, red high). */
@Composable
fun Plot3D(pts: List<P3>, yaw: Float, tilt: Float, zoom: Float, exag: Float, progress: Float,
           modifier: Modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
    Canvas(modifier) {
        if (pts.size < 2) return@Canvas
        val x0 = pts.minOf { it.x }; val x1 = pts.maxOf { it.x }; val y0 = pts.minOf { it.y }; val y1 = pts.maxOf { it.y }
        val z0 = pts.minOf { it.z }; val z1 = pts.maxOf { it.z }
        val mx = (x0 + x1) / 2; val my = (y0 + y1) / 2
        val hx = max((x1 - x0) / 2, 5f); val hy = max((y1 - y0) / 2, 5f); val hz = max((z1 - z0) / 2 * exag, 1f)
        val s = size.minDimension / 2 * 0.8f / max(max(hx, hy), hz) * zoom
        val th = Math.toRadians(yaw.toDouble()); val ph = Math.toRadians(tilt.toDouble())
        val cY = cos(th).toFloat(); val sY = sin(th).toFloat(); val cT = cos(ph).toFloat(); val sT = sin(ph).toFloat()
        fun proj(x: Float, y: Float, z: Float): Offset {
            val px = x - mx; val py = y - my; val pz = (z - z0) * exag - hz
            val a = px * cY - py * sY; val b = px * sY + py * cY
            return Offset(center.x + a * s, center.y - (pz * cT + b * sT) * s)
        }
        val g = Color(0x44FFFFFF)
        val c1 = proj(mx - hx, my - hy, z0); val c2 = proj(mx + hx, my - hy, z0); val c3 = proj(mx + hx, my + hy, z0); val c4 = proj(mx - hx, my + hy, z0)
        drawLine(g, c1, c2, 2f); drawLine(g, c2, c3, 2f); drawLine(g, c3, c4, 2f); drawLine(g, c4, c1, 2f)
        drawLine(g, proj(mx - hx, my, z0), proj(mx + hx, my, z0), 1.5f); drawLine(g, proj(mx, my - hy, z0), proj(mx, my + hy, z0), 1.5f)
        val tp = android.graphics.Paint().apply { textSize = 36f; color = android.graphics.Color.RED; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true }
        val nn = proj(mx, my + hy * 1.12f, z0); drawContext.canvas.nativeCanvas.drawText("N", nn.x, nn.y + 12f, tp)
        val n = (pts.size * progress).toInt().coerceIn(2, pts.size); val rng = (z1 - z0).coerceAtLeast(0.5f)
        for (i in 1 until n) {
            val a = pts[i - 1]; val b = pts[i]
            val col = lerp(Color(0xFF2196F3), Color(0xFFF44336), ((b.z - z0) / rng).coerceIn(0f, 1f))
            drawLine(Color(0x33FFFFFF), proj(a.x, a.y, z0), proj(b.x, b.y, z0), 2f)
            drawLine(col, proj(a.x, a.y, a.z), proj(b.x, b.y, b.z), 6f, StrokeCap.Round)
            if (i % 12 == 0) drawLine(Color(0x22FFFFFF), proj(b.x, b.y, b.z), proj(b.x, b.y, z0), 1.5f)
        }
        drawCircle(Color.White, 11f, proj(pts[0].x, pts[0].y, pts[0].z), style = Stroke(4f))
        drawCircle(Color.White, 10f, proj(pts[n - 1].x, pts[n - 1].y, pts[n - 1].z))
    }
}
