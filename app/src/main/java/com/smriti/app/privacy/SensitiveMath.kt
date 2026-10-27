package com.smriti.app.privacy

/**
 * The plain rules of the sensitive-scene filter: how a picture's closeness to descriptions of undressed scenes becomes a verdict.
 * No Android here, so it is unit-tested. The numbers were measured on ordinary pictures from the phone itself (see PrivacyGuard).
 */
object SensitiveMath {
    /** the descriptions an undressed or bathing scene is compared with */
    val RISKY = listOf(
        "a photo of a naked person",
        "a photo of a person without clothes",
        "a photo of a topless person",
        "a photo of a person in underwear",
        "a photo of a person undressing",
        "an explicit adult photo",
        "a photo of a person bathing in a bathtub or shower"
    )

    /** ordinary scenes, so "looks like a person" or "looks like skin" alone does not count */
    val ORDINARY = listOf(
        "a photo of a person wearing clothes",
        "a photo of a room",
        "a photo of a desk with a laptop",
        "a photo of a kitchen",
        "a photo of a street",
        "a photo of food",
        "a photo of a hand holding a phone",
        "a photo of a face",
        "a photo of a wall",
        "a dark photo"
    )

    /** how much more a picture looks like the risky descriptions than like the ordinary ones (cosine similarities) */
    fun margin(riskyBest: Float, ordinaryBest: Float): Float = riskyBest - ordinaryBest

    enum class Verdict { CLEAR, CHECK, BLOCK }

    /**
     * [margin] below [check] is clear. Between [check] and [block] Gemma-4 looks at the picture and says yes or no. At [block] or above
     * the picture is dropped without asking. When Gemma-4 is not available, a picture in the middle band is dropped as well (when in doubt, drop).
     */
    fun verdict(margin: Float, check: Float, block: Float): Verdict = when {
        margin >= block -> Verdict.BLOCK
        margin >= check -> Verdict.CHECK
        else -> Verdict.CLEAR
    }

    /** Gemma-4's reply to "is this picture nude or undressed?": yes means sensitive; no means fine; anything else (a refusal, a lecture) is treated as sensitive */
    fun answerIsSensitive(reply: String?): Boolean {
        val r = reply?.trim()?.lowercase() ?: return true
        if (r.startsWith("no")) return false
        return true
    }

    /** p-th percentile (0..100) of [values] by the nearest rank */
    fun percentile(values: List<Float>, p: Double): Float {
        if (values.isEmpty()) return 0f
        val s = values.sorted()
        val rank = Math.ceil(p / 100.0 * s.size).toInt().coerceIn(1, s.size)
        return s[rank - 1]
    }
}
