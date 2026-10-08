package com.steptrack.offline
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate

val PAL = listOf(0xFF4FC3F7, 0xFF81C784, 0xFFFFD54F, 0xFFFF8A65, 0xFFE57373, 0xFFBA68C8, 0xFF7986CB, 0xFF4DB6AC).map { Color(it) }

@Composable fun BarChart(v: List<Float>, first: String, last: String, h: Dp = 130.dp) {
    Canvas(Modifier.fillMaxWidth().height(h)) {
        val mx = (v.maxOrNull() ?: 1f).coerceAtLeast(1f); val w = size.width / v.size.coerceAtLeast(1)
        v.forEachIndexed { i, x -> val bh = size.height * x / mx; drawRect(PAL[0], Offset(i * w + w * 0.1f, size.height - bh), Size(w * 0.8f, bh)) }
    }
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(first); Text("max ${(v.maxOrNull() ?: 0f).toInt()}"); Text(last) }
}

@Composable fun LineChart(v: List<Float>, h: Dp = 130.dp) {
    Canvas(Modifier.fillMaxWidth().height(h)) {
        val mx = (v.maxOrNull() ?: 1f).coerceAtLeast(1f); val p = Path()
        v.forEachIndexed { i, x -> val o = Offset(size.width * i / (v.size - 1).coerceAtLeast(1), size.height * (1 - x / mx)); if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
        drawPath(p, PAL[1], style = Stroke(4f))
    }
}

@Composable fun PieChart(counts: List<Int>) {
    val tot = counts.sum().coerceAtLeast(1)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(150.dp)) {
            var a = -90f
            counts.forEachIndexed { i, c -> val sw = 360f * c / tot; if (c > 0) drawArc(PAL[i], a, sw, true); a += sw }
        }
        Column(Modifier.padding(start = 16.dp)) { counts.forEachIndexed { i, c -> if (c > 0) Text("${BUCKETS[i]}  ${100 * c / tot}%  ($c)", color = PAL[i]) } }
    }
}

@Composable fun RoseChart(counts: List<Int>) {
    Canvas(Modifier.size(200.dp)) {
        val mx = (counts.maxOrNull() ?: 1).coerceAtLeast(1); val r = size.minDimension / 2; val c = center
        drawCircle(Color(0x44FFFFFF), r, c, style = Stroke(2f))
        counts.forEachIndexed { i, v ->
            val rr = r * v / mx
            drawArc(PAL[i], -90f + i * 45f - 22.5f, 45f, true, Offset(c.x - rr, c.y - rr), Size(2 * rr, 2 * rr))
        }
    }
}

@Composable fun Heatmap(byDate: Map<String, Int>) {
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        val cell = size.width / 53f; val mx = (byDate.values.maxOrNull() ?: 1).coerceAtLeast(1)
        for (i in 0 until 365) {
            val d = LocalDate.now().minusDays((364 - i).toLong()).toString(); val v = byDate[d] ?: 0
            drawRect(PAL[1].copy(alpha = if (v == 0) 0.08f else (0.25f + 0.75f * v / mx)), Offset((i / 7) * cell, (i % 7) * cell), Size(cell * 0.85f, cell * 0.85f))
        }
    }
}

/** Altitude over time, scaled min..max, with a marker line at mark (0..1). */
@Composable fun AltChart(v: List<Float>, mark: Float, h: Dp = 150.dp) {
    val mn = v.minOrNull() ?: 0f; val mx = v.maxOrNull() ?: 1f; val rng = (mx - mn).coerceAtLeast(1f)
    Canvas(Modifier.fillMaxWidth().height(h)) {
        val p = Path()
        v.forEachIndexed { i, a -> val o = Offset(size.width * i / (v.size - 1).coerceAtLeast(1), size.height * (1 - (a - mn) / rng)); if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
        drawPath(p, PAL[3], style = Stroke(4f))
        val x = size.width * mark.coerceIn(0f, 1f); drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 2f)
    }
}
