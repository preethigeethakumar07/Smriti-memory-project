package com.smriti.app.move

import kotlin.math.abs

/**
 * One walking direction from three sources, each good at what the others are bad at:
 *  - the gyroscope (the "game" rotation vector): smooth and quick and not bothered by magnets, but it has no north and drifts slowly;
 *  - the compass: knows north, but steel, speakers and lifts bend it (indoors by 10 to 25 degrees);
 *  - the GPS course: the direction the phone really travels, the truth while walking in a straight line outdoors.
 *
 * The gyroscope carries the heading from moment to moment. A slow correction pulls the gyroscope's north towards the compass, and it is
 * held off while the compass looks disturbed (its field is too strong, too weak or has jumped, or it moves against the gyroscope).
 * The GPS course corrects separately: the difference between the walking direction and the phone's own direction is how the phone is
 * carried ("body"): nothing in the hand, about a quarter turn in a pocket. It is learned while walking outdoors and forgotten when the
 * phone is picked up (see [poseChanged]).
 *
 * heading = gyroscope heading + north + body. Plain Kotlin, unit-tested.
 */
class HeadingFilter {
    private var north = 0.0
    private var haveNorth = false
    private var northFromGps = false
    private var body = 0.0
    private var gpsFixes = 0
    private var lastNowMs = 0L
    private var lastGame: Double? = null
    private var disturbedUntilMs = Long.MIN_VALUE
    private var gpsAtMs = Long.MIN_VALUE / 2
    private var fieldBase: Double? = null

    private class Sample(val t: Long, val game: Double, val apparent: Double?)
    private val history = ArrayDeque<Sample>()

    /** the walking direction in compass degrees, or null before the first reading */
    var heading: Double? = null; private set

    /** how the phone is carried: walking direction minus the phone's own heading, learned from GPS */
    val bodyOffsetDeg: Double get() = body

    fun gpsLocked(nowMs: Long) = nowMs - gpsAtMs < GPS_TRUST_MS
    fun compassDisturbed(nowMs: Long) = nowMs < disturbedUntilMs

    fun reset() {
        north = 0.0; haveNorth = false; northFromGps = false; body = 0.0; gpsFixes = 0
        lastNowMs = 0L; lastGame = null; disturbedUntilMs = Long.MIN_VALUE; gpsAtMs = Long.MIN_VALUE / 2; fieldBase = null
        history.clear(); heading = null
    }

    /** the phone was picked up or put away: the way it is carried changed, so what was learned about it is forgotten */
    fun poseChanged() { body = 0.0; gpsFixes = 0 }

    /**
     * [game] is the heading from the gyroscope's rotation (north is arbitrary), [compass] the heading from the compass or null when it is
     * not trusted, [fieldUt] the strength of the magnetic field in microtesla or null.
     */
    fun update(nowMs: Long, game: Double, compass: Double?, fieldUt: Double? = null): Double? {
        val g = Geo.wrap360(game)
        val dt = if (lastNowMs == 0L) 0.0 else ((nowMs - lastNowMs) / 1000.0).coerceIn(0.0, 1.0)
        lastNowMs = nowMs
        lastGame = g

        // a magnetic disturbance shows as a field that is too strong or too weak, or far from what it has been
        if (fieldUt != null) {
            val base = fieldBase
            if (fieldUt < FIELD_MIN_UT || fieldUt > FIELD_MAX_UT || (base != null && abs(fieldUt - base) > FIELD_JUMP * base)) {
                disturbedUntilMs = maxOf(disturbedUntilMs, nowMs + HOLD_MS)
            }
            fieldBase = if (base == null) fieldUt else base + (fieldUt - base) * (dt / FIELD_TAU_S).coerceAtMost(1.0)
        }

        var apparent: Double? = null
        if (compass != null) {
            val c = Geo.wrap360(compass)
            apparent = Geo.diff(c, g)                         // where north is, as the gyroscope sees it: steady while the compass is healthy
            if (!haveNorth) {
                north = apparent; haveNorth = true
            } else if (northFromGps) {
                val total = north + body                       // north was a guess made before any compass: keep the heading, change how it is explained
                north = apparent; body = Geo.diff(total - north, 0.0); northFromGps = false
            } else {
                val past = history.firstOrNull { it.apparent != null && nowMs - it.t <= WINDOW_MS }
                if (past != null && abs(Geo.diff(apparent, past.apparent!!)) > DISTURBANCE_DEG) disturbedUntilMs = maxOf(disturbedUntilMs, nowMs + HOLD_MS)
                if (nowMs >= disturbedUntilMs) north = Geo.diff(north + Geo.diff(apparent, north) * (dt / TAU_S).coerceAtMost(0.5), 0.0)
            }
        }
        history.addLast(Sample(nowMs, g, apparent))
        while (history.size > 1 && nowMs - history.first().t > KEEP_MS) history.removeFirst()

        heading = if (haveNorth) Geo.wrap360(g + north + body) else null
        return heading
    }

