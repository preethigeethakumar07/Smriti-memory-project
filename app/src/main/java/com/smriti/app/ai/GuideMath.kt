package com.smriti.app.ai

/**
 * The plain rules behind "show me that object" in the live camera view: how the frame is cut into tiles, which tile (or blend of
 * tiles) holds the object, what to tell the user, and how a spoken request is turned into an object name. No Android here, so
 * it is unit-tested.
 */
object GuideMath {
    /** a region of the picture in fractions of its width and height, each 0..1 */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val cx get() = (left + right) / 2
        val cy get() = (top + bottom) / 2
        val area get() = (right - left) * (bottom - top)
    }

    /** 3 x 3 tiles, each half the picture wide and tall, so neighbours overlap and an object cut by one edge sits whole in the next */
    const val GRID = 3
    private const val TILE = 0.5f

    fun tiles(): List<Box> = buildList {
        for (r in 0 until GRID) for (c in 0 until GRID) {
            val l = (1f - TILE) * c / (GRID - 1); val t = (1f - TILE) * r / (GRID - 1)
            add(Box(l, t, l + TILE, t + TILE))
        }
    }

    /** what the tile scores say: whether the object is in the picture, and about where */
    class Decision(val found: Boolean, val box: Box?, val best: Float, val bestTile: Int)

    /**
     * [scores] are the text-to-picture similarities of the tiles, in the order of [tiles]. The scale of these numbers changes with
     * the wording, so a tile must be clearly above the others as well as above a floor. The place is a blend of the tiles close to
     * the best one, which is finer than the tile grid, and is drawn as a ring of about a third of the picture.
     */
    fun decide(scores: List<Float>, minScore: Float = 0.30f, minMargin: Float = 0.05f): Decision {
        if (scores.size != GRID * GRID) return Decision(false, null, 0f, -1)
        val best = scores.max(); val bi = scores.indexOf(best)
        val median = scores.sorted()[scores.size / 2]
        if (best < minScore || best - median < minMargin) return Decision(false, null, best, bi)
        val ts = tiles()
        var wx = 0f; var wy = 0f; var ws = 0f
        for (i in scores.indices) {
            val w = (scores[i] - (best - 0.08f)).coerceAtLeast(0f)
            wx += w * ts[i].cx; wy += w * ts[i].cy; ws += w
        }
        val cx = if (ws > 0f) wx / ws else ts[bi].cx
        val cy = if (ws > 0f) wy / ws else ts[bi].cy
        val half = 0.17f
        return Decision(true, Box((cx - half).coerceAtLeast(0f), (cy - half).coerceAtLeast(0f), (cx + half).coerceAtMost(1f), (cy + half).coerceAtMost(1f)), best, bi)
    }

    /** where the box is, as the user sees the picture */
    fun describe(b: Box): String {
        val h = when { b.cx < 0.36f -> "on the left"; b.cx > 0.64f -> "on the right"; else -> "in the middle" }
        val v = when { b.cy < 0.36f -> "at the top"; b.cy > 0.64f -> "at the bottom"; else -> "" }
        return listOf(v, h).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "in the middle" }
    }

    /** which way to turn the phone to bring the box to the middle, or null when it is already there */
    fun turn(b: Box): String? {
        val dx = b.cx - 0.5f; val dy = b.cy - 0.5f
        return when {
            kotlin.math.abs(dx) < 0.12f && kotlin.math.abs(dy) < 0.12f -> null
            kotlin.math.abs(dx) >= kotlin.math.abs(dy) -> if (dx < 0) "Turn a little left" else "Turn a little right"
            else -> if (dy < 0) "Tilt up a little" else "Tilt down a little"
        }
    }

    /** the line shown under the strip while an object is in view */
    fun hint(target: String, b: Box): String = turn(b)?.let { "$it  ($target is ${describe(b)})" } ?: "Hold still: $target is right here"
}

/** Turns "where is my steel bottle" or "help me find the keys" into the object name, or null when it is some other question. */
object GuideIntent {
    private val ASK = Regex(
        """^\s*(?:(?:ok(?:ay)?|hey|hi|please|smriti)[,.]?\s+)*(?:(?:can|could|would|will) you\s+)?(?:please\s+)?(?:help me\s+)?(?:to\s+)?""" +
            """(?:find|locate|look for|look out for|search for|spot|show me|point (?:to|at|out)|guide me to|take me to|where(?:'s| is| are| were))\s+(.+?)\s*[?.!]*\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val PAST = Regex("""\b(did|was|were|last|yesterday|earlier|before|ago|remember|used to|this morning|last night)\b""", RegexOption.IGNORE_CASE)
    private val LEAD = Regex("""^(?:my|the|a|an|your|our|this|that|these|those|some|any)\s+""", RegexOption.IGNORE_CASE)
    private val TAIL = Regex("""\s+(?:right now|now|here|around here|in here|in this room|in the room|nearby|near me|for me|please|in the camera|in view)$""", RegexOption.IGNORE_CASE)
    private val NOT_AN_OBJECT = Regex("""\b(time|date|weather|temperature|joke|story|song|way|answer|meaning|you|i|me|it|everything|anything|something|what|how|why|who)\b""", RegexOption.IGNORE_CASE)
    private val STOP = Regex("""^\W*(stop (looking|searching|guiding)|never ?mind|cancel( that)?|i (found|see) it|found it|that's it|thats it)\W*$""", RegexOption.IGNORE_CASE)

    fun parse(said: String): String? {
        if (PAST.containsMatchIn(said)) return null
        val m = ASK.find(said.trim()) ?: return null
        var t = m.groupValues[1].trim()
        repeat(3) { t = t.replace(TAIL, "").replace(LEAD, "").trim() }
        t = t.trim('"', '\'', ' ', ',', '.')
        if (t.isBlank() || t.split(Regex("\\s+")).size > 5 || NOT_AN_OBJECT.containsMatchIn(t)) return null
        return t.lowercase()
    }

    /**
     * A question about where something WAS ("where did I see my bottle", "where did I leave my keys", "where was my bag last"):
     * answered from memory as before, and when the camera is on the live search is also started for the object, so the person can be
     * shown where it is now. Returns the object's name, or null for anything else.
     */
    private val WHERE_SEEN = Regex(
        """^\s*(?:(?:ok(?:ay)?|hey|hi|please|smriti)[,.]?\s+)*(?:(?:can|could|would) you\s+(?:please\s+)?(?:tell me\s+)?)?(?:tell me\s+)?""" +
            """(?:where|when)\s+(?:(?:did|do|have|had)\s+(?:i|we)\s+(?:last\s+)?(?:see|seen|saw|leave|left|put|keep|kept|place|placed|park|parked|drop|dropped|find|found|lose|lost|take|took)|(?:was|were)(?:\s+last)?)\s+(.+?)\s*[?.!]*\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val WHEN_TAIL = Regex("""\s+(?:last time|last|yesterday|earlier|before|today|this morning|last night|recently|the last time)$""", RegexOption.IGNORE_CASE)

    fun parseSeen(said: String): String? {
        val m = WHERE_SEEN.find(said.trim()) ?: return null
        var t = m.groupValues[1].trim()
        repeat(3) { t = t.replace(WHEN_TAIL, "").replace(TAIL, "").replace(LEAD, "").trim() }
        t = t.trim('"', '\'', ' ', ',', '.')
        if (t.isBlank() || t.split(Regex("\\s+")).size > 5 || NOT_AN_OBJECT.containsMatchIn(t)) return null
        return t.lowercase()
    }

    fun isStop(said: String) = STOP.containsMatchIn(said)
}
