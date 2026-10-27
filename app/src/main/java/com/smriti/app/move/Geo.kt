package com.smriti.app.move

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/** A point on the ground in metres: [x] east and [y] north of a chosen start. */
data class Pt(val x: Double, val y: Double)

/**
 * Flat-earth geometry for walking distances (up to a few kilometres): distances, bearings and conversions between latitude / longitude
 * and metres east / north of a start. Bearings are compass degrees, 0 = north, 90 = east. Plain Kotlin, unit-tested.
 */
object Geo {
    private const val EARTH_M = 6_371_000.0

    fun distance(a: LatLon, b: LatLon): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1; val dl = Math.toRadians(b.lon - a.lon)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** the compass direction from [a] to [b], 0..360 */
    fun bearing(a: LatLon, b: LatLon): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat); val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return wrap360(Math.toDegrees(atan2(y, x)))
    }

    fun toLocal(origin: LatLon, p: LatLon): Pt = Pt(
        Math.toRadians(p.lon - origin.lon) * EARTH_M * cos(Math.toRadians(origin.lat)),
        Math.toRadians(p.lat - origin.lat) * EARTH_M
    )

    fun fromLocal(origin: LatLon, east: Double, north: Double): LatLon = LatLon(
        origin.lat + Math.toDegrees(north / EARTH_M),
        origin.lon + Math.toDegrees(east / (EARTH_M * cos(Math.toRadians(origin.lat))))
    )

    fun wrap360(deg: Double): Double { val x = deg % 360.0; return if (x < 0) x + 360.0 else x }

    /** the signed turn from [current] to [target]: positive means turn right (clockwise), negative left, -180..180 */
    fun diff(target: Double, current: Double): Double {
        var d = (target - current) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    /** the compass direction from one ground point to another */
    fun bearing(from: Pt, to: Pt): Double = wrap360(Math.toDegrees(atan2(to.x - from.x, to.y - from.y)))

    fun distance(a: Pt, b: Pt): Double = hypot(a.x - b.x, a.y - b.y)

    /** the average of compass directions (a plain average of 359 and 1 would be 180) */
    fun circularMean(degrees: List<Double>): Double {
        if (degrees.isEmpty()) return 0.0
        var s = 0.0; var c = 0.0
        for (d in degrees) { s += sin(Math.toRadians(d)); c += cos(Math.toRadians(d)) }
        return wrap360(Math.toDegrees(atan2(s, c)))
    }
}
