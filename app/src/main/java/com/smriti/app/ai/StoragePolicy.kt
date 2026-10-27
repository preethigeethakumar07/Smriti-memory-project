package com.smriti.app.ai

/**
 * What to do when Smriti's memory gets big. Video is never stored (only a small blurred picture and a vector per moment, about
 * 19 KB), so the memory grows with time spent recording. This keeps it inside a byte budget and a row cap while keeping as much
 * long-term coverage as possible: old pictures get smaller first, then old moments are thinned (every second one in a recording),
 * and only then are the oldest removed. Plain Kotlin, unit-tested; the app applies the plan.
 */
object StoragePolicy {
    /** a recompressed picture is expected to weigh about this much */
    const val SMALL_BYTES = 5_000L
    const val RECOMPRESS_AFTER_MS = 24 * 3_600_000L
    /** moments newer than this are never touched */
    const val KEEP_NEW_MS = 2 * 3_600_000L
    /** thinning starts with moments older than the first tier and works towards newer ones */
    private val THIN_TIERS = longArrayOf(3 * 86_400_000L, 86_400_000L, KEEP_NEW_MS)

    class Item(
        val id: Long,
        val timestamp: Long,
        /** the recording this moment belongs to; 0 for moments taken outside a recording */
        val sessionId: Long,
        val thumbBytes: Long,
        /** vector, caption and row overhead */
        val otherBytes: Long,
        /** never deleted (a recording's cover picture, the recording in progress) */
        val protected: Boolean = false,
        /** already recompressed */
        val small: Boolean = false
    )

    class Plan(val recompress: List<Long>, val delete: List<Long>, val bytesBefore: Long, val bytesAfter: Long) {
        val isEmpty get() = recompress.isEmpty() && delete.isEmpty()
    }

    /**
     * [startAt] and [target] are fractions of [budgetBytes]: the plan only acts above [startAt] and then frees enough to reach
     * [target]. The row cap works the same way (acts above 90 % of [maxRows], aims for 75 %).
     */
    fun plan(items: List<Item>, budgetBytes: Long, maxRows: Int, now: Long, startAt: Double = 0.85, target: Double = 0.70): Plan {
        val before = items.sumOf { it.thumbBytes + it.otherBytes }
        if (before <= budgetBytes * startAt && items.size <= maxRows * 0.9) return Plan(emptyList(), emptyList(), before, before)

        val targetBytes = (budgetBytes * target).toLong()
        val targetRows = (maxRows * 0.75).toInt()
        var bytes = before
        var rows = items.size
        fun done() = bytes <= targetBytes && rows <= targetRows

        val deleted = LinkedHashSet<Long>()
        val recompressed = LinkedHashSet<Long>()
        val oldestFirst = items.sortedBy { it.timestamp }

        // 1. smaller pictures for old moments (only helps with bytes)
        for (it in oldestFirst) {
            if (bytes <= targetBytes) break
            if (now - it.timestamp < RECOMPRESS_AFTER_MS || it.small || it.thumbBytes <= SMALL_BYTES * 1.3) continue
            bytes -= it.thumbBytes - SMALL_BYTES
            recompressed += it.id
        }

        // 2. thin old recordings: every second moment, never the first or last of a recording
        for (tier in THIN_TIERS) {
            if (done()) break
            val groups = items.filter { !it.protected && it.id !in deleted }.groupBy { it.sessionId }.values
            val candidates = ArrayList<Item>()
            for (g in groups) {
                val sorted = g.sortedBy { it.timestamp }
                for (i in 1 until sorted.size - 1 step 2) {
                    val m = sorted[i]
                    if (now - m.timestamp > tier && now - m.timestamp > KEEP_NEW_MS) candidates += m
                }
            }
            for (m in candidates.sortedBy { it.timestamp }) {
                if (done()) break
                deleted += m.id; recompressed -= m.id
                bytes -= m.thumbBytes + m.otherBytes; rows--
            }
        }

        // 3. still too much: remove the oldest unprotected moments
        for (m in oldestFirst) {
            if (done()) break
            if (m.protected || m.id in deleted || now - m.timestamp < KEEP_NEW_MS) continue
            deleted += m.id; recompressed -= m.id
            bytes -= m.thumbBytes + m.otherBytes; rows--
        }
        return Plan(recompressed.toList(), deleted.toList(), before, bytes.coerceAtLeast(0))
    }

    /** "4.3 MB", "812 KB" */
    fun size(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1e9)
        bytes >= 1_000_000L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1e6)
        else -> "${bytes / 1000} KB"
    }
}
