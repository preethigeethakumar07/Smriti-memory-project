package com.smriti.app.move

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.smriti.app.memory.MoveDao
import com.smriti.app.memory.MovePlaceEntity
import com.smriti.app.memory.MoveRouteEntity
import com.smriti.app.util.Haptics
import com.smriti.app.util.TtsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sqrt

/** the walk guide's saved places and walks, in the phone's database */
class RoomMoveStore(private val dao: MoveDao) : MoveStore {
    override suspend fun home() = dao.home()
    override suspend fun places() = dao.places()
    override suspend fun savePlace(p: MovePlaceEntity): Long {
        if (p.kind == "home") dao.clearHome()
        return dao.insertPlace(p)
    }
    override suspend fun route(id: Long) = dao.route(id)
    override suspend fun lastTrip() = dao.lastTrip()
    override suspend fun saveRoute(r: MoveRouteEntity): Long {
        if (r.id == 0L) return dao.insertRoute(r)
        dao.updateRoute(r)
        return r.id
    }
}

/** the phone's own voice and vibration motor */
class AndroidMoveOutput(private val haptics: Haptics) : MoveOutput {
    override fun speak(text: String, languageTag: String) = TtsManager.speak(text, languageTag)
    override fun buzz(h: HapticId) = haptics.walkCue(h)
    override fun pulse(onMs: Int, amplitude: Int) = haptics.walkPulse(onMs, amplitude)
}

/** what the sensors are doing, for the walk guide screen */
data class SensorDiag(
    val steps: Boolean = false,
    val gyroscope: Boolean = false,
    val compass: Boolean = false,
    val gps: Boolean = false,
    val locationOn: Boolean = true,
    val wifiNetworks: Int = 0,
    val needsLocation: Boolean = false,
    val needsActivity: Boolean = false
)

/**
 * Reads the phone's sensors and hands them to the [MoveController]: the step detector, the gyroscope-based and the compass-based
 * rotation (and the magnetic field strength, to know when the compass is being fooled), GPS fixes, and the Wi-Fi networks around.
 * It runs only while somebody needs it ([retain] / [release]): a guided walk, or the walk guide screen being open.
 */
