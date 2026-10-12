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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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
    // V15: step pipeline (per-step times + headings), gait fallback, GPS power modes, stationary lock
    private val HN = 256; private val hT = LongArray(HN); private val hH = FloatArray(HN); private var hN = 0; private val hLock = Any(); private var lastHPush = 0L
    private var lastStepTs = 0L; private var lastCounterAt = 0L; private var detSince = 0; private var accelSince = 0
    @Volatile private var lastHwStepAt = 0L; @Volatile private var lastAnyStepAt = 0L
    private var accelOn = false; private var gaitRun = 0; private var lastGaitPeak = 0L; private var walkGpsSince = 0L
    @Volatile private var gpsCourse = 0f; @Volatile private var gpsCourseAt = 0L
    @Volatile private var gpsActive = true; private var gpsModeAt = 0L
    @Volatile private var lockAnchor: Location? = null; private var lockSince = 0L; private var lockOut = 0
    private var lastHold = 0L

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
        fusion = SensorFusionHelper(this) { h, a ->
            val tn = System.currentTimeMillis(); lastFusionAt = tn; AppLog.count("fusion_evt")
            if (tn - lastHPush >= 200L) { lastHPush = tn; pushHeading(h, tn) }
            LiveState.heading.value = h; LiveState.accuracy.value = a
        }
        fusion.start()
        // Steps: hardware counter (total) + wake-up step detector (per-step times); accelerometer gait detector as fallback
        val counter = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val det = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR, true) ?: sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        stepSrc = "counter=${counter?.name} detector=${det?.name}(wakeUp=${det?.isWakeUpSensor})"
        AppLog.i("Sensors", "step sensors: $stepSrc rotationVector=${sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null} baro=${baroS != null}")
        if (counter == null && det == null) { AppLog.e("Sensors", "NO hardware step sensor: using accelerometer gait detector"); setAccel(true) }
        counter?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, 0) }
        det?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, 0) }
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
        scope.launch { while (isActive) { delay(15_000L); val d = today(); if (d != curDate) { AppLog.i("Service", "new day $d: step count reset"); loadDay(d); refresh() } } }
        scope.launch { delay(30_000); while (isActive) { try { heartbeat() } catch (e: Exception) { AppLog.e("HB", "heartbeat failed", e) }; delay(60_000) } }
    }

    /** One line per minute while active (one per 10 min when idle): state + counter deltas, for analysing reliability over time. */
    private fun heartbeat() {
        val snap = AppLog.snapshot()
        val keys = listOf("steps_counter", "steps_detector", "steps_accel", "burst_events", "fix_total", "fix_saved", "fix_rej_acc", "fix_jitter", "fix_locked", "fusion_evt")
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
            "wakelock=${wl?.isHeld == true} gpsMode=${if (gpsActive) "1s" else "10s"} locked=${lockAnchor != null} accel=$accelOn fusionAge=${if (lastFusionAt == 0L) -1 else (now - lastFusionAt) / 1000}s stepSensorAge=${if (lastStepEvtAt == 0L) -1 else (now - lastStepEvtAt) / 1000}s heapMB=${(rt.totalMemory() - rt.freeMemory()) / 1048576}")
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
            "MOVE_RESET" -> { prefs.moveSession = System.currentTimeMillis(); scope.launch { hasOrigin = false; gDist = 0f; lastAcc = null; lockAnchor = null } }
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
            wl?.release(); wl = null; gpsActive = true; lockAnchor = null
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
        gpsActive = true; gpsModeAt = System.currentTimeMillis(); lastHold = 0L; holdAwake() // wake lock is now timed and only held while moving
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
        if (lastFixAt > 0 && tNow - lastFixAt > 30000 && gpsActive) AppLog.w("GPS", "gap of ${(tNow - lastFixAt) / 1000}s since previous fix")
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
        // GPS course is more reliable than the compass while moving: used as the step direction for a few seconds
        if (l.hasBearing() && l.hasSpeed() && l.speed >= 1.5f && l.accuracy <= 20f && (!l.hasBearingAccuracy() || l.bearingAccuracyDegrees <= 30f)) { gpsCourse = l.bearing; gpsCourseAt = tNow }
        // walking-speed GPS but no hardware steps: switch the accelerometer gait detector on (only then, to save battery)
        val walkSpeed = l.hasSpeed() && l.speed in 0.6f..2.6f
        if (walkSpeed) { if (walkGpsSince == 0L) walkGpsSince = tNow } else walkGpsSince = 0L
        val wantAccel = walkGpsSince != 0L && tNow - walkGpsSince > 8000L && tNow - lastHwStepAt > 12000L
        if (wantAccel != accelOn && (wantAccel || sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null || sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null)) setAccel(wantAccel)
        if (prefs.elevOn && l.hasAltitude() && (!l.hasVerticalAccuracy() || l.verticalAccuracyMeters <= 25f)) {
            val ga0: Float = if (Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude()) { AppLog.count("alt_msl"); l.mslAltitudeMeters.toFloat() } else { AppLog.count("alt_ellipsoid"); l.altitude.toFloat() }; gpsAltAt = System.currentTimeMillis()
            if (baroS != null && ps >= 0f) {
                if (!offInit) { altOff = ga0 - baro; offInit = true } else altOff += 0.02f * (ga0 - (baro + altOff))
                curAlt = baro + altOff
            } else { // GPS-only altitude is noisy (several metres): smooth strongly, trust good vertical accuracy more
                val w = if (l.hasVerticalAccuracy()) (1.5f / max(1f, l.verticalAccuracyMeters)).coerceIn(0.02f, 0.12f) else 0.05f
                curAlt = if (curAlt == NO_ALT) ga0 else curAlt + w * (ga0 - curAlt)
            }
            LiveState.altAnchored.value = true; LiveState.alt.value = curAlt
        }
        val ga = altNow()
        val now = System.currentTimeMillis()
        scope.launch {
            if (!hasOrigin) { hasOrigin = true; lat0 = l.latitude; lon0 = l.longitude }
            val la = lastAcc
            if (la != null) {
                val d = la.distanceTo(l)
                if (now - lastAnyStepAt < 45_000L) { // steps in the last 45 s: really moving, only drop tiny jitter
                    lockAnchor = null; lockOut = 0
                    if (d < max(3f, l.accuracy * 0.5f)) { AppLog.count("fix_jitter"); return@launch }
                } else { // stationary lock: no steps for 45 s, ignore GPS wander (indoor drift) unless clearly leaving
                    val anc = lockAnchor ?: la.also { lockAnchor = it; lockSince = now; lockOut = 0; AppLog.i("GPS", "stationary lock ON (no steps for 45 s)") }
                    val da = anc.distanceTo(l)
                    val out = da > max(40f, 4f * l.accuracy) || (l.hasSpeed() && l.speed >= 5f && da > 25f)
                    lockOut = if (out) lockOut + 1 else 0
                    if (lockOut < 3 && !(out && l.hasSpeed() && l.speed >= 5f && lockOut >= 2)) {
                        AppLog.count("fix_locked"); LiveState.speed.value = 0f
                        if (gpsActive && now - lockSince > 90_000L && now - lastAnyStepAt > 90_000L) setGpsMode(false)
                        else if (!gpsActive && (out && lockOut >= 1)) setGpsMode(true, true)
                        return@launch
                    }
                    AppLog.i("GPS", "stationary lock OFF: moved ${da.toInt()} m from the anchor"); lockAnchor = null; lockOut = 0
                    if (!gpsActive) setGpsMode(true, true)
                }
                gDist += d
            }
            lastAcc = l; holdAwake()
            val rad = Math.PI / 180.0; val er = 6371000.0
            val x = (er * (l.longitude - lon0) * rad * cos(lat0 * rad)).toFloat(); val y = (er * (l.latitude - lat0) * rad).toFloat()
            dao.insertGps(GpsPoint(timestamp = now, date = dateOf(now), lat = l.latitude, lon = l.longitude, accuracy = l.accuracy,
                speed = if (l.hasSpeed()) l.speed else 0f, heading = if (l.hasBearing()) l.bearing else fusion.heading, x = x, y = y, distance = gDist, alt = ga))
            AppLog.count("fix_saved"); if (!l.hasBearing()) AppLog.count("fix_nobearing")
            if (++gTick % 5 == 0) refresh()
        }
    }

    @Synchronized private fun setGpsMode(active: Boolean, force: Boolean = false) {
        if (!prefs.moveOn || active == gpsActive) return
        val now = System.currentTimeMillis(); if (!force && now - gpsModeAt < 20_000L) return
        try {
            lm.removeUpdates(locL)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, if (active) 1000L else 10_000L, 0f, locL, Looper.getMainLooper())
        } catch (e: Exception) { AppLog.e("GPS", "GPS mode change failed", e); return }
        gpsActive = active; gpsModeAt = now
        AppLog.i("GPS", "GPS mode -> ${if (active) "ACTIVE (1 s fixes)" else "IDLE (10 s fixes, phone not moving)"}"); AppLog.count(if (active) "gps_active" else "gps_idle")
    }

    @Synchronized private fun holdAwake() {
        val now = System.currentTimeMillis(); if (now - lastHold < 30_000L) return
        lastHold = now
        try {
            val w = wl ?: (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "steptrack:move").also { it.setReferenceCounted(false); wl = it }
            w.acquire(120_000L) // timed: released automatically 2 min after the last movement
        } catch (e: Exception) { AppLog.e("Power", "wake lock failed", e) }
    }

    private fun setAccel(on: Boolean) {
        if (on == accelOn) return
        val a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        if (on) sm.registerListener(this, a, SensorManager.SENSOR_DELAY_GAME) else sm.unregisterListener(this, a)
        accelOn = on; gaitRun = 0; AppLog.count(if (on) "accel_on" else "accel_off")
        AppLog.i("Steps", "accelerometer gait detector ${if (on) "ON" else "OFF"}")
    }

    // ---------- Walking: per-step times and headings ----------
    private fun pushHeading(h: Float, t: Long) { synchronized(hLock) { val i = hN % HN; hT[i] = t; hH[i] = h; hN++ } }
    /** Compass heading at wall-clock time t (sensor reports on change, so the latest sample at or before t holds). */
    private fun headingAt(t: Long): Float = synchronized(hLock) {
        val n = min(hN, HN); if (n == 0) return@synchronized fusion.heading
        var oldest = fusion.heading
        for (k in 0 until n) { val i = (hN - 1 - k) % HN; oldest = hH[i]; if (hT[i] <= t) return@synchronized hH[i] }
        oldest
    }
    private fun eventWall(e: SensorEvent): Long {
        val now = System.currentTimeMillis(); val age = (SystemClock.elapsedRealtimeNanos() - e.timestamp) / 1_000_000L
        return if (age < 0L || age > 600_000L) now else now - age
    }
    private fun onStepActivity() {
        lastAnyStepAt = System.currentTimeMillis()
        if (prefs.moveOn) { holdAwake(); if (!gpsActive) setGpsMode(true, true) }
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val v = e.values[0].toLong(); val last = prefs.lastCounter; prefs.lastCounter = v
                val endTs = eventWall(e); val prev = lastCounterAt; lastCounterAt = endTs
                lastStepEvtAt = System.currentTimeMillis(); AppLog.count("step_sensor_evt")
                if (last < 0) AppLog.i("Steps", "first step-counter reading $v (baseline, no steps added)")
                else if (v < last) { AppLog.w("Steps", "step counter reset: $last -> $v (device reboot?), adding $v"); AppLog.count("counter_reset") }
                val n = if (last < 0) 0 else if (v >= last) (v - last).toInt() else v.toInt() // v<last => reboot
                AppLog.count("steps_counter", n.toLong())
                val known = detSince + accelSince; detSince = 0; accelSince = 0 // steps from the detector / gait fallback are already recorded
                val extra = n - known
                if (n > 0) { lastHwStepAt = System.currentTimeMillis(); onStepActivity() }
                if (extra > 0 && (known == 0 || extra > 1)) {
                    if (extra >= 100) AppLog.w("Steps", "step counter caught up with $extra steps (sensor was silent)")
                    if (extra >= 8) AppLog.count("burst_events")
                    stepsBatch(extra, endTs, prev)
                }
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                val t = eventWall(e); lastHwStepAt = System.currentTimeMillis(); lastStepEvtAt = lastHwStepAt; detSince++
                AppLog.count("step_sensor_evt"); AppLog.count("steps_detector"); onStepActivity()
                scope.launch { record(t); if (count % 5 == 0) refresh() }
            }
            Sensor.TYPE_PRESSURE -> {
                val pr = e.values[0]; ps = if (ps < 0f) pr else ps + 0.1f * (pr - ps)
                baro = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, ps); curAlt = baro + altOff
                if (abs(curAlt - lastPub) >= 0.2f) { lastPub = curAlt; LiveState.alt.value = curAlt }
            }
            Sensor.TYPE_ACCELEROMETER -> { // gait detector: only counts when the hardware step sensors have been silent for 12 s
                val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                gAvg = 0.9f * gAvg + 0.1f * m; val now = System.currentTimeMillis()
                if (m - gAvg > 1.6f && now - lastPeak > 300) {
                    lastPeak = now; val dt = now - lastGaitPeak; lastGaitPeak = now
                    gaitRun = if (dt in 300L..1000L) gaitRun + 1 else 0
                    if (gaitRun >= 4 && now - lastHwStepAt > 12_000L) {
                        val n = if (gaitRun == 4) 4 else 1
                        accelSince += n; lastStepEvtAt = now; AppLog.count("step_sensor_evt"); AppLog.count("steps_accel", n.toLong()); onStepActivity()
                        stepsBatch(n, now, now - n * 500L)
                    }
                }
            }
        }
    }

    /** Record n steps spread evenly over the interval since the previous report (sensor batches arrive every ~10 s). */
    private fun stepsBatch(n: Int, endTs: Long, prevTs: Long) {
        if (n <= 0) return
        scope.launch {
            val span = (if (prevTs in 1 until endTs) endTs - prevTs else n * 500L).coerceIn(n * 350L, n * 1500L)
            val start = endTs - span
            for (i in 1..n) record(start + span * i / n)
            refresh()
        }
    }

    private suspend fun loadDay(d: String) {
        curDate = d; bk.fill(0)
        val ev = dao.events(d); count = ev.size
        ev.forEach { bk[BUCKETS.indexOf(it.directionBucket)]++ }
        dominant = if (count == 0) "-" else BUCKETS[bk.indices.maxByOrNull { bk[it] }!!]
        LiveState.steps.value = count
    }

    private suspend fun record(ts0: Long) {
        var ts = ts0; if (ts <= lastStepTs) ts = lastStepTs + 1; lastStepTs = ts
        val d = dateOf(ts)
        if (d != curDate) { loadDay(d) }
        dm.ensure(d, prefs.sessionStart)
        val useGps = System.currentTimeMillis() - gpsCourseAt < 4000L
        val h = if (useGps) gpsCourse else headingAt(ts)
        AppLog.count(if (useGps) "heading_gps" else "heading_compass")
        dm.advance(h, prefs.stride)
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
        sm.unregisterListener(this); accelOn = false; fusion.stop()
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
