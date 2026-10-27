package com.smriti.app.spatial

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan

/**
 * Live orientation of the phone, best sensor first:
 *   1. rotation vector (gyroscope + accelerometer + compass fused by Android)  -> full orientation with true heading
 *   2. accelerometer + magnetometer                                           -> orientation with heading
 *   3. accelerometer only                                                      -> pitch is real, heading is NOT available
 * [capability] says which one is in use, and the app shows it, so nothing is claimed that the phone cannot measure.
 */
class PoseSensor(context: Context) : SensorEventListener {

    enum class Capability { FULL, COMPASS, GRAVITY_ONLY, NONE }

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVector = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnet = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    val capability: Capability = when {
        rotationVector != null -> Capability.FULL
        accel != null && magnet != null -> Capability.COMPASS
        accel != null -> Capability.GRAVITY_ONLY
        else -> Capability.NONE
    }

    private val _pose = MutableStateFlow<Pose?>(null)
    val pose: StateFlow<Pose?> = _pose.asStateFlow()

    @Volatile private var running = false
    private var lastPublish = 0L
    private val gravity = FloatArray(3)
    private val geomag = FloatArray(3)
    private var haveGravity = false
    private var haveMag = false
    private val r9 = FloatArray(9)
    private val inc = FloatArray(9)

    /** Orientation right now, or null before the first sensor reading. */
    fun snapshot(): Pose? = _pose.value

    fun start() {
        if (running || capability == Capability.NONE) return
        running = true
        val rate = SensorManager.SENSOR_DELAY_GAME
        when (capability) {
            Capability.FULL -> sm.registerListener(this, rotationVector, rate)
            Capability.COMPASS -> { sm.registerListener(this, accel, rate); sm.registerListener(this, magnet, rate) }
            else -> sm.registerListener(this, accel, rate)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        sm.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                if (now - lastPublish < 60) return
                SensorManager.getRotationMatrixFromVector(r9, e.values)
                publish(Pose(r9.copyOf(), headingValid = true, at = now), now)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // low-pass so a shaking hand does not make the picture's direction jitter
                for (i in 0..2) gravity[i] = if (!haveGravity) e.values[i] else gravity[i] * 0.85f + e.values[i] * 0.15f
                haveGravity = true
                if (now - lastPublish < 60) return
                if (capability == Capability.COMPASS && haveMag && SensorManager.getRotationMatrix(r9, inc, gravity, geomag)) {
                    publish(Pose(r9.copyOf(), headingValid = true, at = now), now)
                } else if (capability != Capability.FULL) {
                    publish(Pose.fromGravity(gravity[0], gravity[1], gravity[2], now), now)
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                for (i in 0..2) geomag[i] = if (!haveMag) e.values[i] else geomag[i] * 0.85f + e.values[i] * 0.15f
                haveMag = true
            }
        }
    }

    private fun publish(p: Pose, now: Long) { lastPublish = now; _pose.value = p }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

/** Field of view of the back camera's upright (portrait) picture, from the real lens and sensor sizes. */
object CameraOptics {
    @Volatile private var cached: Fov? = null

    fun fov(context: Context): Fov {
        cached?.let { return it }
        val result = runCatching {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            val c = cm.getCameraCharacteristics(id)
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!!.first()
            val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!       // landscape: width x height in mm
            // upright portrait picture: the sensor's short side is horizontal, its long side is vertical
            val shortSide = minOf(size.width, size.height)
            val longSide = maxOf(size.width, size.height)
            Fov(
                hDeg = Math.toDegrees(2.0 * atan(shortSide / (2.0 * focal))).toFloat(),
                vDeg = Math.toDegrees(2.0 * atan(longSide / (2.0 * focal))).toFloat()
            )
        }.getOrDefault(Fov.TYPICAL)
        cached = result
        return result
    }
}
