package com.steptrack.offline
import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

const val NO_ALT = -9999f
val BUCKETS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
fun bucketOf(h: Float): String = BUCKETS[((((h % 360f) + 360f + 22.5f) % 360f) / 45f).toInt() % 8]
fun dateOf(ts: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))
fun today() = dateOf(System.currentTimeMillis())

@Entity(tableName = "step_events", indices = [Index("date")])
data class StepEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val timestamp: Long, val date: String, val steps: Int = 1,
    val heading: Float, @ColumnInfo(name = "direction_bucket") val directionBucket: String,
    @ColumnInfo(name = "x_pos") val x: Float, @ColumnInfo(name = "y_pos") val y: Float,
    @ColumnInfo(defaultValue = "-9999") val alt: Float = NO_ALT)

@Entity(tableName = "daily_summary")
data class DailySummary(@PrimaryKey val date: String, val totalSteps: Int, val distanceM: Float, val calories: Float,
    val activeMinutes: Int, val dominantDirection: String, val bucketCountsJson: String, val pathJson: String)

@Entity(tableName = "movement_points", indices = [Index("date")])
data class MovePoint(@PrimaryKey(autoGenerate = true) val id: Long = 0, val timestamp: Long, val date: String,
    val heading: Float, val speed: Float, val x: Float, val y: Float, val distance: Float)

@Entity(tableName = "gps_points", indices = [Index("date")])
data class GpsPoint(@PrimaryKey(autoGenerate = true) val id: Long = 0, val timestamp: Long, val date: String,
    val lat: Double, val lon: Double, val accuracy: Float, val speed: Float, val heading: Float,
    val x: Float, val y: Float, val distance: Float, @ColumnInfo(defaultValue = "-9999") val alt: Float = NO_ALT)

@Entity(tableName = "places")
data class Place(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val lat: Double, val lon: Double, val radius: Float)

data class DayCount(val date: String, val steps: Int)

@Dao interface StepDao {
    @Insert suspend fun insert(e: StepEvent)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(s: DailySummary)
    @Query("SELECT * FROM step_events WHERE date=:d ORDER BY timestamp") fun eventsFlow(d: String): Flow<List<StepEvent>>
    @Query("SELECT * FROM step_events WHERE date=:d ORDER BY timestamp") suspend fun events(d: String): List<StepEvent>
    @Query("SELECT * FROM step_events ORDER BY timestamp") suspend fun allEvents(): List<StepEvent>
    @Query("SELECT * FROM step_events WHERE date=:d ORDER BY timestamp DESC LIMIT 1") suspend fun last(d: String): StepEvent?
    @Query("SELECT COUNT(*) FROM step_events WHERE date=:d") suspend fun count(d: String): Int
    @Query("SELECT date, COUNT(*) AS steps FROM step_events WHERE date>=:from GROUP BY date") fun dailyFlow(from: String): Flow<List<DayCount>>
    @Insert suspend fun insertGps(p: GpsPoint)
    @Insert suspend fun insertGpsList(l: List<GpsPoint>)
    @Insert suspend fun insertPlace(p: Place)
    @Query("DELETE FROM places WHERE id=:id") suspend fun deletePlace(id: Long)
    @Query("SELECT * FROM places ORDER BY name") fun placesFlow(): Flow<List<Place>>
    @Query("SELECT * FROM places") suspend fun allPlaces(): List<Place>
    @Insert suspend fun insertEvents(l: List<StepEvent>)
    @Query("SELECT * FROM gps_points WHERE date=:d ORDER BY timestamp") suspend fun gps(d: String): List<GpsPoint>
    @Query("SELECT timestamp FROM step_events WHERE date=:d") suspend fun stepTs(d: String): List<Long>
    @Query("SELECT timestamp FROM gps_points WHERE date=:d") suspend fun gpsTs(d: String): List<Long>
    @Query("SELECT COUNT(*) FROM gps_points WHERE date=:d") suspend fun gpsCount(d: String): Int
    @Query("SELECT DISTINCT date FROM step_events UNION SELECT DISTINCT date FROM gps_points") suspend fun allDates(): List<String>
    @Query("SELECT * FROM gps_points WHERE date=:d ORDER BY timestamp") fun gpsFlow(d: String): Flow<List<GpsPoint>>
    @Query("SELECT * FROM gps_points WHERE timestamp>=:since ORDER BY timestamp LIMIT 1") suspend fun firstGps(since: Long): GpsPoint?
    @Query("SELECT * FROM gps_points WHERE timestamp>=:since ORDER BY timestamp DESC LIMIT 1") suspend fun lastGps(since: Long): GpsPoint?
    @Query("SELECT * FROM gps_points ORDER BY timestamp") suspend fun allGps(): List<GpsPoint>
    @Query("DELETE FROM gps_points") suspend fun clearGps()
    @Insert suspend fun insertMove(p: MovePoint)
    @Query("SELECT * FROM movement_points WHERE date=:d ORDER BY timestamp") fun moveFlow(d: String): Flow<List<MovePoint>>
    @Query("SELECT * FROM movement_points WHERE date=:d ORDER BY timestamp DESC LIMIT 1") suspend fun lastMove(d: String): MovePoint?
    @Query("SELECT * FROM movement_points ORDER BY timestamp") suspend fun allMoves(): List<MovePoint>
    @Query("DELETE FROM movement_points") suspend fun clearMoves()
    @Query("DELETE FROM step_events") suspend fun clearEvents()
    @Query("DELETE FROM daily_summary") suspend fun clearSummaries()
}

