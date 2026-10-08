package com.steptrack.offline
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import kotlinx.coroutines.runBlocking
import java.io.BufferedOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Builds the full diagnostic report (device, permissions, sensors, data quality, GPS, backup, log) and the ZIP package. */
object Diagnostics {
    private fun tf(ts: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ts))
    private fun f1(v: Float) = "%.1f".format(Locale.US, v)
    private fun f1(v: Double) = "%.1f".format(Locale.US, v)
    private fun f2(v: Float) = "%.2f".format(Locale.US, v)
    private fun prop(k: String): String = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java).invoke(null, k, "") as String
    } catch (_: Throwable) { "" }

    private fun pctile(sorted: List<Float>, p: Double): Float = if (sorted.isEmpty()) 0f else sorted[((sorted.size - 1) * p).toInt()]
    private fun hist(vals: List<Float>, edges: List<Float>, unit: String): String {
        if (vals.isEmpty()) return "  (no data)"
        val cnt = IntArray(edges.size + 1)
        vals.forEach { v -> var k = 0; while (k < edges.size && v >= edges[k]) k++; cnt[k]++ }
        return (0..edges.size).joinToString("\n") { k ->
            val lab = when (k) { 0 -> "< ${edges[0]}$unit"; edges.size -> ">= ${edges.last()}$unit"; else -> "${edges[k - 1]} - ${edges[k]}$unit" }
            "  %-18s %7d  (%.1f%%)".format(Locale.US, lab, cnt[k], 100.0 * cnt[k] / vals.size)
        }
    }
    private fun angDiff(a: Float, b: Float): Float { var d = (a - b) % 360f; if (d > 180f) d -= 360f; if (d < -180f) d += 360f; return d }
    private fun upper(sorted: LongArray, t: Long): Int { var lo = 0; var hi = sorted.size; while (lo < hi) { val m = (lo + hi) ushr 1; if (sorted[m] <= t) lo = m + 1 else hi = m }; return lo }

    fun build(c: Context): String {
        val sb = StringBuilder()
        fun l(s: String) { sb.append(s).append('\n') }
        fun sec(t: String, body: () -> Unit) { sb.append("\n==== ").append(t).append(" ====\n"); try { body() } catch (e: Throwable) { l("(section failed: $e)") } }
        val dao = AppDb.get(c).dao(); val pr = Prefs(c)
        val events = runBlocking { dao.allEvents() }.sortedBy { it.timestamp }
        val gps = runBlocking { dao.allGps() }.sortedBy { it.timestamp }
        val moves = runBlocking { dao.allMoves() }
        val places = runBlocking { dao.allPlaces() }
        val stepTs = LongArray(events.size) { events[it].timestamp }
        val types = classifyMoves(gps, events.map { it.timestamp })
        val now = System.currentTimeMillis()

        l("STEPTRACK OFFLINE PRO - DIAGNOSTIC REPORT"); l("Generated: ${tf(now)}  (epoch $now)  timezone ${TimeZone.getDefault().id}")
        l("Purpose: complete state, data-quality analysis and app log for improving the app. Contains location data (GPS points, place coordinates).")

        sec("1. APP") {
            val pi = c.packageManager.getPackageInfo(c.packageName, 0)
            @Suppress("DEPRECATION") val vc = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            l("package=${c.packageName}  versionName=${pi.versionName}  versionCode=$vc")
            l("firstInstall=${tf(pi.firstInstallTime)}  lastUpdate=${tf(pi.lastUpdateTime)}")
            l("locale=${Locale.getDefault()}")
        }
        sec("2. DEVICE") {
            l("manufacturer=${Build.MANUFACTURER}  brand=${Build.BRAND}  model=${Build.MODEL}  device=${Build.DEVICE}  hardware=${Build.HARDWARE}")
            if (Build.VERSION.SDK_INT >= 31) l("soc=${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            l("android=${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})  securityPatch=${Build.VERSION.SECURITY_PATCH}")
            l("buildDisplay=${Build.DISPLAY}"); l("fingerprint=${Build.FINGERPRINT}")
            listOf("ro.mi.os.version.name", "ro.mi.os.version.code", "ro.miui.ui.version.name", "ro.miui.ui.version.code", "ro.build.version.incremental").forEach { k -> val v = prop(k); if (v.isNotEmpty()) l("$k=$v") }
            val dm = c.resources.displayMetrics; l("display=${dm.widthPixels}x${dm.heightPixels} density=${dm.density}")
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager; val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
            l("ram total=${mi.totalMem / 1048576} MB  avail=${mi.availMem / 1048576} MB  lowMemory=${mi.lowMemory}")
            val sf = StatFs(Environment.getDataDirectory().path); l("data storage free=${sf.availableBytes / 1048576} MB of ${sf.totalBytes / 1048576} MB")
            val rt = Runtime.getRuntime(); l("app heap used=${(rt.totalMemory() - rt.freeMemory()) / 1048576} MB  max=${rt.maxMemory() / 1048576} MB")
        }
        sec("3. PERMISSIONS AND BACKGROUND RESTRICTIONS") {
            val req = c.packageManager.getPackageInfo(c.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions ?: emptyArray()
            req.sorted().forEach { p ->
                val st = if (p == "android.permission.MANAGE_EXTERNAL_STORAGE") (if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) "GRANTED" else "DENIED")
                else if (c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) "GRANTED" else "not granted"
                l("  ${p.removePrefix("android.permission.")}: $st")
            }
            val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
            l("ignoringBatteryOptimizations=${pm.isIgnoringBatteryOptimizations(c.packageName)}  powerSaveMode=${pm.isPowerSaveMode}  deviceIdle=${pm.isDeviceIdleMode}  screenOn=${pm.isInteractive}")
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (Build.VERSION.SDK_INT >= 28) l("backgroundRestricted=${am.isBackgroundRestricted}")
            if (Build.VERSION.SDK_INT >= 28) { val b = (c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).appStandbyBucket
                l("standbyBucket=$b (10 active, 20 working set, 30 frequent, 40 rare, 45 restricted)") }
            l("notificationsEnabled=${(c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()}")
            val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            l("gpsProviderEnabled=${lm.isProviderEnabled(LocationManager.GPS_PROVIDER)}")
            val bi = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (bi != null) l("battery=${100 * bi.getIntExtra("level", 0) / bi.getIntExtra("scale", 100).coerceAtLeast(1)}%  status=${bi.getIntExtra("status", 0)}  temp=${bi.getIntExtra("temperature", 0) / 10.0}C")
        }
        sec("4. SETTINGS (preferences)") {
            c.getSharedPreferences("st", 0).all.toSortedMap().forEach { (k, v) -> l("  $k = $v") }
            l("  sessionStart(readable)=${if (pr.sessionStart > 0) tf(pr.sessionStart) else "-"}  moveSession(readable)=${if (pr.moveSession > 0) tf(pr.moveSession) else "-"}")
        }
        sec("5. SENSORS ON THIS DEVICE") {
            val sm = c.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            fun usesS(n: String, t: Int) = l("  app uses $n: ${sm.getDefaultSensor(t)?.name ?: "NOT AVAILABLE"}")
            usesS("step counter", Sensor.TYPE_STEP_COUNTER); usesS("step detector", Sensor.TYPE_STEP_DETECTOR); usesS("accelerometer", Sensor.TYPE_ACCELEROMETER)
            usesS("rotation vector", Sensor.TYPE_ROTATION_VECTOR); usesS("magnetometer", Sensor.TYPE_MAGNETIC_FIELD); usesS("barometer", Sensor.TYPE_PRESSURE); usesS("gyroscope", Sensor.TYPE_GYROSCOPE)
            l("  all sensors (name | vendor | type | power mA | resolution | maxRange | minDelay us | fifo | wakeUp):")
            sm.getSensorList(Sensor.TYPE_ALL).sortedBy { it.type }.forEach { s ->
                l("  ${s.name} | ${s.vendor} | ${s.stringType} | ${s.power} | ${s.resolution} | ${s.maximumRange} | ${s.minDelay} | ${s.fifoMaxEventCount} | ${s.isWakeUpSensor}") }
        }
        sec("6. RUNTIME, RELIABILITY AND LIFETIME COUNTERS") {
            val snap = AppLog.snapshot().toSortedMap()
            if (snap.isEmpty()) l("  (no counters yet)") else snap.forEach { (k, v) -> l("  $k = $v") }
            l("  LiveState: heading=${f1(LiveState.heading.value)}  compassAccuracy=${LiveState.accuracy.value} (0 unreliable..3 high)  stepsToday=${LiveState.steps.value}  moveOn=${LiveState.moveOn.value}  gps='${LiveState.gpsStatus.value}'  speed=${f2(LiveState.speed.value)} m/s")
            l("  altitude=${LiveState.alt.value}  altAnchoredToGps=${LiveState.altAnchored.value}  barometerFound=${LiveState.baro.value}  lastFix=${LiveState.fix.value?.let { tf(it.third) } ?: "none"}")
            if (Build.VERSION.SDK_INT >= 30) {
                l("  Why the system stopped this app's process before (most recent first):")
                val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val names = mapOf(1 to "EXIT_SELF", 2 to "SIGNALED", 3 to "LOW_MEMORY", 4 to "CRASH", 5 to "CRASH_NATIVE", 6 to "ANR", 7 to "INIT_FAILURE", 8 to "PERMISSION_CHANGE",
                    9 to "EXCESSIVE_RESOURCE_USAGE", 10 to "USER_REQUESTED", 11 to "USER_STOPPED", 12 to "DEPENDENCY_DIED", 13 to "OTHER", 14 to "FREEZER", 15 to "PACKAGE_STATE_CHANGE", 16 to "PACKAGE_UPDATED")
                val ex = am.getHistoricalProcessExitReasons(c.packageName, 0, 30)
                if (ex.isEmpty()) l("   (none recorded)")
                ex.forEach { l("   ${tf(it.timestamp)}  ${names[it.reason] ?: it.reason}  status=${it.status}  importance=${it.importance}  ${it.description ?: ""}") }
            }
        }
        sec("7. DATA OVERVIEW") {
            l("rows: step_events=${events.size}  gps_points=${gps.size}  movement_points=${moves.size}  places=${places.size}")
            val dbf = c.getDatabasePath("steps.db"); l("database file=${dbf.length() / 1024} KB  (+wal ${java.io.File(dbf.path + "-wal").let { if (it.exists()) it.length() / 1024 else 0 }} KB)")
            if (events.isNotEmpty()) l("step range: ${tf(events.first().timestamp)}  ->  ${tf(events.last().timestamp)}")
            if (gps.isNotEmpty()) l("gps range:  ${tf(gps.first().timestamp)}  ->  ${tf(gps.last().timestamp)}")
            val dates = (events.map { it.date } + gps.map { it.date }).distinct().sorted()
            l("per-day: date | steps | active min | first step | last step | gps pts | gps dist m | avg gps acc m | % steps with altitude")
            val evBy = events.groupBy { it.date }; val gBy = gps.groupBy { it.date }
            dates.forEach { d ->
                val e = evBy[d] ?: emptyList(); val g = gBy[d] ?: emptyList()
                val gd = if (g.size > 1) g.zipWithNext().sumOf { (a, b) -> (b.distance - a.distance).coerceAtLeast(0f).toDouble() } else 0.0
                l("  $d | ${e.size} | ${e.map { it.timestamp / 60000 }.distinct().size} | ${e.firstOrNull()?.let { tf(it.timestamp).substring(11) } ?: "-"} | ${e.lastOrNull()?.let { tf(it.timestamp).substring(11) } ?: "-"} | ${g.size} | ${f1(gd)} | ${if (g.isEmpty()) "-" else f1(g.map { it.accuracy }.average())} | ${if (e.isEmpty()) "-" else (100 * e.count { it.alt > -9000f } / e.size).toString()}")
            }
            l("places (name | radius m | lat | lon):"); places.forEach { l("  ${it.name} | ${it.radius.toInt()} | ${it.lat} | ${it.lon}") }
        }
        sec("8. STEP DETECTION QUALITY") {
            if (events.size < 2) { l("  not enough step data"); return@sec }
            val dts = ArrayList<Float>(); val hj = ArrayList<Float>()
            var sameInstant = 0
            for (i in 1 until events.size) {
                val a = events[i - 1]; val b = events[i]; if (a.date != b.date) continue
                val dt = (b.timestamp - a.timestamp).toFloat(); dts.add(dt); if (dt <= 5f) sameInstant++
                if (dt < 2000f) hj.add(abs(angDiff(b.heading, a.heading)))
            }
            l("interval between consecutive steps (ms):"); l(hist(dts, listOf(150f, 300f, 450f, 700f, 1000f, 2000f, 10000f), " ms"))
            l("steps recorded within 5 ms of the previous step (batched/burst delivery): $sameInstant (${"%.1f".format(Locale.US, 100.0 * sameInstant / dts.size.coerceAtLeast(1))}%)")
            val walk = dts.filter { it in 250f..1500f }.sorted(); if (walk.isNotEmpty()) l("typical walking cadence: median interval ${f1(pctile(walk, 0.5))} ms = ${f1(60000f / pctile(walk, 0.5))} steps/min")
            l("heading change between consecutive steps (degrees, steps <2 s apart):"); l(hist(hj, listOf(5f, 15f, 30f, 60f, 90f), " deg"))
            if (hj.isNotEmpty()) l("mean heading change per step: ${f1(hj.average())} deg  (large values = compass noise or phone orientation changes)")
            val perHour = IntArray(24); events.forEach { perHour[java.time.Instant.ofEpochMilli(it.timestamp).atZone(java.time.ZoneId.systemDefault()).hour]++ }
            l("steps by hour of day: " + perHour.indices.joinToString(" ") { "$it:${perHour[it]}" })
            l("steps by direction bucket: " + BUCKETS.joinToString("  ") { b -> "$b=${events.count { it.directionBucket == b }}" })
            l("steps with altitude recorded: ${events.count { it.alt > -9000f }} of ${events.size}")
            val perDay = events.groupBy { it.date }.values.map { it.size }.sorted(); l("steps per day: min=${perDay.first()} median=${perDay[perDay.size / 2]} max=${perDay.last()}")
        }
        sec("9. GPS QUALITY") {
            if (gps.isEmpty()) { l("  no GPS data"); return@sec }
            l("fix accuracy (m, the app stores only fixes <= 30 m):"); l(hist(gps.map { it.accuracy }, listOf(5f, 10f, 15f, 20f, 30f), " m"))
            val spd = gps.map { it.speed * 3.6f }.sorted(); l("speed km/h: p50=${f1(pctile(spd, 0.5))} p90=${f1(pctile(spd, 0.9))} p99=${f1(pctile(spd, 0.99))} max=${f1(spd.last())}  (>200 km/h points: ${spd.count { it > 200f }})")
            val dts = ArrayList<Float>(); val gaps = ArrayList<Triple<Long, Long, Long>>(); var mism = 0; var cmp = 0; val r = FloatArray(1)
            for (i in 1 until gps.size) {
                val a = gps[i - 1]; val b = gps[i]; if (a.date != b.date) continue
                val dt = b.timestamp - a.timestamp; dts.add(dt / 1000f); if (dt > 180000) gaps.add(Triple(a.timestamp, b.timestamp, dt))
                if (dt in 500..10000) { android.location.Location.distanceBetween(a.lat, a.lon, b.lat, b.lon, r); val implied = r[0] / (dt / 1000f); cmp++; if (abs(implied - b.speed) > 3f) mism++ }
            }
            l("time between stored GPS points (s; stationary points are dropped by the app, so long gaps are normal when standing still):"); l(hist(dts, listOf(1.5f, 3f, 10f, 60f, 180f), " s"))
            l("gaps > 3 min: ${gaps.size}  total ${gaps.sumOf { it.third } / 60000} min.  longest 10:"); gaps.sortedByDescending { it.third }.take(10).forEach { l("  ${tf(it.first)} -> ${tf(it.second)}  (${it.third / 60000} min)") }
            l("reported speed vs speed implied by position change differs by > 3 m/s: $mism of $cmp comparisons")
            val dist = distByType(gps, types); l("movement type distribution (points | distance km):")
            for (i in MOVE_NAMES.indices) l("  ${MOVE_NAMES[i]}: ${types.count { it == i }} | ${f2(dist[i] / 1000f)}")
        }
        sec("10. TRIPS (3 min gap = new trip), last 40") {
            val trips = ArrayList<List<Int>>(); var cur = ArrayList<Int>()
            for (i in gps.indices) { if (cur.isNotEmpty() && gps[i].timestamp - gps[cur.last()].timestamp > 180000) { trips.add(cur); cur = ArrayList() }; cur.add(i) }
            if (cur.isNotEmpty()) trips.add(cur)
            l("total trips: ${trips.size}")
            trips.takeLast(40).forEach { t ->
                val a = gps[t.first()]; val b = gps[t.last()]; val d = (b.distance - a.distance).coerceAtLeast(0f); val secs = ((b.timestamp - a.timestamp) / 1000f).coerceAtLeast(1f)
                val dom = t.groupingBy { types[it] }.eachCount().maxByOrNull { it.value }?.key ?: 0
                l("  ${tf(a.timestamp)} | ${(b.timestamp - a.timestamp) / 60000} min | ${f2(d / 1000f)} km | avg ${f1(d / secs * 3.6f)} km/h | main=${MOVE_NAMES[dom]} | ${placeFor(a.lat, a.lon, places)?.name ?: "?"} -> ${placeFor(b.lat, b.lon, places)?.name ?: "?"}")
            }
            if (places.isNotEmpty()) { l("GPS points inside each place:"); places.forEach { p -> l("  ${p.name}: ${gps.count { placeFor(it.lat, it.lon, listOf(p)) != null }}") } }
        }
        sec("11. CALIBRATION CHECKS (steps vs GPS)") {
            if (gps.size < 2 || events.isEmpty()) { l("  needs both steps and GPS data"); return@sec }
            var sumD = 0.0; var sumS = 0L; val dayD = HashMap<String, Double>(); val dayS = HashMap<String, Long>()
            for (i in 1 until gps.size) {
                val a = gps[i - 1]; val b = gps[i]; if (a.date != b.date || b.timestamp - a.timestamp > 5000 || types[i] !in 1..2) continue
                val d = (b.distance - a.distance).toDouble(); if (d <= 0) continue
                val s = (upper(stepTs, b.timestamp) - upper(stepTs, a.timestamp)).toLong()
                sumD += d; sumS += s; dayD[b.date] = (dayD[b.date] ?: 0.0) + d; dayS[b.date] = (dayS[b.date] ?: 0L) + s
            }
            l("stride length: current setting ${pr.stride} m")
            if (sumS >= 50) { l("  measured from GPS while walking/running: ${"%.3f".format(Locale.US, sumD / sumS)} m per step  (${f1(sumD)} m over $sumS steps)")
                dayD.keys.sorted().forEach { d -> val s = dayS[d] ?: 0L; if (s >= 30) l("    $d: ${"%.3f".format(Locale.US, (dayD[d] ?: 0.0) / s)} m/step ($s steps)") }
            } else l("  not enough walking with GPS to measure (need >= 50 steps with GPS on)")
            // compass heading vs GPS course on walking points
            val diffs = ArrayList<Float>()
            for (i in gps.indices) { val g = gps[i]; if (types[i] != 1 && types[i] != 2 || g.speed < 1.0f) continue
                val k = upper(stepTs, g.timestamp) - 1; if (k < 0 || g.timestamp - stepTs[k] > 3000) continue
                val dd = angDiff(events[k].heading, g.heading); if (abs(dd) > 0.05f) diffs.add(dd) }
            l("compass vs GPS course while walking (positive = compass reads clockwise of GPS course):")
            if (diffs.size >= 20) {
                val ab = diffs.map { abs(it) }.sorted(); val cm = Math.toDegrees(atan2(diffs.sumOf { sin(Math.toRadians(it.toDouble())) }, diffs.sumOf { cos(Math.toRadians(it.toDouble())) }))
                l("  samples=${diffs.size}  mean offset=${f1(cm)} deg  mean |error|=${f1(ab.average())} deg  median |error|=${f1(pctile(ab, 0.5))}  p90 |error|=${f1(pctile(ab, 0.9))}")
                l("  (samples where GPS had no bearing and the app used the compass are excluded)")
            } else l("  not enough samples (need walking with GPS bearing)")
            val es = elevStats(altSeries(events, gps).map { it.second })
            l("elevation: " + (es?.let { "min ${f1(it.min)} m, max ${f1(it.max)} m, ascent ${f1(it.ascent)} m, descent ${f1(it.descent)} m, net ${f1(it.net)} m" } ?: "no altitude data"))
        }
        sec("12. BACKUP AND STORAGE FILES") {
            l("storage access allowed=${AutoBackup.canWrite(c)}  lastStatus='${LiveState.backupStatus.value}'")
            val d = AutoBackup.dir(); val fs = d.listFiles { x -> x.name.startsWith("StepTrack_") && x.name.endsWith(".txt") }?.sortedBy { it.name } ?: emptyList()
            l("daily files in ${d.path}: ${fs.size}  total ${fs.sumOf { it.length() } / 1024} KB  newest=${fs.lastOrNull()?.let { it.name + " @ " + tf(it.lastModified()) } ?: "-"}")
            val have = fs.map { it.name.removePrefix("StepTrack_").removeSuffix(".txt") }.toSet()
            val missing = (events.map { it.date } + gps.map { it.date }).distinct().sorted().filter { it !in have }
            l("days in database without a backup file: ${if (missing.isEmpty()) "none" else missing.take(20).joinToString()}")
            val pf = java.io.File(d.parentFile, "places.txt"); l("places.txt exists=${pf.exists()}")
        }
        sec("13. APP LOG (last 400 lines; the full log is in applog.txt)") {
            l("log size ${AppLog.sizeBytes() / 1024} KB, ${AppLog.lineCount()} lines"); l(AppLog.tail(400))
        }
        return sb.toString()
    }

    private class NoClose(o: OutputStream) : FilterOutputStream(o) {
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
        override fun close() { flush() }
    }

    /** ZIP with everything needed for analysis: report, full log, and all raw data. */
    fun writeZip(c: Context, out: OutputStream) {
        AppLog.i("Diag", "building diagnostic package")
        ZipOutputStream(BufferedOutputStream(out)).use { z ->
            fun entry(name: String, body: (OutputStream) -> Unit) {
                z.putNextEntry(ZipEntry(name))
                try { body(NoClose(z)) } catch (e: Throwable) { AppLog.e("Diag", "entry $name failed", e); z.write("FAILED: $e\n".toByteArray()) }
                z.closeEntry()
            }
            entry("README.txt") { it.write(("StepTrack Offline Pro diagnostic package\nGenerated ${tf(System.currentTimeMillis())}\n\n" +
                "diagnostic_report.txt   device, permissions, sensors, settings, data-quality and GPS analysis, calibration checks, backup state\n" +
                "applog.txt              full app log (lifecycle, sensors, GPS, steps, errors, user actions, 1-minute heartbeats)\n" +
                "steps.csv               every step: time, heading, direction, x/y, altitude\n" +
                "movement_track.csv      every GPS point with speed, type, place\n" +
                "path.kml                GPS path for Google Maps/Earth\n" +
                "steptrack_full.json     all tables as JSON\n").toByteArray()) }
            entry("diagnostic_report.txt") { it.write(build(c).toByteArray(Charsets.UTF_8)) }
            entry("applog.txt") { it.write(AppLog.text().toByteArray(Charsets.UTF_8)) }
            entry("steps.csv") { ExportManager.write(c, "csv", it) }
            entry("movement_track.csv") { ExportManager.write(c, "trackcsv", it) }
            entry("path.kml") { ExportManager.write(c, "kml", it) }
            entry("steptrack_full.json") { ExportManager.write(c, "json", it) }
        }
        AppLog.i("Diag", "diagnostic package written")
    }
}
