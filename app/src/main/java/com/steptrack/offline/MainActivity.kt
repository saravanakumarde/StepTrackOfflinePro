package com.steptrack.offline
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.hypot
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        AppLog.init(this); AppLog.i("Activity", "onCreate (savedState=${b != null}) v=${try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }} android=${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL}")
        val perms = buildList { add(Manifest.permission.ACTIVITY_RECOGNITION); if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) }
        val l = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> AppLog.i("Permissions", "result: $r"); startSvc() }
        l.launch(perms.toTypedArray())
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { App() } } }
    }
    private fun startSvc() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
            AppLog.i("Activity", "starting StepCounterService"); ContextCompat.startForegroundService(this, Intent(this, StepCounterService::class.java))
        } else AppLog.e("Activity", "ACTIVITY_RECOGNITION not granted: step service NOT started")
    }
    override fun onResume() { super.onResume(); AppLog.i("Activity", "onResume") }
    override fun onPause() { super.onPause(); AppLog.i("Activity", "onPause"); AppLog.flush() }
    override fun onDestroy() { AppLog.i("Activity", "onDestroy isFinishing=$isFinishing"); super.onDestroy() }
}

@Composable fun App() {
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(tab) { AppLog.i("UI", "tab -> ${listOf("Walk", "Move", "Height", "Analytics", "More")[tab]}") }
    Scaffold(bottomBar = { NavigationBar { listOf("Walk", "Move", "Height", "Analytics", "More").forEachIndexed { i, t ->
        NavigationBarItem(tab == i, { tab = i }, { Text(listOf("🚶", "🚗", "⛰", "📊", "⚙")[i]) }, label = { Text(t, maxLines = 1, fontSize = 10.sp) }) } } }) { p ->
        Box(Modifier.padding(p).fillMaxSize()) { when (tab) { 0 -> TodayScreen(); 1 -> MoveScreen(); 2 -> HeightScreen(); 3 -> AnalyticsScreen(); else -> MoreScreen() } }
    }
}

@Composable fun rememberToday(): List<StepEvent> {
    val c = LocalContext.current
    return remember { AppDb.get(c).dao().eventsFlow(today()) }.collectAsState(emptyList()).value
}
@Composable fun sessionPts(ev: List<StepEvent>): List<StepEvent> { val s = Prefs(LocalContext.current).sessionStart; return ev.filter { it.timestamp >= s } }

@Composable fun Card2(title: String, value: String, m: Modifier = Modifier) = Card(m.padding(4.dp)) {
    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(title, fontSize = 12.sp); Text(value, fontSize = 18.sp) } }

@Composable fun Compass(h: Float) {
    Canvas(Modifier.size(170.dp)) {
        val c = center; val r = size.minDimension / 2
        drawCircle(Color.Gray, r, c, style = Stroke(4f))
        rotate(-h, c) {
            val pt = android.graphics.Paint().apply { textSize = 40f; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true }
            listOf("N", "E", "S", "W").forEachIndexed { i, t ->
                pt.color = if (i == 0) android.graphics.Color.RED else android.graphics.Color.WHITE
                val a = Math.toRadians(i * 90.0)
                drawContext.canvas.nativeCanvas.drawText(t, c.x + (r * 0.75f * Math.sin(a)).toFloat(), c.y - (r * 0.75f * Math.cos(a)).toFloat() + 14f, pt)
            }
        }
        drawLine(Color.Red, c, Offset(c.x, c.y - r * 0.5f), 8f, StrokeCap.Round)
    }
}

