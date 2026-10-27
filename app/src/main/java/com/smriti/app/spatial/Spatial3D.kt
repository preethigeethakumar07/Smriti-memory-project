package com.smriti.app.spatial

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Where the phone is pointing, and where things are, in a room-sized frame: x = East, y = North, z = Up, in metres,
 * with the origin where the phone was held.
 *
 * What is MEASURED: the phone's orientation (from the rotation sensor), the camera's field of view.
 * What is ESTIMATED: how far an object is, from the size its box has in the picture compared with the real size such
 * an object usually has. So every position here is an estimate, and it is labelled that way in the app.
 *
 * Pure maths, no Android classes, so it is unit-tested on the JVM.
 */

/** Phone orientation. [rot] is the 3x3 row-major matrix that turns device coordinates into East/North/Up. */
class Pose(
    val rot: FloatArray,
    /** false when the phone has no compass or gyroscope: pitch is real, but there is no true heading */
    val headingValid: Boolean,
    val at: Long = 0L
) {
    /** compass heading of the camera axis, 0..360 (0 = North) */
    val azimuthDeg: Float get() { val f = forward(); return ((Math.toDegrees(atan2(f[0].toDouble(), f[1].toDouble())).toFloat()) + 360f) % 360f }

    /** how far the camera points above (+) or below (-) the horizon */
    val pitchDeg: Float get() = Math.toDegrees(asin(forward()[2].coerceIn(-1f, 1f).toDouble())).toFloat()

    /** the back camera looks along -Z of the device */
    fun forward(): FloatArray = Spatial3D.toWorld(rot, floatArrayOf(0f, 0f, -1f))

    companion object {
        /** No heading sensor: build the frame from gravity alone. "North" is whichever way the camera happens to point. */
        fun fromGravity(ax: Float, ay: Float, az: Float, at: Long = 0L): Pose {
            val n = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1e-6f)
            val up = floatArrayOf(ax / n, ay / n, az / n)
            val fwdDev = floatArrayOf(0f, 0f, -1f)
            val d = fwdDev[0] * up[0] + fwdDev[1] * up[1] + fwdDev[2] * up[2]
            var north = floatArrayOf(fwdDev[0] - d * up[0], fwdDev[1] - d * up[1], fwdDev[2] - d * up[2])
            if (norm(north) < 0.2f) north = floatArrayOf(0f, 1f, 0f)          // phone flat: use the top edge instead
            north = normalise(north)
            val east = cross(north, up)
            return Pose(floatArrayOf(east[0], east[1], east[2], north[0], north[1], north[2], up[0], up[1], up[2]), headingValid = false, at = at)
        }

        private fun norm(v: FloatArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        private fun normalise(v: FloatArray): FloatArray { val n = norm(v).coerceAtLeast(1e-6f); return floatArrayOf(v[0] / n, v[1] / n, v[2] / n) }
        private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    }
}

/** Field of view of the upright (portrait) picture. */
data class Fov(val hDeg: Float, val vDeg: Float) {
    companion object { val TYPICAL = Fov(54f, 69f) }
}

/** One object placed in the room. */
data class Point3(
    val label: String,
    val x: Float, val y: Float, val z: Float,
    val distance: Float,
    /** compass bearing from the phone to the object, 0..360 (only meaningful when [headingValid]) */
    val azimuthDeg: Float,
    /** angle above (+) or below (-) the horizon */
    val elevationDeg: Float,
    /** 0..1: 0.6 when the distance came from a known object size, 0.2 when it is only a guess */
    val confidence: Float,
    val headingValid: Boolean
)

/** What to tell the user: how far, which way to turn, up or down. */
data class Relative(val distance: Float, val turnDeg: Float, val upDeg: Float, val headingValid: Boolean)

object Spatial3D {

    /** Typical real size of common things: ("h" = height, "w" = width) in metres. Used only to estimate distance. */
    private val SIZES: Map<String, Pair<Char, Float>> = mapOf(
        "person" to ('h' to 1.7f), "chair" to ('h' to 0.9f), "couch" to ('w' to 1.9f), "bed" to ('w' to 1.9f), "dining table" to ('w' to 1.4f),
        "tv" to ('w' to 1.0f), "laptop" to ('w' to 0.34f), "keyboard" to ('w' to 0.44f), "mouse" to ('w' to 0.07f), "cell phone" to ('h' to 0.15f),
        "bottle" to ('h' to 0.24f), "cup" to ('h' to 0.10f), "book" to ('h' to 0.22f), "backpack" to ('h' to 0.45f), "handbag" to ('w' to 0.35f),
        "suitcase" to ('h' to 0.65f), "umbrella" to ('h' to 0.9f), "remote" to ('w' to 0.18f), "scissors" to ('h' to 0.2f), "clock" to ('w' to 0.3f),
        "vase" to ('h' to 0.3f), "potted plant" to ('h' to 0.5f), "refrigerator" to ('h' to 1.7f), "microwave" to ('w' to 0.5f), "oven" to ('w' to 0.6f),
        "sink" to ('w' to 0.6f), "toilet" to ('h' to 0.4f), "bowl" to ('w' to 0.15f), "wine glass" to ('h' to 0.2f), "teddy bear" to ('h' to 0.3f),
        "toothbrush" to ('h' to 0.18f), "car" to ('w' to 1.8f), "bicycle" to ('w' to 1.7f), "bench" to ('w' to 1.5f), "knife" to ('h' to 0.22f),
        "fork" to ('h' to 0.19f), "spoon" to ('h' to 0.18f), "banana" to ('w' to 0.18f), "apple" to ('w' to 0.08f), "orange" to ('w' to 0.08f)
    )

