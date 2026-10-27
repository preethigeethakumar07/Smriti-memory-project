package com.smriti.app.move

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A person walking, simulated: it feeds the walk guide the same readings the phone's sensors would give (steps, the gyroscope and
 * compass directions, GPS fixes with noise) on a virtual clock, and does what the guide says (turns when told to). It is how the walk
 * guide is tested on a computer, and, through a debug command, on the phone itself without anyone walking.
 *
 * What it assumes: a stride of [strideM] every 560 ms; a GPS fix every second with an error of about [gpsNoiseM] metres; a compass that
 * is off by [compassBiasDeg] with some noise; a gyroscope that drifts a degree a minute; a phone that points [bodyOffsetDeg] off the
 * walking direction (0 in the hand, a quarter turn in a pocket).
 */
class MoveSim(
    private val controller: MoveController,
    seed: Long = 7,
    private val gpsNoiseM: Double = 4.0,
    private val compassBiasDeg: Double = 0.0,
    private val compassNoiseDeg: Double = 2.0,
    private val strideM: Double = 0.72,
    private val gpsOn: Boolean = true,
    private val bodyOffsetDeg: Double = 0.0,
    /** how much of a small heading error a person corrects without being told (0 = none) */
    private val steering: Double = 0.0,
    /** where the virtual clock starts, in milliseconds */
    startMs: Long = 1_000L,
    /** called after every slice of virtual time; the demo sleeps in it so the walk plays in (faster than) real time, and throws to stop */
    private val pace: ((Int) -> Unit)? = null
) {
    private val rnd = java.util.Random(seed)
    private val map = LatLon(12.9716, 77.5946)
    private val gyroNorth = 123.0
    private var drift = 0.0
    private var lastFixAt = -10_000L

    /** the virtual clock in milliseconds */
    var t = startMs; private set
    var truth = Pt(0.0, 0.0); private set
    var trueHeading = 0.0; private set
    var stepsTaken = 0; private set

    /** where the person really is, as a map position */
    fun latLon(): LatLon = Geo.fromLocal(map, truth.x, truth.y)

    fun mapPoint(x: Double, y: Double): LatLon = Geo.fromLocal(map, x, y)

    /** puts the person at ([x], [y]) metres from the map's start, facing [headingDeg], and lets the sensors settle for a few seconds */
    fun place(x: Double, y: Double, headingDeg: Double) {
        truth = Pt(x, y); trueHeading = Geo.wrap360(headingDeg)
        repeat(30) { idle(100) }
        if (gpsOn) repeat(6) { idle(1000); sendFix(moving = false) }
    }

    /** stands still for [ms] while the sensors keep reporting */
    fun idle(ms: Int) {
        var left = ms
        while (left > 0) { val d = minOf(left, 100); t += d; drift += 0.0167 * d / 1000.0; sendOrientation(); controller.tick(t); pace?.invoke(d); left -= d }
    }

    /** turns on the spot to face [headingDeg] */
    fun faceTowards(headingDeg: Double) {
        var guard = 0
        while (abs(Geo.diff(headingDeg, trueHeading)) > 3.0 && guard++ < 60) {
            trueHeading = Geo.wrap360(trueHeading + Geo.diff(headingDeg, trueHeading).coerceIn(-30.0, 30.0))
            idle(100)
        }
        trueHeading = Geo.wrap360(headingDeg)
    }

    /** walks one stride in the direction the person faces */
    fun stepOnce() {
        repeat(5) { idle(112) }
        val h = Math.toRadians(trueHeading)
        truth = Pt(truth.x + strideM * sin(h), truth.y + strideM * cos(h))
        stepsTaken++
        controller.onStep(t)
        if (gpsOn && t - lastFixAt >= 1000) sendFix(moving = true)
    }

    /** walks [metres] in direction [headingDeg] (turning first) */
    fun walkLeg(headingDeg: Double, metres: Double) {
        faceTowards(headingDeg)
        repeat((metres / strideM).toInt()) { stepOnce() }
    }

    /** does what the guide says: turns the way it says, by what it says */
    fun obey() {
        val s = controller.state.value
        if (s.mode != MoveMode.GUIDING) return
        when (s.cue) {
            CueType.LEFT, CueType.RIGHT, CueType.SLIGHT_LEFT, CueType.SLIGHT_RIGHT, CueType.U_TURN, CueType.OFF_ROUTE ->
                trueHeading = Geo.wrap360(trueHeading + s.errDeg.toDouble().coerceIn(-35.0, 35.0))
            CueType.STRAIGHT -> trueHeading = Geo.wrap360(trueHeading + s.errDeg * steering)
            else -> {}
        }
    }

    /** walks as told until the guide says the walk is over (or [maxSteps] strides); returns the strides taken */
    fun follow(maxSteps: Int): Int {
        var n = 0
        while (n < maxSteps && controller.state.value.mode == MoveMode.GUIDING) { obey(); stepOnce(); n++ }
        return n
    }

    fun distanceTo(x: Double, y: Double) = Geo.distance(truth, Pt(x, y))

    private fun sendOrientation() {
        val phone = Geo.wrap360(trueHeading + bodyOffsetDeg)
        val game = Geo.wrap360(phone - gyroNorth + drift)
        val compass = Geo.wrap360(phone + compassBiasDeg + rnd.nextGaussian() * compassNoiseDeg)
        controller.onOrientation(t, game, compass, 45.0 + rnd.nextGaussian() * 0.8)
    }

    /** a GPS fix; while standing still there is no speed to speak of and no direction of travel, as on the real phone */
    private fun sendFix(moving: Boolean) {
        lastFixAt = t
        val p = Geo.fromLocal(map, truth.x + rnd.nextGaussian() * gpsNoiseM, truth.y + rnd.nextGaussian() * gpsNoiseM)
        controller.onFix(
            t,
            GpsFix(
                p.lat, p.lon, gpsNoiseM * 1.2,
                if (moving) strideM / 0.56 else 0.0,
                if (moving) Geo.wrap360(trueHeading + rnd.nextGaussian() * 6.0) else null,
                if (moving) 10.0 else null
            )
        )
    }
}