@Composable fun TodayScreen() {
    val c = LocalContext.current; val p = remember { Prefs(c) }; val ev = rememberToday()
    val h by LiveState.heading.collectAsState(); val steps = ev.size
    val km = steps * p.stride / 1000f; val cal = km * p.weight * 0.57f; val act = ev.map { it.timestamp / 60000 }.distinct().size
    val sess = sessionPts(ev); val tot = sess.size.coerceAtLeast(1)
    val prog = remember { Animatable(1f) }; val scope = rememberCoroutineScope()
    var n = 0f; var s2 = 0f; var e = 0f; var w = 0f; var px = 0f; var py = 0f
    sess.forEach { dy -> val a = dy.y - py; val b = dy.x - px; if (a > 0) n += a else s2 -= a; if (b > 0) e += b else w -= b; px = dy.x; py = dy.y }
    val far = sess.maxOfOrNull { hypot(it.x, it.y) } ?: 0f
    val idx = if (sess.isEmpty()) -1 else ((sess.size - 1) * prog.value).toInt()
    val needle = if (idx < 0 || prog.value >= 0.99f) h else sess[idx].heading
    val frac = min(1f, steps / p.goal.toFloat())
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Path (start = green, ring = compass)")
        PathPlot(sess.map { it.x to it.y }, prog.value, modifier = Modifier.fillMaxWidth().aspectRatio(1f), heading = needle)
        Button({ val sp = p.replaySpeed; AppLog.i("UI", "Walk replay pressed speed=${sp}x"); scope.launch { prog.snapTo(0f); prog.animateTo(1f, tween((8000 / sp).toInt().coerceAtLeast(300), easing = LinearEasing)) } }) { Text("▶ Replay today's path") }
        Spacer(Modifier.height(12.dp))
        Text("$steps steps", fontSize = 40.sp)
        Canvas(Modifier.fillMaxWidth().height(24.dp).padding(vertical = 4.dp)) {
            val y = size.height / 2; val x0 = 12f; val x1 = size.width - 12f
            drawLine(Color(0x33FFFFFF), Offset(x0, y), Offset(x1, y), 18f, StrokeCap.Round)
            if (frac > 0f) drawLine(PAL[0], Offset(x0, y), Offset(x0 + (x1 - x0) * frac, y), 18f, StrokeCap.Round)
        }
        Text("goal ${p.goal}  (${(100 * frac).toInt()}%)")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Card2("Distance", "%.2f km".format(km), Modifier.weight(1f)); Card2("Calories", "%.0f kcal".format(cal), Modifier.weight(1f)); Card2("Active", "$act min", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp)); Compass(h)
        Text("%03d°  %s".format(h.toInt(), bucketOf(h)), fontSize = 22.sp)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Direction breakdown (current session)", fontSize = 18.sp)
            BUCKETS.forEach { b -> val k = sess.count { it.directionBucket == b }; Text("$b: $k steps  (${100 * k / tot}%)") }
            Text("Farthest from start: %.1f m".format(far))
            Text("Distance N %.0f m | E %.0f m | S %.0f m | W %.0f m".format(n, e, s2, w))
        }
    }
}

@Composable fun storageAsker(onDone: () -> Unit): () -> Unit {
    val c = LocalContext.current
    val l1 = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onDone() }
    val l2 = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onDone() }
    return { if (Build.VERSION.SDK_INT >= 30) l1.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${c.packageName}"))) else l2.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
}

