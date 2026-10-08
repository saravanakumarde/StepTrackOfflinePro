package com.steptrack.offline
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Daily text-file backup in Documents/StepTrack/daily (public storage, so it survives uninstall) + restore after reinstall. */
object AutoBackup {
    fun dir() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "StepTrack/daily")
    fun canWrite(c: Context) = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun arr(l: List<JSONArray>) = "[\n" + l.joinToString(",\n") + "\n]"

    suspend fun saveDay(c: Context, d: String): Boolean {
        if (!canWrite(c)) { if (AppLog.get("backup_noaccess") % 50 == 0L) AppLog.w("Backup", "storage access not allowed, daily file not saved"); AppLog.count("backup_noaccess"); LiveState.backupStatus.value = "Storage access not allowed"; return false }
        return try {
            val dao = AppDb.get(c).dao(); val pr = Prefs(c)
            val ev = dao.events(d); val gps = dao.gps(d); val places = dao.allPlaces()
            if (ev.isEmpty() && gps.isEmpty()) return true
            val types = classifyMoves(gps, ev.map { it.timestamp }); val dist = distByType(gps, types); val s = buildSummary(d, ev, pr.stride, pr.weight)
            val byType = JSONObject(); for (i in 1 until MOVE_NAMES.size) byType.put(MOVE_NAMES[i], dist[i].toDouble())
            val es = elevStats(altSeries(ev, gps).map { it.second }); val elevJ = JSONObject()
            es?.let { elevJ.put("min_m", it.min.toDouble()).put("max_m", it.max.toDouble()).put("ascent_m", it.ascent.toDouble()).put("descent_m", it.descent.toDouble()) }
            val settings = JSONObject().put("goal", pr.goal).put("stride_m", pr.stride.toDouble()).put("weight_kg", pr.weight.toDouble())
            val summary = JSONObject().put("total_steps", s.totalSteps).put("walk_distance_m", s.distanceM.toDouble()).put("calories", s.calories.toDouble())
                .put("active_minutes", s.activeMinutes).put("dominant_direction", s.dominantDirection).put("direction_counts", JSONObject(s.bucketCountsJson))
            val evRows = ev.map { JSONArray().put(it.timestamp).put(it.heading.toDouble()).put(it.directionBucket).put(it.x.toDouble()).put(it.y.toDouble()).put(it.alt.toDouble()) }
            val gpsRows = gps.mapIndexed { i, g -> JSONArray().put(g.timestamp).put(g.lat).put(g.lon).put(g.accuracy.toDouble()).put(g.speed.toDouble())
                .put(g.heading.toDouble()).put(g.x.toDouble()).put(g.y.toDouble()).put(g.distance.toDouble()).put(MOVE_NAMES[types[i]]).put(g.alt.toDouble()).put(placeFor(g.lat, g.lon, places)?.name ?: "") }
            val text = "{\n\"app\": \"StepTrackOfflinePro\",\n\"format\": 1,\n\"date\": \"$d\",\n\"saved_at\": ${System.currentTimeMillis()},\n" +
                "\"settings\": $settings,\n\"summary\": $summary,\n\"movement_distance_by_type_m\": $byType,\n\"elevation\": $elevJ,\n" +
                "\"step_events_columns\": \"timestamp,heading_deg,direction_bucket,x_m,y_m,alt_m\",\n\"step_events\": ${arr(evRows)},\n" +
                "\"gps_columns\": \"timestamp,lat,lon,accuracy_m,speed_mps,heading_deg,x_m,y_m,distance_cumulative_m,movement_type,alt_m,place\",\n\"gps_points\": ${arr(gpsRows)}\n}\n"
            val dir = dir(); dir.mkdirs()
            val tmp = File(dir, "StepTrack_$d.tmp"); tmp.writeText(text)
            val f = File(dir, "StepTrack_$d.txt"); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            LiveState.backupStatus.value = "Saved ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())} in Documents/StepTrack/daily"
            true
        } catch (e: Exception) { AppLog.e("Backup", "saveDay $d failed", e); LiveState.backupStatus.value = "Save failed: ${e.message}"; false }
    }

    suspend fun savePlaces(c: Context) {
        if (!canWrite(c)) return
        try {
            val a = JSONArray(); AppDb.get(c).dao().allPlaces().forEach { a.put(JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon).put("radius", it.radius.toDouble())) }
            val root = dir().parentFile ?: return; root.mkdirs()
            File(root, "places.txt").writeText("{\n\"app\": \"StepTrackOfflinePro\",\n\"places\": $a\n}\n")
        } catch (_: Exception) {}
    }

    private suspend fun restorePlaces(c: Context): Int {
        val f = File(dir().parentFile, "places.txt"); if (!f.exists()) return 0
        return try {
            val dao = AppDb.get(c).dao(); val have = dao.allPlaces(); val a = JSONObject(f.readText()).getJSONArray("places"); var n = 0
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i); val nm = o.getString("name"); val la = o.getDouble("lat"); val lo = o.getDouble("lon")
                if (have.any { it.name == nm && Math.abs(it.lat - la) < 1e-6 && Math.abs(it.lon - lo) < 1e-6 }) continue
                dao.insertPlace(Place(name = nm, lat = la, lon = lo, radius = o.getDouble("radius").toFloat())); n++
            }
            n
        } catch (_: Exception) { 0 }
    }

    suspend fun saveAll(c: Context): Boolean {
        var ok = true; AppLog.i("Backup", "saveAll start")
        savePlaces(c)
        AppDb.get(c).dao().allDates().forEach { if (!saveDay(c, it)) ok = false }
        AppLog.i("Backup", "saveAll finished ok=$ok"); return ok
    }

    suspend fun restore(c: Context): String {
        if (!canWrite(c)) return "Storage access not allowed"
        val nPl = restorePlaces(c)
        val files = dir().listFiles { f -> f.name.startsWith("StepTrack_") && f.name.endsWith(".txt") }?.sortedBy { it.name }
        if (files.isNullOrEmpty()) return "No saved day files found; restored $nPl place(s)"
        val dao = AppDb.get(c).dao(); val pr = Prefs(c); var nEv = 0; var nGps = 0; var last: JSONObject? = null; val dates = mutableSetOf<String>()
        for (f in files) try {
            val j = JSONObject(f.readText()); if (j.optString("app") != "StepTrackOfflinePro") continue
            val d = j.getString("date"); dates.add(d)
            val haveE = dao.stepTs(d).toHashSet(); val ea = j.optJSONArray("step_events") ?: JSONArray(); val el = ArrayList<StepEvent>()
            for (i in 0 until ea.length()) { val a = ea.getJSONArray(i); val ts = a.getLong(0); if (ts in haveE) continue
                el.add(StepEvent(timestamp = ts, date = d, heading = a.getDouble(1).toFloat(), directionBucket = a.getString(2), x = a.getDouble(3).toFloat(), y = a.getDouble(4).toFloat(), alt = if (a.length() > 5) a.getDouble(5).toFloat() else NO_ALT)) }
            dao.insertEvents(el); nEv += el.size
            val haveG = dao.gpsTs(d).toHashSet(); val ga = j.optJSONArray("gps_points") ?: JSONArray(); val gl = ArrayList<GpsPoint>()
            for (i in 0 until ga.length()) { val a = ga.getJSONArray(i); val ts = a.getLong(0); if (ts in haveG) continue
                gl.add(GpsPoint(timestamp = ts, date = d, lat = a.getDouble(1), lon = a.getDouble(2), accuracy = a.getDouble(3).toFloat(), speed = a.getDouble(4).toFloat(),
                    heading = a.getDouble(5).toFloat(), x = a.getDouble(6).toFloat(), y = a.getDouble(7).toFloat(), distance = a.getDouble(8).toFloat(), alt = if (a.length() > 10) a.getDouble(10).toFloat() else NO_ALT)) }
            dao.insertGpsList(gl); nGps += gl.size; last = j
        } catch (e: Exception) { AppLog.e("Backup", "restore of ${f.name} failed", e) }
        last?.optJSONObject("settings")?.let { s -> pr.goal = s.optInt("goal", pr.goal); pr.stride = s.optDouble("stride_m", pr.stride.toDouble()).toFloat(); pr.weight = s.optDouble("weight_kg", pr.weight.toDouble()).toFloat() }
        dates.forEach { dao.upsert(buildSummary(it, dao.events(it), pr.stride, pr.weight)) }
        AppLog.i("Backup", "restore: $nEv steps, $nGps gps points, ${files.size} files, $nPl places"); return "Restored $nEv step records and $nGps GPS points from ${files.size} file(s), $nPl place(s)"
    }
}
