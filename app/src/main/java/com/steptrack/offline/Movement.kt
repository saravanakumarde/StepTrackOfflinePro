package com.steptrack.offline
import androidx.compose.ui.graphics.Color

val MOVE_NAMES = listOf("Still", "Walking", "Running", "Slow vehicle / bike", "Automobile", "Fast (train / highway)")
val MOVE_COLORS = listOf(Color(0xFF9E9E9E), Color(0xFF4CAF50), Color(0xFFFF9800), Color(0xFF00BCD4), Color(0xFFFFEB3B), Color(0xFFF44336))

/** Movement type per GPS point from GPS speed + steps taken in the previous 10 s (on foot vs vehicle). Points must be time-ordered. */
fun classifyMoves(gps: List<GpsPoint>, stepTs: List<Long>): List<Int> {
    val st = stepTs.sorted(); var lo = 0; var hi = 0
    return gps.map { g ->
        while (hi < st.size && st[hi] <= g.timestamp) hi++
        while (lo < hi && st[lo] <= g.timestamp - 10000) lo++
        val steps = hi - lo; val v = g.speed
        when {
            steps >= 5 && v < 4.5f -> if (v < 2.2f) 1 else 2
            v < 0.5f -> 0
            v < 7f -> 3
            v < 28f -> 4
            else -> 5
        }
    }
}

fun distByType(gps: List<GpsPoint>, types: List<Int>): FloatArray {
    val a = FloatArray(MOVE_NAMES.size)
    for (i in 1 until gps.size) { val d = gps[i].distance - gps[i - 1].distance; if (d > 0f) a[types[i]] += d }
    return a
}

data class ElevStats(val min: Float, val max: Float, val ascent: Float, val descent: Float, val net: Float)

/** Total ascent/descent with a 1.5 m hysteresis so sensor noise is not counted as climbing. */
fun elevStats(a: List<Float>): ElevStats? {
    if (a.isEmpty()) return null
    var ref = a[0]; var up = 0f; var down = 0f
    for (v in a) { if (v - ref >= 1.5f) { up += v - ref; ref = v } else if (ref - v >= 1.5f) { down += ref - v; ref = v } }
    return ElevStats(a.min(), a.max(), up, down, a.last() - a.first())
}

fun altSeries(ev: List<StepEvent>, gps: List<GpsPoint>): List<Pair<Long, Float>> =
    (ev.filter { it.alt > -9000f }.map { it.timestamp to it.alt } + gps.filter { it.alt > -9000f }.map { it.timestamp to it.alt }).sortedBy { it.first }

fun <T> downsample(l: List<T>, n: Int): List<T> = if (l.size <= n) l else List(n) { l[(it.toLong() * (l.size - 1) / (n - 1)).toInt()] }

/** Nearest saved place whose radius contains this position, or null. */
fun placeFor(lat: Double, lon: Double, places: List<Place>): Place? {
    var best: Place? = null; var bd = Float.MAX_VALUE; val r = FloatArray(1)
    for (p in places) { android.location.Location.distanceBetween(lat, lon, p.lat, p.lon, r); if (r[0] <= p.radius && r[0] < bd) { best = p; bd = r[0] } }
    return best
}
