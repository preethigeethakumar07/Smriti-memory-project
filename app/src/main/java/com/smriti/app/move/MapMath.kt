package com.smriti.app.move

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * What the path drawing shows (see ui/screens/PathMap.kt), in metres east ([Pt.x]) and north ([Pt.y]) of where the walk began.
 *  - [out]: the walk that was recorded, in the order it was walked;
 *  - [back]: where the person has walked since the guide started leading them;
 *  - [route]: the way they are being led along, in the order to walk it;
 *  - [here] and [headingDeg]: where the person is and the direction they walk in (null when nothing is running);
 *  - [start], [dest] and [destLabel]: where the walk began and where the guide is leading to.
 * A plain class on purpose: every new snapshot is a new object, so nothing compares the (long) lists.
 */
class Trail(
    val out: List<Pt> = emptyList(),
    val back: List<Pt> = emptyList(),
    val route: List<Pt> = emptyList(),
    val here: Pt? = null,
    val headingDeg: Double? = null,
    val start: Pt? = null,
    val dest: Pt? = null,
    val destLabel: String = "",
    val sigmaM: Double = 0.0
) {
    val isEmpty: Boolean get() = out.isEmpty() && back.isEmpty() && route.isEmpty() && here == null && dest == null
}

/** The arithmetic behind the path drawing: how to fit a walk on the screen, round distances for the grid and the scale bar, and where the arrows go. Plain Kotlin, unit-tested. */
object MapMath {
    private val STEPS = doubleArrayOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0, 50000.0)

    /** how the world is shown: [scale] pixels for each metre, with the world point ([cx], [cy]) in the middle of the canvas */
    class View(val scale: Double, val cx: Double, val cy: Double)

    /** a view with every point inside the canvas (and at least [minSpanM] metres across, so a few steps do not fill the screen) */
    fun fit(points: List<Pt>, widthPx: Double, heightPx: Double, padPx: Double, minSpanM: Double = 40.0): View {
        if (points.isEmpty()) return View(1.0, 0.0, 0.0)
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (p in points) { minX = min(minX, p.x); maxX = max(maxX, p.x); minY = min(minY, p.y); maxY = max(maxY, p.y) }
        val spanX = max(maxX - minX, minSpanM)
        val spanY = max(maxY - minY, minSpanM)
        val sx = (widthPx - 2 * padPx) / spanX
        val sy = (heightPx - 2 * padPx) / spanY
        return View(max(min(sx, sy), 1e-6), (minX + maxX) / 2, (minY + maxY) / 2)
    }

    /** the smallest round distance in metres that is at least [minPx] long on the screen (the spacing of the grid lines) */
    fun gridStep(pxPerM: Double, minPx: Double): Double = STEPS.firstOrNull { it * pxPerM >= minPx } ?: STEPS.last()

    /** the longest round distance in metres that is at most [maxPx] long on the screen (the length of the scale bar) */
    fun barLength(pxPerM: Double, maxPx: Double): Double = STEPS.lastOrNull { it * pxPerM <= maxPx } ?: STEPS.first()

    fun label(metres: Double): String = if (metres >= 1000) "${trim(metres / 1000)} km" else "${metres.toInt()} m"

    private fun trim(v: Double) = if (v == v.toInt().toDouble()) v.toInt().toString() else "%.1f".format(java.util.Locale.US, v)

    /** a point on a line drawn in pixels, and the direction the line runs there (radians, screen coordinates: 0 is right, pi/2 is down) */
    class Mark(val x: Double, val y: Double, val angle: Double)

    /** marks along a polyline given in pixels, one every [spacingPx], the first at [firstAtPx] from its start: where the direction arrows go */
    fun arrowMarks(line: List<Pair<Double, Double>>, spacingPx: Double, firstAtPx: Double = spacingPx / 2): List<Mark> {
        val out = ArrayList<Mark>()
        if (line.size < 2 || spacingPx <= 0) return out
        var next = firstAtPx
        var walked = 0.0
        for (i in 0 until line.size - 1) {
            val (x0, y0) = line[i]; val (x1, y1) = line[i + 1]
            val len = hypot(x1 - x0, y1 - y0)
            if (len == 0.0) continue
            val ang = atan2(y1 - y0, x1 - x0)
            while (next < walked + len) {
                val t = (next - walked) / len
                out += Mark(x0 + t * (x1 - x0), y0 + t * (y1 - y0), ang)
                next += spacingPx
            }
            walked += len
        }
        return out
    }

    /** the points to fit on the screen: everything that is drawn */
    fun extent(t: Trail): List<Pt> = buildList {
        addAll(t.out); addAll(t.back); addAll(t.route)
        t.here?.let { add(it) }; t.dest?.let { add(it) }; t.start?.let { add(it) }
    }
}
