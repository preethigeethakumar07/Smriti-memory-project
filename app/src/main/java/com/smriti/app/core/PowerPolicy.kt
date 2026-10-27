package com.smriti.app.core

/**
 * How hard the camera and the memory work, decided from where the user is in the app and how warm the phone is. Plain Kotlin,
 * so the rules are unit-tested; the app applies the result (camera frame rate, how often a frame is taken, how often a moment
 * is remembered).
 *
 * The camera service itself was measured at 90 to 130 % of a CPU core while streaming at full rate, even on the Home screen,
 * so the frame rate is the biggest lever: Home needs only a few frames a second, Live needs a smooth picture.
 */
object PowerPolicy {

    enum class Where { HOME, LIVE_VOICE, LIVE_CAMERA }

    /** [cameraFps] camera frame rate; [tickMs] least time between two frames taken for analysis; [ambientGapScale] multiplies the gap
     *  between remembered moments outside a recording; [allowInventory] whether the object list may be written; [label] what to tell the user. */
    class Plan(val cameraFps: Int, val tickMs: Long, val ambientGapScale: Int, val allowInventory: Boolean, val label: String?)

    /**
     * Thermal level 0 (none) to 6 (shutdown), as Android numbers it: 1 light, 2 moderate, 3 severe, 4 critical.
     * [headroom] is the OS forecast for the next seconds (1.0 means severe throttling is reached): acting on it slows the app down
     * before the phone is hot instead of after.
     */
    fun level(status: Int, headroom: Float?): Int {
        val h = headroom?.takeUnless { it.isNaN() } ?: return status
        val forecast = when {
            h >= 0.95f -> 3
            h >= 0.85f -> 2
            h >= 0.70f -> 1
            else -> 0
        }
        return maxOf(status, forecast)
    }

    fun decide(where: Where, level: Int, blankStreak: Int = 0): Plan {
        val cool = when { level >= 3 -> 3; level >= 2 -> 2; level >= 1 -> 1; else -> 0 }
        var plan = when (where) {
            Where.LIVE_CAMERA -> Plan(
                cameraFps = intArrayOf(24, 24, 15, 10)[cool], tickMs = longArrayOf(500, 500, 1000, 2000)[cool],
                ambientGapScale = 1, allowInventory = false, label = null
            )
            Where.LIVE_VOICE -> Plan(
                cameraFps = 10, tickMs = longArrayOf(1000, 1000, 2000, 4000)[cool],
                ambientGapScale = 1, allowInventory = false, label = null
            )
            Where.HOME -> Plan(
                cameraFps = 10, tickMs = longArrayOf(1000, 1000, 2000, 4000)[cool],
                ambientGapScale = intArrayOf(2, 2, 3, 4)[cool], allowInventory = cool < 2, label = null
            )
        }
        // a covered lens (pocket, desk) shows nothing worth looking at: look only now and then
        if (blankStreak >= BLANK_BACKOFF) plan = Plan(plan.cameraFps, maxOf(plan.tickMs, 4000), maxOf(plan.ambientGapScale, 4), plan.allowInventory, plan.label)
        val label = when (cool) {
            2 -> "Cooling down: the camera is slowed"
            3 -> "Phone is hot: the camera runs at minimum"
            else -> null
        }
        return Plan(plan.cameraFps, plan.tickMs, plan.ambientGapScale, plan.allowInventory, label)
    }

    /** consecutive blank frames after which the camera is looked at rarely */
    const val BLANK_BACKOFF = 8
}

/** Picks the camera frame-rate range the device offers that is closest to what is wanted (fixed ranges first). */
object FrameRates {
    class Range(val lower: Int, val upper: Int)

    fun pick(offered: List<Range>, wantFps: Int): Range? {
        if (offered.isEmpty()) return null
        return offered.sortedWith(
            compareBy<Range> { kotlin.math.abs(it.upper - wantFps) }
                .thenBy { if (it.lower == it.upper) 0 else 1 }
                .thenBy { it.upper }
        ).first()
    }
}
