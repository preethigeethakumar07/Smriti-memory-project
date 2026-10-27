package com.smriti.app.ai

/**
 * A pointer from an answer to a moment: which session, where in it (offsets from the session start) and the label shown on the
 * tappable chip. Stored as text on the chat line so a tap can open that session at that moment.
 */
class MomentRef(val sessionId: Long, val startOffsetMs: Long, val endOffsetMs: Long, val label: String) {

    /** "00:03 - 00:07" */
    val range: String get() = SessionRules.clock(startOffsetMs) + " - " + SessionRules.clock(endOffsetMs)

    companion object {
        private const val SEP = "|"
        private const val FIELD = ";"

        fun encode(refs: List<MomentRef>): String? =
            if (refs.isEmpty()) null else refs.joinToString(SEP) { "${it.sessionId}$FIELD${it.startOffsetMs}$FIELD${it.endOffsetMs}$FIELD${it.label.replace(SEP, " ").replace(FIELD, " ")}" }

        fun decode(text: String?): List<MomentRef> =
            if (text.isNullOrBlank()) emptyList()
            else text.split(SEP).mapNotNull { part ->
                val p = part.split(FIELD, limit = 4)
                if (p.size < 4) return@mapNotNull null
                val id = p[0].toLongOrNull() ?: return@mapNotNull null
                val a = p[1].toLongOrNull() ?: return@mapNotNull null
                val b = p[2].toLongOrNull() ?: return@mapNotNull null
                MomentRef(id, a, b, p[3])
            }
    }
}
