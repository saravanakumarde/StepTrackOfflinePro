package com.steptrack.offline
import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class StepCounterService : Service(), SensorEventListener {
    private val exec = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + exec)
    private lateinit var dao: StepDao; private lateinit var dm: DirectionManager
    private lateinit var fusion: SensorFusionHelper; private lateinit var prefs: Prefs; private lateinit var sm: SensorManager
    private lateinit var lm: LocationManager
    private var count = 0; private var curDate = ""; private val bk = IntArray(8); private var dominant = "-"
    private var lastPeak = 0L; private var gAvg = 9.8f
    // Move tab (GPS) state
    private var hasOrigin = false; private var lat0 = 0.0; private var lon0 = 0.0
    private var wl: PowerManager.WakeLock? = null
    // Elevation: barometer (relative, precise) anchored to GPS altitude (absolute) when available
    private val baroS: Sensor? by lazy { sm.getDefaultSensor(Sensor.TYPE_PRESSURE) }
    @Volatile private var curAlt = NO_ALT
    private var ps = -1f; private var baro = 0f; private var altOff = 0f; private var offInit = false; private var lastPub = NO_ALT; private var gpsAltAt = 0L
    private var gDist = 0f; private var lastAcc: Location? = null; private var gTick = 0
    // Diagnostics state (see AppLog)
    private var svcStartAt = 0L; private var lastFixAt = 0L; private var lastFixOk = true; private var firstFixLogged = false
    @Volatile private var lastFusionAt = 0L; @Volatile private var lastStepEvtAt = 0L; private var stepSrc = "none"
    private var hbPrev: Map<String, Long> = emptyMap(); private var hbIdle = 0

    private val locL = object : LocationListener {
        override fun onLocationChanged(l: Location) { onFix(l) }
        override fun onProviderEnabled(p: String) { AppLog.i("GPS", "provider enabled: $p"); LiveState.gpsStatus.value = "Searching for GPS…" }
        override fun onProviderDisabled(p: String) { AppLog.w("GPS", "provider DISABLED: $p"); AppLog.count("gps_provider_off"); LiveState.gpsStatus.value = "GPS is off - enable Location in system settings" }
        @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this); svcStartAt = System.currentTimeMillis(); AppLog.count("svc_start")
        prefs = Prefs(this); dao = AppDb.get(this).dao(); dm = DirectionManager(dao)
        AppLog.i("Service", "onCreate (start #${AppLog.get("svc_start")}) stride=${prefs.stride} weight=${prefs.weight} goal=${prefs.goal} elevOn=${prefs.elevOn} moveOn=${prefs.moveOn} sessionStart=${prefs.sessionStart}")
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val ch = NotificationChannel("st", "StepTrack", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        ServiceCompat.startForeground(this, 1, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        fusion = SensorFusionHelper(this) { h, a -> lastFusionAt = System.currentTimeMillis(); AppLog.count("fusion_evt"); LiveState.heading.value = h; LiveState.accuracy.value = a }
        fusion.start()
        // Walking (unchanged): STEP_COUNTER -> STEP_DETECTOR -> accelerometer peaks
        val s = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        stepSrc = s?.name ?: "none"
        AppLog.i("Sensors", "step source chosen: ${s?.name} (type ${s?.type}); counter=${sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null} detector=${sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null} rotationVector=${sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null} baro=${baroS != null}")
        if (s == null) AppLog.e("Sensors", "NO step sensor available")
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
        scope.launch { loadDay(today()); AppLog.i("Service", "loaded today: $count steps in database"); refresh() }
        applyElev()
        if (prefs.moveOn) setMove(true)
        scope.launch { // daily text-file auto-save (Documents/StepTrack/daily)
            var d0 = today(); var sig = -1L; var didAll = false; delay(20_000)
            while (isActive) {
                val d = today()
                if (d != d0) { AutoBackup.saveDay(this@StepCounterService, d0); d0 = d; sig = -1L }
                if (!didAll && AutoBackup.canWrite(this@StepCounterService)) { AutoBackup.saveAll(this@StepCounterService); didAll = true }
                val s = dao.count(d).toLong() * 1_000_000L + dao.gpsCount(d)
                if (s != sig && AutoBackup.saveDay(this@StepCounterService, d)) sig = s
                AppLog.mirror(this@StepCounterService)
                delay(5 * 60_000L)
            }
        }
        scope.launch { delay(30_000); while (isActive) { try { heartbeat() } catch (e: Exception) { AppLog.e("HB", "heartbeat failed", e) }; delay(60_000) } }
    }

    /** One line per minute while active (one per 10 min when idle): state + counter deltas, for analysing reliability over time. */
    private fun heartbeat() {
        val snap = AppLog.snapshot()
        val keys = listOf("steps_counter", "steps_detector", "steps_accel", "burst_events", "fix_total", "fix_saved", "fix_rej_acc", "fix_jitter", "fusion_evt")
        val dl = keys.associateWith { (snap[it] ?: 0L) - (hbPrev[it] ?: 0L) }
        hbPrev = snap
        val active = dl.filterKeys { it != "fusion_evt" }.values.any { it != 0L }
        hbIdle = if (active) 0 else hbIdle + 1
        if (!active && hbIdle % 10 != 0) { AppLog.flush(); return }
        val now = System.currentTimeMillis(); val pm = getSystemService(POWER_SERVICE) as PowerManager
        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bat = if (bi != null) 100 * bi.getIntExtra("level", 0) / bi.getIntExtra("scale", 100).coerceAtLeast(1) else -1
        val chg = if (bi != null) bi.getIntExtra("plugged", 0) != 0 else false
        val rt = Runtime.getRuntime()
        AppLog.i("HB", "up=${(now - svcStartAt) / 60000}m steps=$count d[${dl.entries.joinToString(" ") { "${it.key}=${it.value}" }}] heading=${LiveState.heading.value.toInt()} compassAcc=${LiveState.accuracy.value} " +
            "gps='${LiveState.gpsStatus.value}' speed=${"%.1f".format(LiveState.speed.value)} alt=${LiveState.alt.value} bat=$bat% chg=$chg psave=${pm.isPowerSaveMode} doze=${pm.isDeviceIdleMode} screen=${pm.isInteractive} " +
            "wakelock=${wl?.isHeld == true} fusionAge=${if (lastFusionAt == 0L) -1 else (now - lastFusionAt) / 1000}s stepSensorAge=${if (lastStepEvtAt == 0L) -1 else (now - lastStepEvtAt) / 1000}s heapMB=${(rt.totalMemory() - rt.freeMemory()) / 1048576}")
        AppLog.flush()
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        AppLog.i("Service", "onStartCommand action=${i?.action ?: (if (i == null) "null intent (system restarted the service)" else "start")} flags=$f")
        if (i == null) AppLog.count("svc_system_restart")
        if (i?.action == "NEW_SESSION") { prefs.sessionStart = System.currentTimeMillis(); scope.launch { dm.reset() } }
        when (i?.action) {
            "MOVE_ON" -> setMove(true)
            "MOVE_OFF" -> setMove(false)
            "ELEV_CHANGED" -> applyElev()
            "SAVE_NOW" -> scope.launch { AutoBackup.saveAll(this@StepCounterService) }
            "RELOAD" -> scope.launch { loadDay(today()); refresh() }
            "MOVE_RESET" -> { prefs.moveSession = System.currentTimeMillis(); scope.launch { hasOrigin = false; gDist = 0f; lastAcc = null } }
        }
        return START_STICKY
    }
    override fun onBind(i: Intent?): IBinder? = null
    override fun onAccuracyChanged(s: Sensor?, a: Int) { AppLog.i("Sensors", "accuracy changed: ${s?.name} -> $a"); AppLog.count("sensor_acc_changes") }

    // ---------- Move tab: GPS tracking (platform LocationManager, no Google Play Services) ----------
    private fun setMove(on: Boolean) {
        AppLog.i("GPS", "setMove(${on})")
        if (!on) {
            try { lm.removeUpdates(locL) } catch (_: Exception) {}
            wl?.release(); wl = null
            prefs.moveOn = false; LiveState.moveOn.value = false; LiveState.speed.value = 0f; LiveState.gpsStatus.value = "Off"
            try { ServiceCompat.startForeground(this, 1, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH) } catch (_: Exception) {}
            return
        }
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) throw SecurityException()
            ServiceCompat.startForeground(this, 1, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            scope.launch { // resume the current session (origin, cumulative distance) before fixes arrive
                val f = dao.firstGps(prefs.moveSession); val l = dao.lastGps(prefs.moveSession)
                if (f != null && l != null) {
                    hasOrigin = true; lat0 = f.lat; lon0 = f.lon; gDist = l.distance
                    lastAcc = Location("gps").apply { latitude = l.lat; longitude = l.lon }
                } else { hasOrigin = false; gDist = 0f; lastAcc = null }
            }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locL, Looper.getMainLooper())
        } catch (e: Exception) {
            AppLog.e("GPS", "could not start location updates", e); prefs.moveOn = false; LiveState.moveOn.value = false; LiveState.gpsStatus.value = "Location not available"; return
        }
        if (wl == null) wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "steptrack:move")
            .apply { setReferenceCounted(false); acquire() } // keeps GPS/sensors delivering with screen off
        prefs.moveOn = true; LiveState.moveOn.value = true; firstFixLogged = false; lastFixOk = true; lastFixAt = 0L
        AppLog.i("GPS", "tracking ON, gpsProviderEnabled=${lm.isProviderEnabled(LocationManager.GPS_PROVIDER)} bgLocation=${ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED}")
        LiveState.gpsStatus.value = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) "Searching for GPS…" else "GPS is off - enable Location in system settings"
    }

    private fun applyElev() {
        val b = baroS; LiveState.baro.value = b != null
        if (b != null) { sm.unregisterListener(this, b); if (prefs.elevOn) sm.registerListener(this, b, SensorManager.SENSOR_DELAY_NORMAL) }
        if (!prefs.elevOn) { curAlt = NO_ALT; LiveState.alt.value = NO_ALT; ps = -1f; offInit = false; LiveState.altAnchored.value = false }
    }
    private fun altNow(): Float {
        if (!prefs.elevOn) return NO_ALT
        if (baroS == null && System.currentTimeMillis() - gpsAltAt > 30000) return NO_ALT
        return curAlt
    }

    private fun onFix(l: Location) {
        AppLog.count("fix_total"); val tNow = System.currentTimeMillis()
        if (lastFixAt > 0 && tNow - lastFixAt > 30000) AppLog.w("GPS", "gap of ${(tNow - lastFixAt) / 1000}s since previous fix")
        lastFixAt = tNow
        LiveState.gpsStatus.value = "Fix ±${l.accuracy.toInt()} m"
        if (!firstFixLogged) { firstFixLogged = true; AppLog.i("GPS", "first fix: acc=${l.accuracy} m speed=${if (l.hasSpeed()) l.speed else -1f} bearing=${l.hasBearing()} altitude=${l.hasAltitude()} vAcc=${if (l.hasVerticalAccuracy()) l.verticalAccuracyMeters else -1f}") }
        if (l.accuracy > 30f) {
            AppLog.count("fix_rej_acc")
            if (lastFixOk) { lastFixOk = false; AppLog.w("GPS", "fixes being rejected: accuracy ${l.accuracy} m is worse than 30 m") }
            return
        }
        if (!lastFixOk) { lastFixOk = true; AppLog.i("GPS", "fixes accepted again (accuracy ${l.accuracy} m)") }
        LiveState.speed.value = if (l.hasSpeed()) l.speed else 0f
        LiveState.fix.value = Triple(l.latitude, l.longitude, System.currentTimeMillis())
        if (prefs.elevOn && l.hasAltitude() && (!l.hasVerticalAccuracy() || l.verticalAccuracyMeters <= 25f)) {
            val ga0: Float = if (Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude()) { AppLog.count("alt_msl"); l.mslAltitudeMeters.toFloat() } else { AppLog.count("alt_ellipsoid"); l.altitude.toFloat() }; gpsAltAt = System.currentTimeMillis()
            if (baroS != null && ps >= 0f) {
                if (!offInit) { altOff = ga0 - baro; offInit = true } else altOff += 0.02f * (ga0 - (baro + altOff))
                curAlt = baro + altOff
            } else curAlt = if (curAlt == NO_ALT) ga0 else curAlt + 0.3f * (ga0 - curAlt)
            LiveState.altAnchored.value = true; LiveState.alt.value = curAlt
        }
        val ga = altNow()
        val now = System.currentTimeMillis()
        scope.launch {
            if (!hasOrigin) { hasOrigin = true; lat0 = l.latitude; lon0 = l.longitude }
            val la = lastAcc
            if (la != null) {
                val d = la.distanceTo(l)
                if (d < max(3f, l.accuracy * 0.5f)) { AppLog.count("fix_jitter"); return@launch } // GPS jitter while standing still
                gDist += d
            }
            lastAcc = l
            val rad = Math.PI / 180.0; val er = 6371000.0
            val x = (er * (l.longitude - lon0) * rad * cos(lat0 * rad)).toFloat(); val y = (er * (l.latitude - lat0) * rad).toFloat()
            dao.insertGps(GpsPoint(timestamp = now, date = dateOf(now), lat = l.latitude, lon = l.longitude, accuracy = l.accuracy,
                speed = if (l.hasSpeed()) l.speed else 0f, heading = if (l.hasBearing()) l.bearing else fusion.heading, x = x, y = y, distance = gDist, alt = ga))
            AppLog.count("fix_saved"); if (!l.hasBearing()) AppLog.count("fix_nobearing")
            if (++gTick % 5 == 0) refresh()
        }
    }

    // ---------- Walking (unchanged) ----------
    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val v = e.values[0].toLong(); val last = prefs.lastCounter; prefs.lastCounter = v
                lastStepEvtAt = System.currentTimeMillis(); AppLog.count("step_sensor_evt")
                if (last < 0) AppLog.i("Steps", "first step-counter reading $v (baseline, no steps added)")
                else if (v < last) { AppLog.w("Steps", "step counter reset: $last -> $v (device reboot?), adding $v"); AppLog.count("counter_reset") }
                val n = if (last < 0) 0 else if (v >= last) (v - last).toInt() else v.toInt() // v<last => reboot
                AppLog.count("steps_counter", n.toLong()); steps(n)
            }
            Sensor.TYPE_PRESSURE -> {
                val pr = e.values[0]; ps = if (ps < 0f) pr else ps + 0.1f * (pr - ps)
                baro = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, ps); curAlt = baro + altOff
                if (abs(curAlt - lastPub) >= 0.2f) { lastPub = curAlt; LiveState.alt.value = curAlt }
            }
            Sensor.TYPE_STEP_DETECTOR -> { lastStepEvtAt = System.currentTimeMillis(); AppLog.count("step_sensor_evt"); AppLog.count("steps_detector"); steps(1) }
            Sensor.TYPE_ACCELEROMETER -> {
                val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                gAvg = 0.9f * gAvg + 0.1f * m; val now = System.currentTimeMillis()
                if (m - gAvg > 1.8f && now - lastPeak > 300) { lastPeak = now; lastStepEvtAt = now; AppLog.count("step_sensor_evt"); AppLog.count("steps_accel"); steps(1) }
            }
        }
    }

    private fun steps(n: Int) {
        if (n >= 8) { AppLog.count("burst_events"); AppLog.w("Steps", "burst: $n steps delivered at once (sensor batching or delay); all get the same heading ${LiveState.heading.value.toInt()} deg") }
        if (n > 0) scope.launch { repeat(n) { record() }; refresh() }
    }

    private suspend fun loadDay(d: String) {
        curDate = d; bk.fill(0)
        val ev = dao.events(d); count = ev.size
        ev.forEach { bk[BUCKETS.indexOf(it.directionBucket)]++ }
        dominant = if (count == 0) "-" else BUCKETS[bk.indices.maxByOrNull { bk[it] }!!]
        LiveState.steps.value = count
    }

    private suspend fun record() {
        val ts = System.currentTimeMillis(); val d = dateOf(ts)
        if (d != curDate) { loadDay(d) }
        dm.ensure(d, prefs.sessionStart)
        val h = fusion.heading; dm.advance(h, prefs.stride)
        val b = bucketOf(h)
        dao.insert(StepEvent(timestamp = ts, date = d, heading = h, directionBucket = b, x = dm.x, y = dm.y, alt = altNow())) // saved immediately
        count++; bk[BUCKETS.indexOf(b)]++
        dominant = BUCKETS[bk.indices.maxByOrNull { bk[it] }!!]
        LiveState.steps.value = count
        if (count % 25 == 0) dao.upsert(buildSummary(d, dao.events(d), prefs.stride, prefs.weight))
    }

    private fun refresh() = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1, notif())
    private fun notif(): Notification {
        val h = LiveState.heading.value
        return NotificationCompat.Builder(this, "st").setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("$count steps | $dominant | ${h.toInt()}° ${bucketOf(h)}")
            .setContentText(if (prefs.moveOn) "Tracking movement: %.0f km/h".format(LiveState.speed.value * 3.6f) else null)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).setOnlyAlertOnce(true).build()
    }
    /** App swiped from recents: keep running; ask the system to bring the service back if the OEM kills it. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.w("Service", "onTaskRemoved (app swiped from recents), scheduling restart"); AppLog.count("task_removed"); AppLog.flush()
        try {
            val pi = PendingIntent.getForegroundService(this, 7, Intent(this, StepCounterService::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            (getSystemService(ALARM_SERVICE) as AlarmManager).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000, pi)
        } catch (_: Exception) {}
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        AppLog.w("Service", "onDestroy after ${(System.currentTimeMillis() - svcStartAt) / 60000} min, steps today=$count"); AppLog.count("svc_destroy"); AppLog.flush(); AppLog.mirror(this)
        wl?.release(); wl = null
        sm.unregisterListener(this); fusion.stop()
        try { lm.removeUpdates(locL) } catch (_: Exception) {}
        super.onDestroy()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) try {
            AppLog.init(c); AppLog.i("Boot", "BOOT_COMPLETED received, starting service"); AppLog.count("boot_start")
            c.startForegroundService(Intent(c, StepCounterService::class.java))
        } catch (e: Exception) { AppLog.e("Boot", "could not start service after boot", e) }
    }
}