    /** a GPS fix with a direction of travel; used only while walking fast enough, in a straight line, with a believable course */
    fun onGpsCourse(nowMs: Long, courseDeg: Double, speedMps: Double, courseAccDeg: Double?) {
        val g = lastGame ?: return
        if (speedMps < MIN_SPEED_MPS) return
        if (courseAccDeg != null && courseAccDeg > MAX_COURSE_ACC_DEG) return
        if (turnedRecently(nowMs, g)) return                  // in a turn the course lags behind the phone
        if (!haveNorth) { north = 0.0; haveNorth = true; northFromGps = true }
        val err = Geo.diff(Geo.wrap360(courseDeg), Geo.wrap360(g + north + body))
        val gain = if (gpsFixes < FAST_FIXES) GAIN_FAST else GAIN_SLOW
        body = Geo.diff(body + err * gain, 0.0)
        gpsFixes++
        gpsAtMs = nowMs
        heading = Geo.wrap360(g + north + body)
    }

    private fun turnedRecently(nowMs: Long, g: Double): Boolean {
        val past = history.firstOrNull { nowMs - it.t <= TURN_WINDOW_MS } ?: return false
        return abs(Geo.diff(g, past.game)) > TURN_MAX_DEG
    }

    companion object {
        const val WINDOW_MS = 3_000L
        const val KEEP_MS = 3_200L
        const val HOLD_MS = 5_000L
        const val DISTURBANCE_DEG = 8.0
        const val TAU_S = 20.0
        const val FIELD_MIN_UT = 20.0
        const val FIELD_MAX_UT = 70.0
        const val FIELD_JUMP = 0.25
        const val FIELD_TAU_S = 30.0
        const val MIN_SPEED_MPS = 0.9
        const val MAX_COURSE_ACC_DEG = 25.0
        const val TURN_WINDOW_MS = 2_500L
        const val TURN_MAX_DEG = 25.0
        const val GPS_TRUST_MS = 120_000L
        const val FAST_FIXES = 5
        const val GAIN_FAST = 0.4
        const val GAIN_SLOW = 0.15
    }
}

/**
 * What the walk guide says, in English or Hindi (other languages get English, spoken with an English voice). Short on purpose:
 * a person who is confused needs one instruction at a time. Plain Kotlin, unit-tested.
 */
object MovePhrases {
    fun isHindi(language: String) = language.startsWith("hi")

    /** the language tag the text is written in (so the right voice reads it) */
    fun tagFor(language: String) = if (isHindi(language)) "hi-IN" else "en-IN"

    fun text(a: Announcement, language: String): String {
        if (!isHindi(language) || a.say == Say.NONE) return a.phrase
        return when (a.say) {
            Say.NONE -> a.phrase
            Say.STRAIGHT -> "सीधे चलिए"
            Say.SLIGHT_LEFT -> "थोड़ा बाएं मुड़िए"
            Say.SLIGHT_RIGHT -> "थोड़ा दाएं मुड़िए"
            Say.LEFT -> "बाएं मुड़िए"
            Say.RIGHT -> "दाएं मुड़िए"
            Say.U_TURN -> "पीछे मुड़िए"
            Say.ARRIVED -> "आप पहुंच गए हैं"
            Say.OFF_LEFT -> "आप रास्ते से हट गए हैं। वापस आने के लिए बाएं मुड़िए"
            Say.OFF_RIGHT -> "आप रास्ते से हट गए हैं। वापस आने के लिए दाएं मुड़िए"
            Say.OFF_STRAIGHT -> "आप रास्ते से हट गए हैं। वापस आने के लिए सीधे चलिए"
            Say.HOLD_PHONE -> "फ़ोन को हाथ में सीधा पकड़िए"
            Say.KEEP_GOING -> "चलते रहिए"
            Say.IN_LEFT -> "${a.arg} मीटर बाद बाएं मुड़िए"
            Say.IN_RIGHT -> "${a.arg} मीटर बाद दाएं मुड़िए"
            Say.NOW_LEFT -> "अब बाएं मुड़िए"
            Say.NOW_RIGHT -> "अब दाएं मुड़िए"
            Say.TO_GO -> "लगभग ${a.arg} मीटर बाकी हैं"
        }
    }

    /** "About 200 metres to go" */
    fun toGo(metres: Int, language: String): Announcement {
        val txt = if (metres >= 1000) "About ${metres / 1000} kilometre${if (metres >= 2000) "s" else ""} to go" else "About $metres metres to go"
        return Announcement(txt, HapticId.TICK, Say.TO_GO, metres)
    }

    fun holdPhone(): Announcement = Announcement("Hold the phone flat in your hand", HapticId.WARN, Say.HOLD_PHONE)
}