@Database(entities = [StepEvent::class, DailySummary::class, MovePoint::class, GpsPoint::class, Place::class], version = 5, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): StepDao
    companion object {
        @Volatile private var i: AppDb? = null
        fun get(c: Context): AppDb = i ?: synchronized(this) {
            i ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "steps.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { i = it } }
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `places` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `radius` REAL NOT NULL)")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `step_events` ADD COLUMN `alt` REAL NOT NULL DEFAULT -9999")
        db.execSQL("ALTER TABLE `gps_points` ADD COLUMN `alt` REAL NOT NULL DEFAULT -9999")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `gps_points` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `date` TEXT NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `accuracy` REAL NOT NULL, `speed` REAL NOT NULL, `heading` REAL NOT NULL, `x` REAL NOT NULL, `y` REAL NOT NULL, `distance` REAL NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_gps_points_date` ON `gps_points` (`date`)")
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `movement_points` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `date` TEXT NOT NULL, `heading` REAL NOT NULL, `speed` REAL NOT NULL, `x` REAL NOT NULL, `y` REAL NOT NULL, `distance` REAL NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_movement_points_date` ON `movement_points` (`date`)")
    }
}

class Prefs(c: Context) {
    var elevOn: Boolean get() = sp.getBoolean("elevOn", true); set(v) = sp.edit().putBoolean("elevOn", v).apply()
    var moveOn: Boolean get() = sp.getBoolean("moveOn", false); set(v) = sp.edit().putBoolean("moveOn", v).apply()
    var moveSession: Long get() = sp.getLong("moveSession", 0L); set(v) = sp.edit().putLong("moveSession", v).apply()
    val sp = c.getSharedPreferences("st", 0)
    var goal: Int get() = sp.getInt("goal", 10000); set(v) = sp.edit().putInt("goal", v).apply()
    var stride: Float get() = sp.getFloat("stride", 0.8f); set(v) = sp.edit().putFloat("stride", v).apply()
    var weight: Float get() = sp.getFloat("weight", 70f); set(v) = sp.edit().putFloat("weight", v).apply()
    var sessionStart: Long get() = sp.getLong("session", 0L); set(v) = sp.edit().putLong("session", v).apply()
    var replaySpeed: Float get() = sp.getFloat("replaySpeed", 1f); set(v) = sp.edit().putFloat("replaySpeed", v).apply()
    var groundAlt: Float get() = sp.getFloat("groundAlt", -9999f); set(v) = sp.edit().putFloat("groundAlt", v).apply()
    var lastCounter: Long get() = sp.getLong("lastCounter", -1L); set(v) = sp.edit().putLong("lastCounter", v).apply()
}

object LiveState {
    val heading = MutableStateFlow(0f); val accuracy = MutableStateFlow(0); val steps = MutableStateFlow(0); val speed = MutableStateFlow(0f); val moveOn = MutableStateFlow(false); val gpsStatus = MutableStateFlow("Off"); val backupStatus = MutableStateFlow(""); val alt = MutableStateFlow(NO_ALT); val altAnchored = MutableStateFlow(false); val baro = MutableStateFlow(false); val fix = MutableStateFlow<Triple<Double, Double, Long>?>(null)
}

fun buildSummary(d: String, ev: List<StepEvent>, stride: Float, weight: Float): DailySummary {
    val cnt = BUCKETS.map { b -> ev.count { it.directionBucket == b } }
    val dom = if (ev.isEmpty()) "-" else BUCKETS[cnt.indexOf(cnt.max())]
    val km = ev.size * stride / 1000f
    val path = JSONArray()
    ev.filterIndexed { i, _ -> i % 10 == 0 }.forEach { path.put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) }
    val o = JSONObject(); BUCKETS.forEachIndexed { i, b -> o.put(b, cnt[i]) }
    return DailySummary(d, ev.size, km * 1000f, km * weight * 0.57f, ev.map { it.timestamp / 60000 }.distinct().size, dom, o.toString(), path.toString())
}
