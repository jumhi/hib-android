package com.sameerasw.airsync.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * H.I.B Flick aim: where the phone is pointing while the Flick screen (or a Point to act screen) is open.
 *
 * The aim direction is the sum of the phone's "top" axis and its "back" axis in world
 * coordinates, so it works whether the phone is held flat-ish (top points at the screen)
 * or upright (back points at the screen). Reported as a compass heading (degrees clockwise
 * from north) and an elevation (degrees above the horizon). Point & Flick matches it against
 * the saved screen map to pick the computer and the screen a flicked card lands on.
 */
object FlickAim : SensorEventListener {
    @Volatile private var heading: Float? = null
    @Volatile private var elevation: Float? = null
    private var manager: SensorManager? = null
    private val rotation = FloatArray(9)

    /** True when the phone has the rotation-vector sensor Point & Flick needs. */
    fun isAvailable(context: Context): Boolean =
        (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
            ?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null

    /** How many screens want the aim (Flick, Flick settings, Point to act); the sensor runs while > 0. */
    private var users = 0

    /** Starts the sensor (or joins it when another screen already did). Balance every call with [stop]. */
    @Synchronized
    fun start(context: Context) {
        users++
        if (manager != null) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return
        manager = sm
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    @Synchronized
    fun stop() {
        if (users > 0) users--
        if (users > 0) return
        manager?.unregisterListener(this)
        manager = null
        heading = null
        elevation = null
    }

    /** Latest (heading, elevation) or null if the sensor isn't available yet. */
    fun current(): Pair<Float, Float>? {
        val h = heading ?: return null
        return h to (elevation ?: 0f)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        // Columns of R are the device axes in world coords (x = east, y = north, z = up).
        val topX = rotation[1]; val topY = rotation[4]; val topZ = rotation[7]
        val backX = -rotation[2]; val backY = -rotation[5]; val backZ = -rotation[8]
        val x = topX + backX
        val y = topY + backY
        val z = topZ + backZ
        val len = sqrt(x * x + y * y + z * z)
        if (len < 1e-3f) return
        var h = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
        if (h < 0) h += 360f
        heading = h
        elevation = Math.toDegrees(asin((z / len).coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