    /** Direction of the pixel (u across 0..1, v down 0..1) in device coordinates (x right, y up, z out of the screen). */
    fun rayDevice(u: Float, v: Float, fov: Fov): FloatArray {
        val tx = tan(Math.toRadians(fov.hDeg / 2.0)).toFloat()
        val ty = tan(Math.toRadians(fov.vDeg / 2.0)).toFloat()
        val x = (u - 0.5f) * 2f * tx
        val y = -(v - 0.5f) * 2f * ty
        val n = sqrt(x * x + y * y + 1f)
        return floatArrayOf(x / n, y / n, -1f / n)
    }

    fun toWorld(rot: FloatArray, d: FloatArray) = floatArrayOf(
        rot[0] * d[0] + rot[1] * d[1] + rot[2] * d[2],
        rot[3] * d[0] + rot[4] * d[1] + rot[5] * d[2],
        rot[6] * d[0] + rot[7] * d[1] + rot[8] * d[2]
    )

    /** Distance from how big the box is. Null when the thing has no known size. */
    fun distanceFromSize(label: String, widthNorm: Float, heightNorm: Float, fov: Fov): Float? {
        val (dim, metres) = SIZES[label] ?: return null
        val frac = if (dim == 'h') heightNorm else widthNorm
        if (frac < 0.02f) return null
        val fovDeg = if (dim == 'h') fov.vDeg else fov.hDeg
        val d = metres / (frac * 2f * tan(Math.toRadians(fovDeg / 2.0)).toFloat())
        return d.coerceIn(0.2f, 12f)
    }

    /** Puts a detection (box in 0..1 picture coordinates) into the room. */
    fun place(label: String, left: Float, top: Float, right: Float, bottom: Float, pose: Pose, fov: Fov): Point3 {
        val u = (left + right) / 2f
        val v = (top + bottom) / 2f
        val dirW = toWorld(pose.rot, rayDevice(u, v, fov))
        val known = distanceFromSize(label, right - left, bottom - top, fov)
        val dist = known ?: 2.0f
        val az = ((Math.toDegrees(atan2(dirW[0].toDouble(), dirW[1].toDouble())).toFloat()) + 360f) % 360f
        val el = Math.toDegrees(asin(dirW[2].coerceIn(-1f, 1f).toDouble())).toFloat()
        return Point3(label, dirW[0] * dist, dirW[1] * dist, dirW[2] * dist, dist, az, el, if (known != null) 0.6f else 0.2f, pose.headingValid)
    }

    /** From where the user is looking now to a stored object. */
    fun relative(target: Point3, now: Pose): Relative {
        val turn = wrap180(target.azimuthDeg - now.azimuthDeg)
        return Relative(target.distance, turn, target.elevationDeg - now.pitchDeg, target.headingValid && now.headingValid)
    }

    fun wrap180(deg: Float): Float { var d = deg % 360f; if (d > 180f) d -= 360f; if (d < -180f) d += 360f; return d }

    /** Words for [Relative]. Direction is only claimed when the phone has a compass. */
    fun describe(r: Relative): String {
        val parts = ArrayList<String>(3)
        parts += "about ${"%.1f".format(r.distance)} m away"
        if (r.headingValid) {
            val a = abs(r.turnDeg)
            parts += when {
                a < 15f -> "straight ahead"
                a > 135f -> "behind you"
                r.turnDeg < 0 -> "${a.toInt()} degrees to your left"
                else -> "${a.toInt()} degrees to your right"
            }
        }
        if (abs(r.upDeg) > 10f) parts += if (r.upDeg > 0) "a little above eye level" else "a little below eye level"
        return parts.joinToString(", ")
    }

    /** Angle between two directions on the sphere, in degrees (used to match the same object across frames). */
    fun angleBetween(az1: Float, el1: Float, az2: Float, el2: Float): Float {
        val a1 = Math.toRadians(az1.toDouble()); val e1 = Math.toRadians(el1.toDouble())
        val a2 = Math.toRadians(az2.toDouble()); val e2 = Math.toRadians(el2.toDouble())
        val dot = cos(e1) * cos(e2) * cos(a1 - a2) + sin(e1) * sin(e2)
        return Math.toDegrees(acos(dot.coerceIn(-1.0, 1.0))).toFloat()
    }

    @Suppress("unused") private fun halfFov(deg: Float) = atan(Math.toRadians(deg / 2.0))
}
