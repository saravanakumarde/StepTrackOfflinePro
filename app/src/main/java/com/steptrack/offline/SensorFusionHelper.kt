package com.steptrack.offline
import android.content.Context
import android.hardware.*
import kotlin.math.abs

/** Azimuth 0-360 (magnetic north). TYPE_ROTATION_VECTOR, else ACCELEROMETER + MAGNETIC_FIELD. Auto-remaps when phone is upright. */
class SensorFusionHelper(ctx: Context, private val cb: (Float, Int) -> Unit) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rot = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val g = FloatArray(3); private val m = FloatArray(3); private var hm = false; private var hg = false
    private val r = FloatArray(9); private val r2 = FloatArray(9); private val o = FloatArray(3)
    @Volatile var heading = 0f; private var init = false; private var acc = 0

    fun start() {
        val d = SensorManager.SENSOR_DELAY_UI
        if (rot != null) sm.registerListener(this, rot, d) else {
            sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), d)
            sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD), d)
        }
    }
    fun stop() = sm.unregisterListener(this)
    override fun onAccuracyChanged(s: Sensor, a: Int) { if (a != acc) AppLog.i("Compass", "accuracy ${s.name}: $acc -> $a"); acc = a; cb(heading, a) }
    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> { SensorManager.getRotationMatrixFromVector(r, e.values); update() }
            Sensor.TYPE_ACCELEROMETER -> { lp(g, e.values); hg = true; if (hm && SensorManager.getRotationMatrix(r, null, g, m)) update() }
            Sensor.TYPE_MAGNETIC_FIELD -> { lp(m, e.values); hm = true }
        }
    }
    private fun lp(dst: FloatArray, src: FloatArray) { for (i in 0..2) dst[i] += 0.15f * (src[i] - dst[i]) }
    private val rm = FloatArray(9)
    /** Rotate a device-frame vector into earth frame (x=East, y=North, z=Up). */
    fun toEarth(v: FloatArray, out: FloatArray) {
        out[0] = rm[0] * v[0] + rm[1] * v[1] + rm[2] * v[2]
        out[1] = rm[3] * v[0] + rm[4] * v[1] + rm[5] * v[2]
        out[2] = rm[6] * v[0] + rm[7] * v[1] + rm[8] * v[2]
    }
    private fun update() {
        System.arraycopy(r, 0, rm, 0, 9)
        SensorManager.getOrientation(r, o); var az = o[0]
        if (abs(o[1]) > 0.8f) { // phone upright / in hand: use back-camera axis
            SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, r2)
            SensorManager.getOrientation(r2, o); az = o[0]
        }
        val deg = ((Math.toDegrees(az.toDouble()).toFloat()) + 360f) % 360f
        if (!init) { heading = deg; init = true } else {
            var d = deg - heading; if (d > 180) d -= 360; if (d < -180) d += 360
            heading = (heading + 0.2f * d + 360f) % 360f
        }
        cb(heading, acc)
    }
}
