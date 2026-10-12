package com.steptrack.offline
import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max

object ExportManager {
    fun write(c: Context, kind: String, out: OutputStream) {
        AppLog.i("Export", "start kind=$kind")
        try { writeInner(c, kind, out); AppLog.i("Export", "done kind=$kind") } catch (e: Throwable) { AppLog.e("Export", "FAILED kind=$kind", e); throw e }
    }
    private fun writeInner(c: Context, kind: String, out: OutputStream) {
        when (kind) {
            "diag" -> { Diagnostics.writeZip(c, out); return }
            "report" -> { out.write(Diagnostics.build(c).toByteArray(Charsets.UTF_8)); out.flush(); return }
            "log" -> { out.write(AppLog.text().toByteArray(Charsets.UTF_8)); out.flush(); return }
        }
        val all = kotlinx.coroutines.runBlocking { AppDb.get(c).dao().allEvents() }.groupBy { it.date }
        val pr = Prefs(c)
        val moves = kotlinx.coroutines.runBlocking { AppDb.get(c).dao().allMoves() }
        val gps = kotlinx.coroutines.runBlocking { AppDb.get(c).dao().allGps() }
        val types = classifyMoves(gps, all.values.flatten().map { it.timestamp })
        val places = kotlinx.coroutines.runBlocking { AppDb.get(c).dao().allPlaces() }
        when (kind) { "csv" -> csv(all, pr, out); "trackcsv" -> trackCsv(all, gps, types, places, out); "kml" -> kml(gps, types, places, out); "json" -> json(all, pr, out, moves, gps, types, places); else -> pdf(all, pr, out) }
    }
    private fun trackCsv(all: Map<String, List<StepEvent>>, gps: List<GpsPoint>, types: List<Int>, places: List<Place>, out: OutputStream) {
        val tf = SimpleDateFormat("HH:mm:ss", Locale.US); val w = out.writer()
        w.write("date,time,timestamp,lat,lon,x_pos,y_pos,speed_kmh,heading_degrees,direction_bucket,accuracy_m,distance_cumulative_m,steps_total,movement_type,altitude_m,place\n")
        val st = all.values.flatten().map { it.timestamp }.sorted(); var k = 0
        for ((gi, g) in gps.withIndex()) {
            while (k < st.size && st[k] <= g.timestamp) k++
            w.write("${g.date},${tf.format(Date(g.timestamp))},${g.timestamp},${g.lat},${g.lon},${"%.1f".format(Locale.US, g.x)},${"%.1f".format(Locale.US, g.y)},${"%.1f".format(Locale.US, g.speed * 3.6f)},${"%.1f".format(Locale.US, g.heading)},${bucketOf(g.heading)},${"%.0f".format(Locale.US, g.accuracy)},${"%.1f".format(Locale.US, g.distance)},$k,${MOVE_NAMES[types[gi]]},${altStr(g.alt)},${csvQ(placeFor(g.lat, g.lon, places)?.name ?: "")}\n")
        }
        w.flush()
    }
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun csvQ(s: String) = if (s.contains(',') || s.contains('"')) "\"" + s.replace("\"", "\"\"") + "\"" else s
    /** Google My Maps / Google Earth file: one coloured line per movement-type run + trip start/end markers named after your places. */
    private fun kml(gps: List<GpsPoint>, types: List<Int>, places: List<Place>, out: OutputStream) {
        val tf = SimpleDateFormat("HH:mm", Locale.US)
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val cols = listOf("ff9e9e9e", "ff50af4c", "ff0098ff", "ffd4bc00", "ff3bebff", "ff3643f4")
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>StepTrack</name>\n")
        cols.forEachIndexed { i, c -> sb.append("<Style id=\"s$i\"><LineStyle><color>$c</color><width>4</width></LineStyle></Style>\n") }
        fun co(g: GpsPoint) = "${g.lon},${g.lat}"
        for ((d, idxs) in gps.indices.groupBy { gps[it].date }) {
            sb.append("<Folder><name>$d</name>\n")
            var s = 0
            while (s < idxs.size) {
                var e = s
                while (e + 1 < idxs.size && types[idxs[e + 1]] == types[idxs[s]] && gps[idxs[e + 1]].timestamp - gps[idxs[e]].timestamp <= 180000) e++
                val pre = if (s > 0 && gps[idxs[s]].timestamp - gps[idxs[s - 1]].timestamp <= 180000) listOf(idxs[s - 1]) else emptyList()
                val run = pre + idxs.subList(s, e + 1)
                if (run.size >= 2) {
                    val a = gps[idxs[s]]; val b = gps[idxs[e]]; val t = types[idxs[s]]
                    sb.append("<Placemark><name>${esc(MOVE_NAMES[t])} ${tf.format(Date(a.timestamp))}-${tf.format(Date(b.timestamp))}</name><styleUrl>#s$t</styleUrl>")
                    sb.append("<TimeSpan><begin>${iso.format(Date(a.timestamp))}</begin><end>${iso.format(Date(b.timestamp))}</end></TimeSpan>")
                    sb.append("<LineString><tessellate>1</tessellate><coordinates>${run.joinToString(" ") { co(gps[it]) }}</coordinates></LineString></Placemark>\n")
                }
                s = e + 1
            }
            var t0 = 0
            for (k in 1..idxs.size) if (k == idxs.size || gps[idxs[k]].timestamp - gps[idxs[k - 1]].timestamp > 180000) {
                for ((label, g) in listOf("Start" to gps[idxs[t0]], "End" to gps[idxs[k - 1]])) {
                    val nm = placeFor(g.lat, g.lon, places)?.name
                    sb.append("<Placemark><name>${esc(label + " " + tf.format(Date(g.timestamp)) + (if (nm != null) ": $nm" else ""))}</name><Point><coordinates>${co(g)}</coordinates></Point></Placemark>\n")
                }
                t0 = k
            }
            sb.append("</Folder>\n")
        }
        sb.append("</Document></kml>\n"); out.write(sb.toString().toByteArray(Charsets.UTF_8)); out.flush()
    }
    private fun altStr(a: Float) = if (a <= -9000f) "" else "%.1f".format(Locale.US, a)
    private fun csv(all: Map<String, List<StepEvent>>, pr: Prefs, out: OutputStream) {
        val tf = SimpleDateFormat("HH:mm:ss", Locale.US); val w = out.writer()
        w.write("date,time,step_number,heading_degrees,direction_bucket,x_pos,y_pos,distance_cumulative,altitude_m\n")
        for ((d, ev) in all) ev.forEachIndexed { i, e ->
            w.write("$d,${tf.format(Date(e.timestamp))},${i + 1},${"%.1f".format(Locale.US, e.heading)},${e.directionBucket},${"%.2f".format(Locale.US, e.x)},${"%.2f".format(Locale.US, e.y)},${"%.2f".format(Locale.US, (i + 1) * pr.stride)},${altStr(e.alt)}\n")
        }
        w.flush()
    }
    private fun json(all: Map<String, List<StepEvent>>, pr: Prefs, out: OutputStream, moves: List<MovePoint>, gps: List<GpsPoint>, types: List<Int>, places: List<Place>) {
        val sums = JSONArray(); val evs = JSONArray()
        for ((d, ev) in all) {
            val s = buildSummary(d, ev, pr.stride, pr.weight)
            sums.put(JSONObject().put("date", d).put("total_steps", s.totalSteps).put("distance_m", s.distanceM).put("calories", s.calories)
                .put("time_active_minutes", s.activeMinutes).put("dominant_direction", s.dominantDirection)
                .put("direction_counts", JSONObject(s.bucketCountsJson)).put("path", JSONArray(s.pathJson)))
            ev.forEach { evs.put(JSONObject().put("timestamp", it.timestamp).put("date", it.date).put("steps", 1).put("alt_m", it.alt.toDouble()).put("heading", it.heading)
                .put("direction_bucket", it.directionBucket).put("x_pos", it.x).put("y_pos", it.y)) }
        }
        out.writer().apply { write(JSONObject().put("daily_summary", sums).put("step_events", evs).put("gps_points", JSONArray().also { a -> gps.forEachIndexed { gi, it -> a.put(JSONObject().put("movement_type", MOVE_NAMES[types[gi]]).put("place", placeFor(it.lat, it.lon, places)?.name ?: "").put("alt_m", it.alt.toDouble()).put("timestamp", it.timestamp).put("date", it.date).put("lat", it.lat).put("lon", it.lon).put("accuracy_m", it.accuracy).put("speed_mps", it.speed).put("heading", it.heading).put("x_pos", it.x).put("y_pos", it.y).put("distance_cumulative", it.distance)) } }).put("movement_points", JSONArray().also { a -> moves.forEach { a.put(JSONObject().put("timestamp", it.timestamp).put("date", it.date).put("heading", it.heading).put("speed_mps", it.speed).put("x_pos", it.x).put("y_pos", it.y).put("distance_cumulative", it.distance)) } }).toString(2)); flush() }
    }
    private fun pdf(all: Map<String, List<StepEvent>>, pr: Prefs, out: OutputStream) {
        val doc = PdfDocument(); var n = 1
        val p = Paint().apply { textSize = 13f }
        val line = Paint().apply { color = 0xFF0088CC.toInt(); strokeWidth = 2f; style = Paint.Style.STROKE }
        val bar = Paint().apply { color = 0xFF4488FF.toInt() }
        val days = if (all.isEmpty()) mapOf("No data" to emptyList<StepEvent>()) else all
        for ((d, ev) in days) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, n++).create()); val cv = page.canvas
            p.textSize = 20f; cv.drawText("StepTrack - $d", 40f, 50f, p); p.textSize = 13f
            val s = buildSummary(d, ev, pr.stride, pr.weight)
            listOf("Steps: ${s.totalSteps}", "Distance: ${"%.0f".format(s.distanceM)} m", "Calories: ${"%.0f".format(s.calories)} kcal",
                "Active: ${s.activeMinutes} min", "Dominant direction: ${s.dominantDirection}").forEachIndexed { i, t -> cv.drawText(t, 40f, 85f + i * 20f, p) }
            if (ev.isNotEmpty()) {
                val sc = 140f / max(5f, ev.maxOf { max(abs(it.x), abs(it.y)) }); val path = android.graphics.Path(); path.moveTo(300f, 350f)
                ev.forEach { path.lineTo(300f + it.x * sc, 350f - it.y * sc) }
                cv.drawText("Relative path (N up)", 40f, 190f, p); cv.drawPath(path, line)
                val hrs = IntArray(24); ev.forEach { hrs[Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).hour]++ }
                val mx = max(1, hrs.max()); cv.drawText("Steps per hour", 40f, 590f, p)
                hrs.forEachIndexed { h, v -> cv.drawRect(40f + h * 21f, 760f - 150f * v / mx, 40f + h * 21f + 16f, 760f, bar) }
            }
            doc.finishPage(page)
        }
        doc.writeTo(out); doc.close()
    }
}
