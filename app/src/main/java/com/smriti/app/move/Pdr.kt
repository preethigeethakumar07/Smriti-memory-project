package com.smriti.app.move

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pedestrian dead reckoning: where the user is, from steps and the direction of each step, with no camera. Every step moves the
 * position one stride along the heading. A stride error of 3 to 6 % and a heading error of a few degrees make the position drift,
 * so [sigma] grows with the distance walked; a GPS fix pulls the position back by an amount that depends on how far each of them is
 * trusted, and fixes taken far apart on a straight walk also teach the stride length. Plain Kotlin, unit-tested.
 *
 * Honest limits: indoors the compass is bent by steel and electronics (10 to 25 degrees), so this is good for a room, a corridor or a
 * floor (a few metres), not for a whole building; outdoors GPS keeps it within a few metres.
 */
class PdrTracker(strideM: Double = DEFAULT_STRIDE_M) {
    /** position in metres east / north of the start */
    var x = 0.0; private set
    var y = 0.0; private set
    /** how far off the position may be, in metres (grows with distance walked, shrinks with each good GPS fix) */
    var sigma = 0.0; private set
    var steps = 0; private set
    var distanceM = 0.0; private set

    /** the stride length in use, in metres */
    var stride: Double = strideM; private set
    private var scale = 1.0

    // learning the stride length: GPS fixes far apart on a straight walk, against the steps alone (never the GPS-corrected position)
    private var rawX = 0.0
    private var rawY = 0.0
    private var anchorGps: Pt? = null
    private var anchorRaw: Pt? = null
    private var anchorDistance = 0.0

    /** start at ([startX], [startY]); [sigma0] says how far off that start may be (the accuracy of the GPS fix it came from) */
    fun reset(startX: Double = 0.0, startY: Double = 0.0, sigma0: Double = 0.0) {
        x = startX; y = startY; sigma = sigma0; steps = 0; distanceM = 0.0
        rawX = 0.0; rawY = 0.0; anchorGps = null; anchorRaw = null; anchorDistance = 0.0
    }

    /** move the position (the frame changed under it) without counting it as walking */
    fun moveTo(nx: Double, ny: Double) { x = nx; y = ny }

    fun setStride(m: Double) { stride = m.coerceIn(0.3, 1.2) }

    val effectiveStride: Double get() = stride * scale

    /** one step in compass direction [headingDeg] */
    fun step(headingDeg: Double) {
        val len = effectiveStride
        val h = Math.toRadians(headingDeg)
        x += len * sin(h)
        y += len * cos(h)
        rawX += len * sin(h)
        rawY += len * cos(h)
        steps++
        distanceM += len
        sigma += SIGMA_PER_M * len
    }

    /** the current position as a point */
    fun position() = Pt(x, y)

    /**
     * A GPS fix at ground position ([gx], [gy]) (in the same frame) with a stated accuracy in metres. Fixes worse than [MAX_ACCURACY_M]
     * are ignored. Returns how much of the way to the fix the position was moved (0..1).
     */
    fun fuse(gx: Double, gy: Double, accuracyM: Double): Double {
        if (accuracyM <= 0.0 || accuracyM > MAX_ACCURACY_M) return 0.0
        learnStride(gx, gy, accuracyM)
        val pdrVar = sigma * sigma + 1.0
        val gpsVar = accuracyM * accuracyM
        val k = pdrVar / (pdrVar + gpsVar)
        x += k * (gx - x)
        y += k * (gy - y)
        sigma = sqrt((1 - k) * pdrVar).coerceAtLeast(MIN_SIGMA)
        return k
    }

    private fun learnStride(gx: Double, gy: Double, accuracyM: Double) {
        if (accuracyM > LEARN_ACCURACY_M) return
        val aG = anchorGps; val aR = anchorRaw
        if (aG == null || aR == null) { anchorGps = Pt(gx, gy); anchorRaw = Pt(rawX, rawY); anchorDistance = distanceM; return }
        val walked = distanceM - anchorDistance
        if (walked < LEARN_MIN_M) return
        val stepsDisp = hypot(rawX - aR.x, rawY - aR.y)
        val gpsDisp = hypot(gx - aG.x, gy - aG.y)
        // only a fairly straight walk says anything about the stride
        if (stepsDisp / walked >= 0.85 && stepsDisp > 25.0) {
            val ratio = gpsDisp / stepsDisp
            scale = (scale * (0.7 + 0.3 * ratio)).coerceIn(0.7, 1.4)
        }
        anchorGps = Pt(gx, gy); anchorRaw = Pt(rawX, rawY); anchorDistance = distanceM
    }

    companion object {
        const val DEFAULT_STRIDE_M = 0.70
        /** growth of the position uncertainty per metre walked */
        const val SIGMA_PER_M = 0.05
        const val MIN_SIGMA = 1.0
        const val MAX_ACCURACY_M = 30.0
        private const val LEARN_ACCURACY_M = 8.0
        private const val LEARN_MIN_M = 40.0

        /** a typical stride from height: about 0.415 of it for walking */
        fun strideForHeightCm(cm: Double) = (cm / 100.0 * 0.415).coerceIn(0.4, 1.0)
    }
}

/**
 * Which way the user is walking, from the phone's orientation. [rot] is the 3x3 row-major matrix that turns device axes into
 * East / North / Up (the one Android's rotation vector gives). Held flat in the hand the top edge points where the user walks; held upright
 * like a camera, the back camera does. The axis that lies more along the ground is used, with some hysteresis so it does not flip near 45 degrees.
 */
class HeadingFromRotation {
    private var useTop = true

    /** compass heading 0..360 of the walking direction, or null when the phone points straight up or down (no horizontal direction) */
    fun heading(rot: FloatArray): Double? {
        // device +Y (top edge) and -Z (back camera) as world vectors
        val tE = rot[1].toDouble(); val tN = rot[4].toDouble()
        val cE = -rot[2].toDouble(); val cN = -rot[5].toDouble()
        val top = hypot(tE, tN); val cam = hypot(cE, cN)
        if (top < 0.2 && cam < 0.2) return null
        useTop = if (useTop) !(cam > top * 1.25) else (top > cam * 1.25)
        return headingOf(rot)
    }

    /** the heading of another orientation matrix (the gyroscope-only one) along the axis the last [heading] call chose, so both agree */
    fun headingOf(rot: FloatArray): Double? {
        val e = if (useTop) rot[1].toDouble() else -rot[2].toDouble()
        val n = if (useTop) rot[4].toDouble() else -rot[5].toDouble()
        if (hypot(e, n) < 0.05) return null
        return Geo.wrap360(Math.toDegrees(kotlin.math.atan2(e, n)))
    }

    /** true when the top edge (not the back camera) is the axis in use */
    val usingTopEdge: Boolean get() = useTop
}
