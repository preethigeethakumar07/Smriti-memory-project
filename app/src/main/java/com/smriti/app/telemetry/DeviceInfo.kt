package com.smriti.app.telemetry

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class CameraSensorInfo(
    val id: String,
    val resolution: String,
    val sensorSizeMm: String,
    val focalLengthMm: String,
    val aperture: String,
    val fpsRanges: String,
    val hardwareLevel: String,
    val stabilization: Boolean
)

data class SensorEntry(val name: String, val type: String, val vendor: String, val powerMa: Float)

object DeviceInfo {

    val soc: String
        get() = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE

    val summary: String get() = "${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    fun backCamera(context: Context): CameraSensorInfo? = runCatching {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.first {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        val c = cm.getCameraCharacteristics(id)
        val px = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val ap = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        val fps = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        CameraSensorInfo(
            id = id,
            resolution = px?.let { "${it.width} x ${it.height} (${"%.1f".format(it.width * it.height / 1_000_000f)} MP)" } ?: "n/a",
            sensorSizeMm = size?.let { "${"%.2f".format(it.width)} x ${"%.2f".format(it.height)} mm" } ?: "n/a",
            focalLengthMm = focal?.joinToString { "%.1f".format(it) + " mm" } ?: "n/a",
            aperture = ap?.joinToString { "f/%.1f".format(it) } ?: "n/a",
            fpsRanges = fps?.map { it.upper }?.distinct()?.sorted()?.joinToString("/") { "$it" }?.plus(" fps max") ?: "n/a",
            hardwareLevel = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "Legacy"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "Limited"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "Full"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "Level 3"
                else -> "External"
            },
            stabilization = (ois?.size ?: 0) > 1
        )
    }.getOrNull()

    fun sensors(context: Context): List<SensorEntry> {
        val sm = context.getSystemService(SensorManager::class.java)
        return sm.getSensorList(Sensor.TYPE_ALL)
            .map { SensorEntry(it.name, it.stringType.substringAfterLast('.'), it.vendor, it.power) }
            .sortedBy { it.type }
    }

    /** Live values for light and acceleration; only registered while the Sensors tab collects it. */
    fun liveSensors(context: Context): Flow<Map<String, Float>> = callbackFlow {
        val sm = context.getSystemService(SensorManager::class.java)
        val values = HashMap<String, Float>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_LIGHT -> values["light"] = e.values[0]
                    Sensor.TYPE_ACCELEROMETER ->
                        values["accel"] = kotlin.math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                    Sensor.TYPE_PROXIMITY -> values["proximity"] = e.values[0]
                }
                trySend(HashMap(values))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        listOf(Sensor.TYPE_LIGHT, Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_PROXIMITY).forEach { t ->
            sm.getDefaultSensor(t)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        }
        awaitClose { sm.unregisterListener(listener) }
    }
}