@Composable fun MoveScreen() {
    val c = LocalContext.current; val p = remember { Prefs(c) }
    var tick by remember { mutableIntStateOf(0) }
    val on = tick.let { p.moveOn }
    val pts by remember { AppDb.get(c).dao().gpsFlow(today()) }.collectAsState(emptyList())
    val speed by LiveState.speed.collectAsState(); val status by LiveState.gpsStatus.collectAsState(); val h by LiveState.heading.collectAsState()
    val ev = rememberToday()
    val cur = pts.filter { it.timestamp >= p.moveSession }
    val types = remember(cur, ev) { classifyMoves(cur, ev.map { it.timestamp }) }
    val byType = remember(cur, types) { distByType(cur, types) }
    val steps = ev.count { it.timestamp >= p.moveSession }
    val dist = if (cur.size > 1) cur.last().distance - cur.first().distance else 0f
    var scrub by remember { mutableFloatStateOf(1f) }; val replayAnim = remember { Animatable(0f) }
    var zoom by remember { mutableFloatStateOf(1f) }; var pan by remember { mutableStateOf(Offset.Zero) }
    val idx = if (cur.isEmpty()) -1 else ((cur.size - 1) * scrub).toInt()
    val tf = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val trips = remember(cur) {
        val out = mutableListOf<List<GpsPoint>>(); var s = mutableListOf<GpsPoint>()
        cur.forEach { if (s.isNotEmpty() && it.timestamp - s.last().timestamp > 180000) { out.add(s); s = mutableListOf() }; s.add(it) }
        if (s.isNotEmpty()) out.add(s); out
    }
    val bgOk = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    val ignBat = (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName)
    fun send(a: String) { c.startService(Intent(c, StepCounterService::class.java).setAction(a)) }
    val places by remember { AppDb.get(c).dao().placesFlow() }.collectAsState(emptyList())
    val fix by LiveState.fix.collectAsState(); val scope = rememberCoroutineScope()
    var pName by remember { mutableStateOf("") }; var pRad by remember { mutableStateOf("100") }; var pMsg by remember { mutableStateOf("") }
    fun nm(g: GpsPoint) = placeFor(g.lat, g.lon, places)?.name ?: "Unnamed place"
    val permL = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val okLoc = ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        AppLog.i("Permissions", "location permission result: fineGranted=$okLoc")
        if (okLoc) send("MOVE_ON")
        tick++
    }
    val needle = if (idx < 0 || (on && scrub >= 0.99f)) h else cur[idx].heading
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Facing %03d° %s (compass)  |  moving %03d° %s".format(h.toInt(), bucketOf(h), needle.toInt(), bucketOf(needle)), fontSize = 14.sp)
        PathPlot(cur.map { it.x to it.y }, progress = if (cur.isEmpty()) 1f else min(1f, (idx + 2f) / (cur.size + 1f) + 0.001f),
            heading = needle, zoom = zoom, pan = pan, colors = types.map { MOVE_COLORS[it] },
            onTransform = { z, d -> zoom = (zoom * z).coerceIn(0.5f, 60f); pan += d })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ zoom = (zoom * 1.5f).coerceAtMost(60f) }) { Text("＋") }
            Button({ zoom = (zoom / 1.5f).coerceAtLeast(0.5f) }) { Text("－") }
            Button({ zoom = 1f; pan = Offset.Zero }) { Text("Fit") }
            Text("x%.1f  (pinch with 2 fingers)".format(zoom), fontSize = 12.sp)
        }
        Slider(scrub, { scrub = it }, enabled = cur.size > 1)
        Button({ val sp = p.replaySpeed; AppLog.i("UI", "Move replay pressed speed=${sp}x"); scope.launch { replayAnim.snapTo(0f); replayAnim.animateTo(1f, tween((8000 / sp).toInt().coerceAtLeast(300), easing = LinearEasing)) { scrub = value } } }, enabled = cur.size > 1) { Text("▶ Replay today's path") }
        if (idx >= 0) {
            val q = cur[idx]; val stAt = ev.count { it.timestamp >= p.moveSession && it.timestamp <= q.timestamp }
            Text("${tf.format(Date(q.timestamp))}  |  ${MOVE_NAMES[types[idx]]}  |  ${"%.0f".format(q.speed * 3.6f)} km/h  |  ${"%.2f".format((q.distance - cur.first().distance) / 1000f)} km  |  $stAt steps")
        }
        Column(Modifier.fillMaxWidth()) {
            for (i in 1 until MOVE_NAMES.size) Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(MOVE_COLORS[i])); Spacer(Modifier.width(8.dp))
                Text("${MOVE_NAMES[i]}: ${"%.2f".format(byType[i] / 1000f)} km", fontSize = 13.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Track all movements (GPS + steps): walk, bus, car, train, tram", Modifier.weight(1f))
            Switch(on, { AppLog.i("UI", "Move tracking switch -> $it"); if (it) permL.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) else { p.moveOn = false; send("MOVE_OFF"); tick++ } })
        }
        Text("GPS: ${if (on) status else "Off"}. Keeps tracking when the app is closed. Works offline; needs Location on in system settings.", fontSize = 12.sp)
        if (!bgOk) {
            Text("To keep tracking after a restart or if the system stops the app, set Location to 'Allow all the time'.", fontSize = 12.sp)
            Button({ c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))) }) { Text("Open location permission") }
        }
        if (!ignBat) Button({ c.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}"))) }) { Text("Allow background running") }
        if (!AutoBackup.canWrite(c)) Text("Daily auto-save to Documents/StepTrack needs storage access: open the More tab.", fontSize = 12.sp)
        Row(Modifier.fillMaxWidth()) {
            Card2("Speed", "%.0f km/h".format(if (on) speed * 3.6f else 0f), Modifier.weight(1f))
            Card2("Distance", "%.2f km".format(dist / 1000f), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            Card2("Max speed", "%.0f km/h".format((cur.maxOfOrNull { it.speed } ?: 0f) * 3.6f), Modifier.weight(1f))
            Card2("Steps", "$steps", Modifier.weight(1f))
        }
        Text("Speed over time (km/h)"); LineChart(cur.map { it.speed * 3.6f })
        if (cur.isNotEmpty()) Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(tf.format(Date(cur.first().timestamp))); Text(tf.format(Date(cur.last().timestamp))) }
        Text("Trips today (a new trip starts after a 3 minute gap)", fontSize = 16.sp)
        trips.asReversed().take(10).forEach { t ->
            val d = t.last().distance - t.first().distance; val sec = ((t.last().timestamp - t.first().timestamp) / 1000f).coerceAtLeast(1f)
            Text("${nm(t.first())} → ${nm(t.last())}\n${tf.format(Date(t.first().timestamp))} - ${tf.format(Date(t.last().timestamp))}   ${"%.2f".format(d / 1000f)} km   avg ${"%.0f".format(d / sec * 3.6f)} km/h   max ${"%.0f".format(t.maxOf { it.speed } * 3.6f)} km/h", fontSize = 13.sp)
        }
        val visits = trips.zipWithNext().mapNotNull { (a, b) -> placeFor(a.last().lat, a.last().lon, places)?.let { Triple(it.name, a.last().timestamp, b.first().timestamp) } }
        if (visits.isNotEmpty()) {
            Text("Stops at your places", fontSize = 16.sp)
            visits.asReversed().take(10).forEach { Text("${it.first}   ${tf.format(Date(it.second))} - ${tf.format(Date(it.third))}   (${(it.third - it.second) / 60000} min)", fontSize = 13.sp) }
        }
        Text("My places", fontSize = 16.sp)
        val here = fix?.let { f -> if (System.currentTimeMillis() - f.third < 120000) placeFor(f.first, f.second, places)?.name else null }
        if (here != null) Text("You are at: $here")
        OutlinedTextField(pName, { pName = it }, label = { Text("Place name (e.g. Home)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pRad, { pRad = it }, label = { Text("Radius in meters") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button({
            val f = fix
            if (pName.isBlank()) pMsg = "Enter a name first"
            else if (f == null || System.currentTimeMillis() - f.third > 120000) pMsg = "No recent GPS fix. Turn tracking on and wait for a fix."
            else {
                val r = pRad.toFloatOrNull()?.coerceIn(20f, 1000f) ?: 100f; val nm = pName.trim()
                AppLog.i("UI", "place saved: $nm radius=${r.toInt()} m"); scope.launch(Dispatchers.IO) { AppDb.get(c).dao().insertPlace(Place(name = nm, lat = f.first, lon = f.second, radius = r)); AutoBackup.savePlaces(c) }
                pMsg = "Saved $nm here"; pName = ""
            }
        }) { Text("Save current location as place") }
        if (pMsg.isNotEmpty()) Text(pMsg, fontSize = 12.sp)
        places.forEach { pl ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${pl.name}  (${pl.radius.toInt()} m)", Modifier.weight(1f))
                Button({ AppLog.i("UI", "place deleted: ${pl.name}"); scope.launch(Dispatchers.IO) { AppDb.get(c).dao().deletePlace(pl.id); AutoBackup.savePlaces(c) } }) { Text("Delete") }
            }
        }
        Button({ AppLog.i("UI", "Reset movement path pressed"); send("MOVE_RESET") }) { Text("Reset movement path") }
    }
}