class MoveSensors(private val context: Context, private val controller: MoveController) {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val lm = context.getSystemService(LocationManager::class.java)
    private val wm = context.applicationContext.getSystemService(WifiManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val owners = HashSet<String>()
    private var running = false

    private val _diag = MutableStateFlow(SensorDiag())
    val diag: StateFlow<SensorDiag> = _diag.asStateFlow()

    /** while true the real sensors are listened to but not passed on: a demo walk is feeding the walk guide instead (see MoveDemo) */
    @Volatile var muted = false

    // the latest readings, combined into one orientation report each time the gyroscope speaks
    private val rotCompass = FloatArray(9)
    private val rotGame = FloatArray(9)
    private val axis = HeadingFromRotation()
    private var compassHeading: Double? = null
    private var gameHeading: Double? = null
    private var haveGame = false
    private var fieldUt: Double? = null
    private var fieldAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
    private var declination = 0.0
    private var pitchRef = Double.NaN
    private var pitchRefAt = 0L
    private var poseChanged = false

    fun retain(owner: String) {
        main.post {
            owners += owner
            if (!running) start()
        }
    }

    fun release(owner: String) {
        main.post {
            owners -= owner
            if (owners.isEmpty() && running) stop()
        }
    }

    /** looks at the permissions again (after the user answered a request) */
    fun refreshPermissions() {
        main.post {
            if (running) { stop(); start() } else publish()
        }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    /** precise location and (on Android 10 and later) physical activity are both allowed */
    fun hasPermissions(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) && (Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACTIVITY_RECOGNITION))

    @SuppressLint("MissingPermission")
    private fun start() {
        running = true
        val hasLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val hasActivity = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACTIVITY_RECOGNITION)

        val rotation = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val game = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val field = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val step = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        haveGame = game != null
        compassHeading = null; gameHeading = null; fieldUt = null; pitchRef = Double.NaN
        rotation?.let { sm.registerListener(sensors, it, 40_000) }
        game?.let { sm.registerListener(sensors, it, 40_000) }
        field?.let { sm.registerListener(sensors, it, 200_000) }
        if (hasActivity) step?.let { sm.registerListener(sensors, it, SensorManager.SENSOR_DELAY_FASTEST) }

        if (hasLocation) {
            runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, location, Looper.getMainLooper()) }
            runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { l -> updateDeclination(l) } }
            main.post(wifiPoll)
        }
        _diag.value = SensorDiag(
            steps = hasActivity && step != null, gyroscope = game != null, compass = rotation != null,
            gps = hasLocation, locationOn = runCatching { lm.isLocationEnabled }.getOrDefault(true),
            needsLocation = !hasLocation, needsActivity = !hasActivity
        )
    }

    private fun stop() {
        running = false
        sm.unregisterListener(sensors)
        runCatching { lm.removeUpdates(location) }
        main.removeCallbacks(wifiPoll)
    }

    private fun publish() {
        _diag.value = _diag.value.copy(
            needsLocation = !granted(Manifest.permission.ACCESS_FINE_LOCATION),
            needsActivity = Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACTIVITY_RECOGNITION)
        )
    }

    private val sensors = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            if (muted) return
            when (e.sensor.type) {
                Sensor.TYPE_STEP_DETECTOR -> controller.onStep(SystemClock.elapsedRealtime())
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotCompass, e.values)
                    compassHeading = axis.heading(rotCompass)
                    trackPose(rotCompass)
                    if (!haveGame) report()
                }
                Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotGame, e.values)
                    gameHeading = axis.headingOf(rotGame)
                    report()
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    fieldUt = sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
                    fieldAccuracy = e.accuracy
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) fieldAccuracy = accuracy
        }
    }

    /** one orientation report: the gyroscope's heading and the compass's (turned to true north), with how far each can be trusted */
    private fun report() {
        val trusted = fieldAccuracy > SensorManager.SENSOR_STATUS_UNRELIABLE
        val compass = compassHeading?.takeIf { trusted }?.let { Geo.wrap360(it + declination) }
        val g = gameHeading ?: compassHeading?.let { Geo.wrap360(it + declination) }
        val changed = poseChanged
        poseChanged = false
        controller.onOrientation(
            SystemClock.elapsedRealtime(), g, compass, fieldUt,
            poseChanged = changed, compassPoor = fieldAccuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
        )
    }

    /** the phone was lifted out of a pocket (or put into one) when its top edge swings through a big angle within a couple of seconds */
    private fun trackPose(rot: FloatArray) {
        val now = SystemClock.elapsedRealtime()
        val pitch = Math.toDegrees(asin(rot[7].toDouble().coerceIn(-1.0, 1.0)))
        if (pitchRef.isNaN()) { pitchRef = pitch; pitchRefAt = now; return }
        if (now - pitchRefAt >= 2_000) {
            if (abs(pitch - pitchRef) > 40.0) poseChanged = true
            pitchRef = pitch; pitchRefAt = now
        }
    }

    private val location = LocationListener { loc: Location ->
        if (muted) return@LocationListener
        updateDeclination(loc)
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else null
        // the direction of travel means something only while moving
        val course = if (loc.hasBearing() && speed != null && speed > 0.5) loc.bearing.toDouble() else null
        val courseAcc = if (Build.VERSION.SDK_INT >= 26 && loc.hasBearingAccuracy()) loc.bearingAccuracyDegrees.toDouble() else null
        controller.onFix(
            SystemClock.elapsedRealtime(),
            GpsFix(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy.toDouble() else 50.0, speed, course, courseAcc)
        )
        if (!_diag.value.gps || !_diag.value.locationOn) _diag.value = _diag.value.copy(gps = true, locationOn = true)
    }

    private fun updateDeclination(l: Location) {
        declination = runCatching {
            GeomagneticField(l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(), System.currentTimeMillis()).declination.toDouble()
        }.getOrDefault(0.0)
    }

    // ---- Wi-Fi: only the addresses of the strongest networks, hashed, to tell one place from another
    private var lastScanAt = 0L

    private val wifiPoll = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            if (!running) return
            runCatching {
                val now = SystemClock.elapsedRealtime()
                if (now - lastScanAt > 60_000) { lastScanAt = now; @Suppress("DEPRECATION") wm.startScan() }
                val results = wm.scanResults.sortedByDescending { it.level }.take(8)
                val print = WifiPrint.encode(results.map { WifiPrint.hash(it.BSSID ?: "") })
                controller.onWifi(print)
                if (_diag.value.wifiNetworks != results.size) _diag.value = _diag.value.copy(wifiNetworks = results.size)
            }
            main.postDelayed(this, 10_000)
        }
    }
}
