package com.smriti.app.move

import kotlin.math.abs
import kotlin.math.hypot

enum class CueType { STRAIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, LEFT, RIGHT, U_TURN, ARRIVED, OFF_ROUTE, NO_HEADING }

/**
 * What the user should do now. [errDeg] is how far they must turn (positive right, negative left); [remainingM] the way left along the
 * path; [crossTrackM] how far they are from the path; [nextTurnM] / [nextTurnDir] the next corner ahead, if any, with its vertex number
 * in [nextTurnId] so it is announced once.
 */
data class Cue(
    val type: CueType,
    val errDeg: Double,
    val remainingM: Double,
    val crossTrackM: Double,
    val nextTurnM: Double? = null,
    val nextTurnDir: CueType? = null,
    val nextTurnId: Int = -1
)

/**
 * Turn-by-turn guidance along a path of ground points (a route walked before, or a straight line to a place). Each update takes the
 * user's position and walking direction and says go straight, bear / turn left or right, turn around, you are off the path, or you
 * have arrived. The direction to turn is towards a point a few metres ahead on the path, so the user is led along it and not
 * just towards its end; the choice of cue has hysteresis, so it does not flicker at a boundary. Plain Kotlin, unit-tested.
 */
class Guidance(
    private val path: List<Pt>,
    private val arriveRadiusM: Double = 6.0,
    private val offRouteM: Double = 20.0,
    private val lookAheadM: Double = 8.0
) {
    private val cum = DoubleArray(path.size)
    private var seg = 0
    private var last = CueType.STRAIGHT
    private var arrived = false

    init {
        require(path.size >= 2) { "a path needs at least two points" }
        for (i in 1 until path.size) cum[i] = cum[i - 1] + Geo.distance(path[i - 1], path[i])
    }

    val totalM: Double get() = cum.last()

    fun update(x: Double, y: Double, headingDeg: Double): Cue {
        val here = Pt(x, y)
        val end = path.last()
        if (arrived || Geo.distance(here, end) <= arriveRadiusM) {
            arrived = true
            return Cue(CueType.ARRIVED, 0.0, 0.0, 0.0)
        }

        // the nearest point on the path, looking a few segments ahead of where the user already is
        var bestI = seg; var bestT = 0.0; var bestD = Double.MAX_VALUE
        val lastSeg = path.size - 2
        for (i in seg..minOf(seg + SEARCH_AHEAD, lastSeg)) {
            val (t, d) = project(here, path[i], path[i + 1])
            if (d <= bestD) { bestD = d; bestI = i; bestT = t }
        }
        if (bestD > offRouteM * 1.5) {
            // far from where we thought: look at the whole path
            for (i in 0..lastSeg) {
                val (t, d) = project(here, path[i], path[i + 1])
                if (d < bestD) { bestD = d; bestI = i; bestT = t }
            }
        }
        seg = maxOf(seg, bestI).let { if (bestD > offRouteM * 1.5) bestI else it }
        val s0 = cum[bestI] + bestT * (cum[bestI + 1] - cum[bestI])
        val remaining = (cum.last() - s0).coerceAtLeast(0.0)

        val nearest = pointAt(s0)
        if (bestD > offRouteM) {
            val back = Geo.diff(Geo.bearing(here, nearest), headingDeg)
            return Cue(CueType.OFF_ROUTE, back, remaining, bestD).also { last = CueType.OFF_ROUTE }
        }

        val look = pointAt((s0 + lookAheadM).coerceAtMost(cum.last()))
        val toLook = if (Geo.distance(here, look) < 1.0) Geo.bearing(path[bestI], path[bestI + 1]) else Geo.bearing(here, look)
        val err = Geo.diff(toLook, headingDeg)
        val type = classify(err)
        last = type

        val turn = nextTurn(bestI, s0)
        return Cue(type, err, remaining, bestD, turn?.first, turn?.second, turn?.third ?: -1)
    }

    private fun classify(err: Double): CueType = TurnClass.classify(err, last)

    /** the next corner (a change of direction of more than 35 degrees) ahead: distance, side, vertex number */
    private fun nextTurn(fromSeg: Int, s0: Double): Triple<Double, CueType, Int>? {
        for (j in fromSeg + 1 until path.size - 1) {
            val before = Geo.bearing(path[j - 1], path[j]); val after = Geo.bearing(path[j], path[j + 1])
            val d = Geo.diff(after, before)
            if (abs(d) > TURN_MIN_DEG) {
                val dist = cum[j] - s0
                if (dist > 0) return Triple(dist, if (d < 0) CueType.LEFT else CueType.RIGHT, j)
            }
        }
        return null
    }

    /** the point [s] metres along the path */
    private fun pointAt(s: Double): Pt {
        if (s <= 0.0) return path.first()
        if (s >= cum.last()) return path.last()
        var i = 0
        while (i < path.size - 2 && cum[i + 1] < s) i++
        val len = cum[i + 1] - cum[i]
        val t = if (len <= 0.0) 0.0 else (s - cum[i]) / len
        return Pt(path[i].x + t * (path[i + 1].x - path[i].x), path[i].y + t * (path[i + 1].y - path[i].y))
    }

    /** the fraction t (0..1) along a-b of the point nearest [p], and how far [p] is from it */
    private fun project(p: Pt, a: Pt, b: Pt): Pair<Double, Double> {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return t to hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    private companion object {
        const val SEARCH_AHEAD = 8
        const val TURN_MIN_DEG = 35.0
    }
}

/** How far to turn becomes a cue, with a little hysteresis so the cue does not flicker at the edge of a band. */
object TurnClass {
    fun classify(err: Double, last: CueType): CueType {
        val a = abs(err)
        val left = err < 0
        // staying in the current band is easier than entering it
        val straightIn = if (last == CueType.STRAIGHT) 25.0 else 15.0
        val slightIn = if (last == CueType.SLIGHT_LEFT || last == CueType.SLIGHT_RIGHT) 60.0 else 50.0
        val turnIn = if (last == CueType.LEFT || last == CueType.RIGHT) 150.0 else 135.0
        return when {
            a <= straightIn -> CueType.STRAIGHT
            a <= slightIn -> if (left) CueType.SLIGHT_LEFT else CueType.SLIGHT_RIGHT
            a <= turnIn -> if (left) CueType.LEFT else CueType.RIGHT
            else -> CueType.U_TURN
        }
    }
}

/**
 * Straight to a point, with no path to follow (a saved place seen from where the user stands, "as the crow flies"): turn towards it
 * from wherever the user is now, so walking round a building does not count as being off the path. Plain Kotlin, unit-tested.
 */
class StraightGuide(private val dest: Pt, private val arriveRadiusM: Double = 8.0) {
    private var last = CueType.STRAIGHT
    private var arrived = false

    fun update(x: Double, y: Double, headingDeg: Double): Cue {
        val here = Pt(x, y)
        val d = Geo.distance(here, dest)
        if (arrived || d <= arriveRadiusM) { arrived = true; return Cue(CueType.ARRIVED, 0.0, 0.0, 0.0) }
        val err = Geo.diff(Geo.bearing(here, dest), headingDeg)
        val type = TurnClass.classify(err, last)
        last = type
        return Cue(type, err, d, 0.0)
    }
}

/** "About 200 metres to go": said once each time the way left drops below 1000, 500, 300, 200, 100, 50 and 20 metres. */
class DistanceCalls(private val marks: List<Int> = listOf(1000, 500, 300, 200, 100, 50, 20)) {
    private var lowest = Int.MAX_VALUE

    /** the mark just crossed, or null */
    fun next(remainingM: Double): Int? {
        // marks the user starts inside of are not announced
        if (lowest == Int.MAX_VALUE) { lowest = marks.firstOrNull { remainingM >= it }?.let { it + 1 } ?: 0; return null }
        val crossed = marks.filter { it < lowest && remainingM <= it }.maxOrNull() ?: return null
        lowest = crossed
        return crossed
    }

    fun reset() { lowest = Int.MAX_VALUE }
}

/** Records the way someone walks as a few points, so the same way can be walked back. */
class RouteBuilder(private val minGapM: Double = 3.0) {
    private val pts = ArrayList<Pt>()

    val size get() = pts.size

    fun add(p: Pt) {
        val last = pts.lastOrNull()
        if (last == null || Geo.distance(last, p) >= minGapM) pts += p
    }

    fun points(): List<Pt> = pts.toList()

    /** the route with unneeded points removed (a point is kept only if the route bends more than [toleranceM] there) */
    fun simplified(toleranceM: Double = 1.5): List<Pt> = simplify(pts, toleranceM)

    companion object {
        fun simplify(points: List<Pt>, tolerance: Double): List<Pt> {
            if (points.size < 3) return points
            var maxD = 0.0; var idx = 0
            val a = points.first(); val b = points.last()
            for (i in 1 until points.size - 1) {
                val d = distanceToLine(points[i], a, b)
                if (d > maxD) { maxD = d; idx = i }
            }
            if (maxD <= tolerance) return listOf(a, b)
            val left = simplify(points.subList(0, idx + 1), tolerance)
            val right = simplify(points.subList(idx, points.size), tolerance)
            return left.dropLast(1) + right
        }

        private fun distanceToLine(p: Pt, a: Pt, b: Pt): Double {
            val dx = b.x - a.x; val dy = b.y - a.y
            val len = hypot(dx, dy)
            if (len == 0.0) return Geo.distance(p, a)
            return abs(dy * p.x - dx * p.y + b.x * a.y - b.y * a.x) / len
        }

        fun length(points: List<Pt>): Double = points.zipWithNext { a, b -> Geo.distance(a, b) }.sum()
    }
}

enum class HapticId { TICK, LEFT, RIGHT, UTURN, ARRIVE, WARN }

/** what an announcement means, so it can be said in the user's language (see [MovePhrases]) */
enum class Say {
    NONE, STRAIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, LEFT, RIGHT, U_TURN, ARRIVED, OFF_LEFT, OFF_RIGHT, OFF_STRAIGHT,
    HOLD_PHONE, KEEP_GOING, IN_LEFT, IN_RIGHT, NOW_LEFT, NOW_RIGHT, TO_GO
}

/** a short phrase to say (in English), what it means ([say], with [arg] as the number of metres where it has one) and the buzz that goes with it */
class Announcement(val phrase: String, val haptic: HapticId, val say: Say = Say.NONE, val arg: Int = 0)

/**
 * Decides when a cue is worth announcing: when it changes (but not more often than every [minGapMs]), again every [repeatMs] while the
 * user still has to turn, once for a corner coming up (at 20 m and at 5 m), and once on arrival. "Keep going" is said rarely, so the person
 * is not nagged. Plain Kotlin, unit-tested.
 */
class CuePolicy(private val minGapMs: Long = 3_000, private val repeatMs: Long = 14_000, private val keepGoingMs: Long = 45_000) {
    private var lastType: CueType? = null
    private var lastAt = Long.MIN_VALUE / 2
    private var lastStraightSpoken = Long.MIN_VALUE / 2
    private val announcedFar = HashSet<Int>()
    private val announcedNear = HashSet<Int>()
    private var arrivedSaid = false

    fun next(cue: Cue, nowMs: Long): Announcement? {
        if (cue.type == CueType.ARRIVED) {
            if (arrivedSaid) return null
            arrivedSaid = true
            return Announcement("You have arrived", HapticId.ARRIVE, Say.ARRIVED)
        }
        val changed = cue.type != lastType
        val dueRepeat = cue.type != CueType.STRAIGHT && nowMs - lastAt >= repeatMs
        val enoughGap = nowMs - lastAt >= minGapMs

        // a corner coming up
        val id = cue.nextTurnId
        val dist = cue.nextTurnM
        if (id >= 0 && dist != null && cue.type == CueType.STRAIGHT) {
            val left = cue.nextTurnDir == CueType.LEFT
            val side = if (left) "left" else "right"
            if (dist <= 5.0 && announcedNear.add(id)) { lastAt = nowMs; return Announcement("Turn $side now", haptic(cue.nextTurnDir!!), if (left) Say.NOW_LEFT else Say.NOW_RIGHT) }
            if (dist <= 20.0 && dist > 5.0 && announcedFar.add(id) && enoughGap) {
                lastAt = nowMs
                val m = (dist / 5).toInt() * 5
                return Announcement("In $m metres turn $side", HapticId.TICK, if (left) Say.IN_LEFT else Say.IN_RIGHT, m)
            }
        }

        if (!(changed && enoughGap) && !(dueRepeat && enoughGap)) {
            if (cue.type == CueType.STRAIGHT && nowMs - lastStraightSpoken >= keepGoingMs && nowMs - lastAt >= minGapMs) {
                lastStraightSpoken = nowMs; lastAt = nowMs; lastType = cue.type
                return Announcement("Keep going", HapticId.TICK, Say.KEEP_GOING)
            }
            return null
        }
        lastType = cue.type; lastAt = nowMs
        if (cue.type == CueType.STRAIGHT) lastStraightSpoken = nowMs
        return Announcement(phrase(cue), haptic(cue.type), say(cue))
    }

    fun reset() { lastType = null; lastAt = Long.MIN_VALUE / 2; lastStraightSpoken = Long.MIN_VALUE / 2; announcedFar.clear(); announcedNear.clear(); arrivedSaid = false }

    companion object {
        fun phrase(cue: Cue): String = when (cue.type) {
            CueType.STRAIGHT -> "Go straight"
            CueType.SLIGHT_LEFT -> "Bear left"
            CueType.SLIGHT_RIGHT -> "Bear right"
            CueType.LEFT -> "Turn left"
            CueType.RIGHT -> "Turn right"
            CueType.U_TURN -> "Turn around"
            CueType.ARRIVED -> "You have arrived"
            CueType.OFF_ROUTE -> "You are off the path. ${if (cue.errDeg < -20) "Turn left" else if (cue.errDeg > 20) "Turn right" else "Go straight"} to get back"
            CueType.NO_HEADING -> "Hold the phone flat in your hand"
        }

        fun say(cue: Cue): Say = when (cue.type) {
            CueType.STRAIGHT -> Say.STRAIGHT
            CueType.SLIGHT_LEFT -> Say.SLIGHT_LEFT
            CueType.SLIGHT_RIGHT -> Say.SLIGHT_RIGHT
            CueType.LEFT -> Say.LEFT
            CueType.RIGHT -> Say.RIGHT
            CueType.U_TURN -> Say.U_TURN
            CueType.ARRIVED -> Say.ARRIVED
            CueType.OFF_ROUTE -> if (cue.errDeg < -20) Say.OFF_LEFT else if (cue.errDeg > 20) Say.OFF_RIGHT else Say.OFF_STRAIGHT
            CueType.NO_HEADING -> Say.HOLD_PHONE
        }

        fun haptic(t: CueType): HapticId = when (t) {
            CueType.STRAIGHT -> HapticId.TICK
            CueType.SLIGHT_LEFT, CueType.LEFT -> HapticId.LEFT
            CueType.SLIGHT_RIGHT, CueType.RIGHT -> HapticId.RIGHT
            CueType.U_TURN -> HapticId.UTURN
            CueType.ARRIVED -> HapticId.ARRIVE
            CueType.OFF_ROUTE, CueType.NO_HEADING -> HapticId.WARN
        }
    }
}

/**
 * A buzz that guides without looking or listening: the closer the phone points to the right way, the more steady the buzz. Facing the
 * right way is a firm continuous buzz; a little off, quick short pulses; far off, slow light ticks; facing the opposite way, nothing.
 */
object CompassBuzz {
    class Pulse(val onMs: Int, val amplitude: Int, val offMs: Int)

    fun pulse(errDeg: Double): Pulse? {
        val a = abs(errDeg)
        return when {
            a <= 10.0 -> Pulse(onMs = 380, amplitude = 220, offMs = 90)
            a <= 35.0 -> Pulse(onMs = 70, amplitude = 170, offMs = 260)
            a <= 90.0 -> Pulse(onMs = 45, amplitude = 120, offMs = 650)
            else -> null
        }
    }
}