@Composable fun HeightScreen() {
    val c = LocalContext.current; val p = remember { Prefs(c) }
    var back by remember { mutableIntStateOf(0) }
    val date = LocalDate.now().minusDays(back.toLong()).toString()
    val ev by remember(date) { AppDb.get(c).dao().eventsFlow(date) }.collectAsState(emptyList())
    val gps by remember(date) { AppDb.get(c).dao().gpsFlow(date) }.collectAsState(emptyList())
    val alt by LiveState.alt.collectAsState(); val anch by LiveState.altAnchored.collectAsState(); val baro by LiveState.baro.collectAsState()
    val series = remember(ev, gps) { altSeries(ev, gps) }
    val st = remember(series) { elevStats(series.map { it.second }) }
    val chart = remember(series) { downsample(series, 400) }
    val pts3 = remember(ev, gps) {
        val g = gps.filter { it.alt > -9000f }.map { P3(it.timestamp, it.x, it.y, it.alt) }
        downsample(if (g.size >= 2) g else ev.filter { it.alt > -9000f }.map { P3(it.timestamp, it.x, it.y, it.alt) }, 800)
    }
    var scrub by remember { mutableFloatStateOf(1f) }; var yaw by remember { mutableFloatStateOf(30f) }; var tilt by remember { mutableFloatStateOf(55f) }
    var z3 by remember { mutableFloatStateOf(1f) }; var exag by remember { mutableFloatStateOf(1f) }
    val tf = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val ci = if (chart.isEmpty()) -1 else ((chart.size - 1) * scrub).toInt()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button({ back++ }) { Text("◀") }; Text(if (back == 0) "Today ($date)" else date, fontSize = 16.sp); Button({ if (back > 0) back-- }) { Text("▶") }
        }
        Text("3D path (blue = low, red = high)", fontSize = 16.sp)
        if (pts3.size < 2) Text("Not enough points with height yet.", fontSize = 13.sp)
        else Plot3D(pts3, yaw, tilt, z3, exag, scrub)
        Text("Time", fontSize = 12.sp); Slider(scrub, { scrub = it })
        Text("Rotate", fontSize = 12.sp); Slider(yaw, { yaw = it }, valueRange = 0f..360f)
        Text("Tilt (0 = side view, 90 = top view)", fontSize = 12.sp); Slider(tilt, { tilt = it }, valueRange = 0f..90f)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ z3 = (z3 * 1.4f).coerceAtMost(8f) }) { Text("＋") }; Button({ z3 = (z3 / 1.4f).coerceAtLeast(0.3f) }) { Text("－") }
            Button({ exag = when (exag) { 1f -> 2f; 2f -> 5f; 5f -> 10f; 10f -> 20f; else -> 1f } }) { Text("Height x${exag.toInt()}") }
        }
        if (!p.elevOn) Text("Elevation recording is OFF. Turn it on in More > Settings.", fontSize = 13.sp)
        Text("Barometer: ${if (baro) "found" else "not found (GPS altitude only, needs the Move tab on)"}", fontSize = 12.sp)
        Text("Altitude now: " + (if (alt <= -9000f) "-" else "%.0f m above sea level".format(alt) + (if (anch) " (GPS-calibrated)" else " (barometer only: relative, not true sea level)")), fontSize = 14.sp)
        Text("This is the height above sea level, not above the ground, so it stays high even when you are indoors. Stand on your ground floor and tap the button to see your height above that level.", fontSize = 12.sp)
        var gnd by remember { mutableFloatStateOf(p.groundAlt) }
        if (gnd > -9000f && alt > -9000f) { val d = alt - gnd; Text("Above your ground level: %+.1f m  (about floor %d)".format(d, Math.round(d / 3f)), fontSize = 16.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ if (alt > -9000f) { gnd = alt; p.groundAlt = alt; AppLog.i("UI", "ground level set to $alt m") } }, enabled = alt > -9000f) { Text("Set ground level here") }
            if (gnd > -9000f) Button({ gnd = -9999f; p.groundAlt = -9999f }) { Text("Clear") }
        }
        if (st == null) Text("No elevation data for this day yet.")
        else {
            Row(Modifier.fillMaxWidth()) {
                Card2("Highest", "%.0f m".format(st.max), Modifier.weight(1f)); Card2("Lowest", "%.0f m".format(st.min), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                Card2("Ascent ↑", "%.0f m (~%.0f fl)".format(st.ascent, st.ascent / 3f), Modifier.weight(1f))
                Card2("Descent ↓", "%.0f m (~%.0f fl)".format(st.descent, st.descent / 3f), Modifier.weight(1f))
            }
            Text("Net change: %+.0f m (about 3 m per floor)".format(st.net), fontSize = 13.sp)
            Text("Height over time"); AltChart(chart.map { it.second }, scrub)
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(tf.format(Date(chart.first().first))); Text(tf.format(Date(chart.last().first))) }
            if (ci >= 0) Text("${tf.format(Date(chart[ci].first))}  |  %.1f m".format(chart[ci].second))
        }
    }
}

