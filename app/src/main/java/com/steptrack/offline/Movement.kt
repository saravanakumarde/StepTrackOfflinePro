package com.steptrack.offline
import androidx.compose.ui.graphics.Color
import kotlin.math.max

val MOVE_NAMES = listOf("Still", "Walking", "Running", "Slow vehicle / bike", "Automobile", "Fast (train / highway)", "Train (between stations)")
val MOVE_COLORS = listOf(Color(0xFF9E9E9E), Color(0xFF4CAF50), Color(0xFFFF9800), Color(0xFF00BCD4), Color(0xFFFFEB3B), Color(0xFFF44336), Color(0xFF9C27B0))

/**
 * Movement type per GPS point. Uses the speed measured over a +-15 s window of positions (not the noisy Doppler speed)
 * plus steps seen in that window. On foot = steps present, or slow and steady (< 8 km/h, no spikes) even if the step sensor was silent.
 */
fun classifyMoves(gps: List<GpsPoint>, stepTs: List<Long>, stations: List<Place> = emptyList()): List<Int> {
    val st = stepTs.sorted(); var lo = 0; var hi = 0
    val n = gps.size; var a = 0; var b = 0
    val res = ArrayList<Int>(n)
    for (i in 0 until n) {
        val g = gps[i]
        while (hi < st.size && st[hi] <= g.timestamp) hi++
        while (lo < hi && st[lo] <= g.timestamp - 15000) lo++
        val steps = hi - lo
        while (a < n && gps[a].timestamp < g.timestamp - 15000) a++
        if (b < i) b = i
        while (b + 1 < n && gps[b + 1].timestamp <= g.timestamp + 15000) b++
        var vWin = g.speed; var vMax = g.speed
        if (b > a) {
            val dt = (gps[b].timestamp - gps[a].timestamp) / 1000f
            if (dt >= 5f) vWin = max(0f, gps[b].distance - gps[a].distance) / dt
            for (k in a..b) if (gps[k].speed > vMax) vMax = gps[k].speed
        }
        val onFoot = steps >= 4 || (vWin < 2.3f && vMax < 4.5f)
        res.add(when {
            vWin < 0.4f && steps < 2 -> 0
            onFoot && vWin < 5f -> if (vWin < 2.4f) 1 else 2
            vWin < 7f -> 3
            vWin < 28f -> 4
            else -> 5
        })
    }
    if (stations.isNotEmpty()) markTrains(gps, res, stations)
    return res
}

/** A vehicle run (stops under 3 min allowed) that starts and ends within 600 m of a station and is at least 1 km long becomes Train. */
private fun markTrains(gps: List<GpsPoint>, res: MutableList<Int>, stations: List<Place>) {
    val r = FloatArray(1)
    fun nearStation(g: GpsPoint): Boolean { for (p in stations) { android.location.Location.distanceBetween(g.lat, g.lon, p.lat, p.lon, r); if (r[0] <= 600f) return true }; return false }
    var i = 0
    while (i < gps.size) {
        if (res[i] !in 3..5) { i++; continue }
        val start = i; var lastVeh = i; var k = i + 1
        while (k < gps.size && gps[k].timestamp - gps[k - 1].timestamp <= 180_000L && gps[k].date == gps[start].date && (res[k] == 0 || res[k] in 3..6)) { if (res[k] in 3..5) lastVeh = k; k++ }
        if (gps[lastVeh].distance - gps[start].distance >= 1000f && nearStation(gps[start]) && nearStation(gps[lastVeh])) for (q in start..lastVeh) if (res[q] in 3..5) res[q] = 6
        i = maxOf(k, lastVeh + 1)
    }
}

/** Metres per step measured from GPS: 20 s windows that are entirely walking/running with at least 8 steps. Null if not enough data. */
fun measureStride(gps: List<GpsPoint>, events: List<StepEvent>): Pair<Float, Int>? {
    if (gps.size < 3 || events.isEmpty()) return null
    val ts = events.map { it.timestamp }.sorted().toLongArray()
    val types = classifyMoves(gps, ts.toList())
    fun upper(t: Long): Int { var lo = 0; var hi = ts.size; while (lo < hi) { val m = (lo + hi) ushr 1; if (ts[m] <= t) lo = m + 1 else hi = m }; return lo }
    var sd = 0.0; var ss = 0L; var i = 0
    while (i < gps.size) {
        val t0 = gps[i].timestamp; var j = i
        while (j + 1 < gps.size && gps[j + 1].timestamp < t0 + 20000 && gps[j + 1].date == gps[i].date) j++
        if (j - i >= 2 && (i..j).all { types[it] == 1 || types[it] == 2 }) {
            val d = gps[j].distance - gps[i].distance
            val s = upper(gps[j].timestamp) - upper(gps[i].timestamp - 1)
            if (d > 5f && s >= 8) { sd += d; ss += s }
        }
        i = j + 1
    }
    return if (ss >= 200) Pair((sd / ss).toFloat(), ss.toInt()) else null
}

fun distByType(gps: List<GpsPoint>, types: List<Int>): FloatArray {
    val a = FloatArray(MOVE_NAMES.size)
    for (i in 1 until gps.size) { val d = gps[i].distance - gps[i - 1].distance; if (d > 0f) a[types[i]] += d }
    return a
}

data class ElevStats(val min: Float, val max: Float, val ascent: Float, val descent: Float, val net: Float)

/** Total ascent/descent with a hysteresis so sensor noise is not counted as climbing (1.5 m with barometer, 6 m for GPS-only altitude). */
fun elevStats(a: List<Float>, hyst: Float = 1.5f): ElevStats? {
    if (a.isEmpty()) return null
    // GPS-only altitude (large hysteresis): 9-sample median first, so single noisy readings are not counted as climbing
    val b = if (hyst >= 5f && a.size >= 9) List(a.size) { i -> a.subList(maxOf(0, i - 4), minOf(a.size, i + 5)).sorted().let { it[it.size / 2] } } else a
    var ref = b[0]; var up = 0f; var down = 0f
    for (v in b) { if (v - ref >= hyst) { up += v - ref; ref = v } else if (ref - v >= hyst) { down += ref - v; ref = v } }
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

/** The place containing the point, else the nearest place within 150 m (trip start/end labels). */
fun placeNear(lat: Double, lon: Double, places: List<Place>): Place? {
    placeFor(lat, lon, places)?.let { return it }
    val r = FloatArray(1); var best: Place? = null; var bd = 150f
    for (p in places) { android.location.Location.distanceBetween(lat, lon, p.lat, p.lon, r); if (r[0] < bd) { bd = r[0]; best = p } }
    return best
}
private val STATION_RE = Regex("(?i)\\b(bf|hbf|bahnhof|station|gleis)\\b")
fun isStation(p: Place, ids: Set<String>) = p.id.toString() in ids || STATION_RE.containsMatchIn(p.name)
fun stationPlaces(places: List<Place>, ids: Set<String>) = places.filter { isStation(it, ids) }
