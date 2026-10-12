package com.steptrack.offline
import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent rolling app log (app-private files/logs, 2 x ~2.5 MB) + lifetime diagnostic counters.
 * Everything stays on the phone. It is exported only when you tap an export button in More > Diagnostics.
 */
object AppLog {
    private const val MAX = 2_500_000L
    @Volatile private var dir: File? = null
    private var sp: android.content.SharedPreferences? = null
    private val lock = Any()
    private val counters = ConcurrentHashMap<String, Long>()
    private val fmt = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }
    @Volatile private var lastMirrorSize = -1L

    fun init(c: Context) {
        synchronized(lock) {
            if (dir != null) return
            val ctx = c.applicationContext
            dir = File(ctx.filesDir, "logs").also { it.mkdirs() }
            sp = ctx.getSharedPreferences("diag", 0)
            sp?.all?.forEach { (k, v) -> if (v is Long) counters[k] = v }
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                try { log("E", "CRASH", "Uncaught exception on thread ${t.name}\n" + Log.getStackTraceString(e)); count("crashes"); flush() } catch (_: Throwable) {}
                prev?.uncaughtException(t, e)
            }
        }
        log("I", "App", "logger ready, process ${android.os.Process.myPid()}")
    }

    fun log(level: String, tag: String, msg: String) {
        val d = dir ?: return
        val line = "${fmt.get()!!.format(Date())} $level/$tag [${Thread.currentThread().name}] $msg\n"
        when (level) { "E" -> Log.e("StepTrack", "$tag: $msg"); "W" -> Log.w("StepTrack", "$tag: $msg"); else -> Log.i("StepTrack", "$tag: $msg") }
        synchronized(lock) {
            try {
                val f = File(d, "applog.txt")
                if (f.length() > MAX) { val o = File(d, "applog.1.txt"); o.delete(); f.renameTo(o) }
                FileWriter(f, true).use { it.write(line) }
            } catch (_: Exception) {}
        }
    }
    fun i(tag: String, msg: String) = log("I", tag, msg)
    fun w(tag: String, msg: String) = log("W", tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) = log("E", tag, if (t == null) msg else msg + "\n" + Log.getStackTraceString(t))

    // ---- counters (lifetime, persisted at flush()) ----
    fun count(k: String, n: Long = 1L) { counters.compute(k) { _, v -> (v ?: 0L) + n } }
    fun get(k: String): Long = counters[k] ?: 0L
    fun snapshot(): Map<String, Long> = HashMap(counters)
    fun flush() { try { val ed = sp?.edit() ?: return; counters.forEach { (k, v) -> ed.putLong(k, v) }; ed.apply() } catch (_: Exception) {} }

    // ---- reading ----
    private fun files(): List<File> { val d = dir ?: return emptyList(); return listOf(File(d, "applog.1.txt"), File(d, "applog.txt")).filter { it.exists() } }
    fun sizeBytes(): Long = files().sumOf { it.length() }
    fun text(): String = synchronized(lock) { files().joinToString("") { it.readText() } }
    fun tail(n: Int): String { val l = text().lines().dropLastWhile { it.isEmpty() }; return l.takeLast(n).joinToString("\n") }
    fun lineCount(): Int = synchronized(lock) { files().sumOf { f -> f.useLines { it.count() } } }
    fun clear() { synchronized(lock) { files().forEach { it.delete() } } }

    /** Copy of the log in Documents/StepTrack/logs so it survives an uninstall (only when storage access is allowed). */
    fun mirror(c: Context) {
        try {
            if (!AutoBackup.canWrite(c)) return
            val sz = sizeBytes(); if (sz == lastMirrorSize) return
            val root = AutoBackup.dir().parentFile ?: return
            val out = File(root, "logs"); out.mkdirs()
            val tmp = File(out, "applog.tmp"); tmp.writeText(text())
            val f = File(out, "applog.txt"); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            lastMirrorSize = sz
        } catch (_: Exception) {}
    }
}