@Composable fun AnalyticsScreen() {
    val c = LocalContext.current; val ev = rememberToday()
    val daily by remember { AppDb.get(c).dao().dailyFlow(LocalDate.now().minusDays(364).toString()) }.collectAsState(emptyList())
    val map = daily.associate { it.date to it.steps }
    fun lastN(n: Int) = (n - 1 downTo 0).map { (map[LocalDate.now().minusDays(it.toLong()).toString()] ?: 0).toFloat() }
    val hourly = FloatArray(24).also { a -> ev.forEach { a[Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).hour]++ } }.toList()
    val counts = BUCKETS.map { b -> ev.count { it.directionBucket == b } }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Steps per hour (today)"); BarChart(hourly, "0h", "23h")
        Text("Steps per day - 7 days"); BarChart(lastN(7), "-6d", "today")
        Text("Steps per day - 30 days"); BarChart(lastN(30), "-29d", "today")
        Text("30-day trend"); LineChart(lastN(30))
        Text("Direction distribution (today)"); PieChart(counts)
        Text("Direction rose (N at top)"); RoseChart(counts)
        Text("Year heatmap"); Heatmap(map)
    }
}

@Composable fun NumField(label: String, init: String, save: (String) -> Unit) {
    var t by remember { mutableStateOf(init) }
    OutlinedTextField(t, { t = it; save(it) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable fun MoreScreen() {
    val c = LocalContext.current; val p = remember { Prefs(c) }; val scope = rememberCoroutineScope()
    val acc by LiveState.accuracy.collectAsState()
    var last by remember { mutableStateOf<Uri?>(null) }; var lastMime by remember { mutableStateOf("text/csv") }
    var exportMsg by remember { mutableStateOf("") }
    fun export(kind: String, mime: String, uri: Uri?) { uri ?: return
        scope.launch(Dispatchers.IO) {
            try { c.contentResolver.openOutputStream(uri)?.use { ExportManager.write(c, kind, it) }; last = uri; lastMime = mime; exportMsg = "Saved: $kind" }
            catch (e: Exception) { AppLog.e("Export", "export $kind failed", e); exportMsg = "Export failed ($kind): ${e.message}" }
        } }
    val diagL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { export("diag", "application/zip", it) }
    val repL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { export("report", "text/plain", it) }
    val logL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { export("log", "text/plain", it) }
    val trkL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { export("trackcsv", "text/csv", it) }
    val kmlL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")) { export("kml", "application/vnd.google-earth.kml+xml", it) }
    val csvL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { export("csv", "text/csv", it) }
    val jsonL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { export("json", "application/json", it) }
    val pdfL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { export("pdf", "application/pdf", it) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Settings", fontSize = 20.sp)
        NumField("Daily goal (steps)", p.goal.toString()) { it.toIntOrNull()?.let { v -> p.goal = v } }
        NumField("Stride length (m)", p.stride.toString()) { it.toFloatOrNull()?.let { v -> p.stride = v } }
        NumField("Weight (kg)", p.weight.toString()) { it.toFloatOrNull()?.let { v -> p.weight = v } }
        var elevOn by remember { mutableStateOf(p.elevOn) }
        val baroOk by LiveState.baro.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Record elevation (barometer + GPS): adds height to every step and GPS point", Modifier.weight(1f))
            Switch(elevOn, { AppLog.i("UI", "elevation recording -> $it"); elevOn = it; p.elevOn = it; c.startService(Intent(c, StepCounterService::class.java).setAction("ELEV_CHANGED")) })
        }
        Text("Barometer: ${if (baroOk) "found" else "not found (GPS altitude only, needs the Move tab on)"}", fontSize = 12.sp)
        Text("Compass calibration: ${listOf("unreliable", "low", "medium", "high")[acc.coerceIn(0, 3)]}. Wave the phone in a figure-8 until it reads high.")
        Button({ AppLog.i("UI", "New session pressed"); c.startService(Intent(c, StepCounterService::class.java).setAction("NEW_SESSION")) }) { Text("New session (reset path)") }
        Button({ AppLog.w("UI", "DELETE ALL HISTORY pressed"); scope.launch(Dispatchers.IO) { AppDb.get(c).dao().apply { clearEvents(); clearSummaries(); clearMoves(); clearGps() }; p.sessionStart = 0 } }) { Text("Delete all history") }
        var rs by remember { mutableFloatStateOf(p.replaySpeed) }
        Text("Path replay speed: ${"%.2f".format(rs)}x  (used by the replay buttons in Walk and Move; 1x = 8 seconds for the whole path)")
        Slider(rs, { rs = it; p.replaySpeed = it }, valueRange = 0.25f..8f, steps = 30)
        var showVer by remember { mutableStateOf(false) }
        val curVer = remember { try { c.packageManager.getPackageInfo(c.packageName, 0).versionName } catch (_: Exception) { "?" } }
        Button({ showVer = !showVer }) { Text(if (showVer) "Hide version history" else "Version history (installed: $curVer)") }
        if (showVer) VERSION_HISTORY.forEach { v ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("${v.id}  -  ${v.title}", fontSize = 15.sp)
                v.notes.forEach { Text("• $it", fontSize = 12.sp) }
            }
        }
        HorizontalDivider(); Text("Export (choose Documents/StepTrack/exports)", fontSize = 20.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ csvL.launch("steptrack.csv") }) { Text("CSV") }; Button({ jsonL.launch("steptrack.json") }) { Text("JSON") }; Button({ pdfL.launch("steptrack.pdf") }) { Text("PDF") }
        }
        Button({ trkL.launch("steptrack-movement.csv") }) { Text("Movement track CSV (with time)") }
        Button({ kmlL.launch("steptrack-path.kml") }) { Text("Google Maps path file (KML)") }
        last?.let { u -> Button({ c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(lastMime).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share")) }) { Text("Share last export") } }
        HorizontalDivider(); Text("Diagnostics & app log", fontSize = 20.sp)
        var logTick by remember { mutableIntStateOf(0) }
        val logKb = logTick.let { AppLog.sizeBytes() / 1024 }
        Text("The app keeps a private log (service start/stop, sensors, GPS, steps, errors, backups, your button presses) plus a one-minute heartbeat with battery and sensor state. Nothing leaves the phone unless you export it. The ZIP holds the report, the full log and all raw data, including your GPS positions.", fontSize = 12.sp)
        Text("Log size: $logKb KB, ${logTick.let { AppLog.lineCount() }} lines. A copy is kept in Documents/StepTrack/logs when storage access is allowed.", fontSize = 12.sp)
        Button({ val st = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()); AppLog.i("UI", "export diagnostic package"); diagL.launch("steptrack-diagnostics-$st.zip") }) { Text("Export diagnostic package (ZIP, everything)") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ val st = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()); repL.launch("steptrack-report-$st.txt") }) { Text("Report only") }
            Button({ val st = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()); logL.launch("steptrack-log-$st.txt") }) { Text("Log only") }
        }
        var note by remember { mutableStateOf("") }
        OutlinedTextField(note, { note = it }, label = { Text("Note for the log (e.g. walked 100 steps north)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ if (note.isNotBlank()) { AppLog.i("NOTE", note.trim()); note = ""; logTick++ } }) { Text("Add note") }
            Button({ AppLog.clear(); AppLog.i("App", "log cleared by user"); logTick++ }) { Text("Clear log") }
            Button({ logTick++ }) { Text("Refresh") }
        }
        if (exportMsg.isNotEmpty()) Text(exportMsg, fontSize = 12.sp)
        HorizontalDivider(); Text("Auto-save to phone (survives uninstall)", fontSize = 20.sp)
        var tk by remember { mutableIntStateOf(0) }
        val canW = tk.let { AutoBackup.canWrite(c) }
        val ask = storageAsker { tk++ }
        val bs by LiveState.backupStatus.collectAsState()
        var rmsg by remember { mutableStateOf("") }
        Text("Every 5 minutes the app saves each day's steps, directions, GPS path, speed, movement type and settings as a text file in Documents/StepTrack/daily/. The files stay on the phone if you uninstall.", fontSize = 12.sp)
        Text(if (canW) "Storage access: allowed" else "Storage access: NOT allowed (needed for auto-save)")
        if (!canW) Button({ ask() }) { Text("Allow storage access") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ AppLog.i("UI", "Save all now pressed"); c.startService(Intent(c, StepCounterService::class.java).setAction("SAVE_NOW")) }) { Text("Save all now") }
            Button({ AppLog.i("UI", "Restore pressed"); scope.launch(Dispatchers.IO) { rmsg = AutoBackup.restore(c); c.startService(Intent(c, StepCounterService::class.java).setAction("RELOAD")) } }) { Text("Restore saved data") }
        }
        if (bs.isNotEmpty()) Text(bs, fontSize = 12.sp)
        if (rmsg.isNotEmpty()) Text(rmsg, fontSize = 12.sp)
        HorizontalDivider(); Text("HyperOS / MIUI setup", fontSize = 20.sp)
        Text("1. Settings > Apps > StepTrack > Battery saver > No restrictions\n2. Enable Autostart\n3. Open recents, long-press the app card, tap the lock icon")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ c.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("Battery") }
            Button({ try { c.startActivity(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))) }
                catch (_: Exception) { c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))) } }) { Text("Autostart") }
        }
    }
}
