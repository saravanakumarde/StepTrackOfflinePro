package com.steptrack.offline
import kotlin.math.cos
import kotlin.math.sin

/** Virtual dead-reckoning: X += stride*sin(h), Y += stride*cos(h). Resets each day or on New Session. */
class DirectionManager(private val dao: StepDao) {
    private var date = ""; var x = 0f; private set; var y = 0f; private set
    suspend fun ensure(d: String, sessionStart: Long) {
        if (d == date) return
        date = d
        val l = dao.last(d)
        if (l != null && l.timestamp >= sessionStart) { x = l.x; y = l.y } else { x = 0f; y = 0f }
    }
    fun reset() { x = 0f; y = 0f }
    fun advance(headingDeg: Float, stride: Float) {
        val r = Math.toRadians(headingDeg.toDouble())
        x += (stride * sin(r)).toFloat(); y += (stride * cos(r)).toFloat()
    }
}
