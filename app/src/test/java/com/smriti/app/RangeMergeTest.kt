package com.smriti.app

import com.smriti.app.ai.RangeMerge
import com.smriti.app.ai.RangeMerge.Win
import org.junit.Assert.assertEquals
import org.junit.Test

class RangeMergeTest {

    private fun w(id: Long, session: Long, start: Long, score: Float) = Win(id, session, start, start + 4_000, score)

    @Test fun neighbouringWindowsBecomeOneRange() {
        val r = RangeMerge.merge(listOf(w(1, 1, 0, 0.40f), w(2, 1, 1_500, 0.52f), w(3, 1, 3_000, 0.45f)))
        assertEquals(1, r.size)
        assertEquals(0L, r[0].start); assertEquals(7_000L, r[0].end)
        assertEquals(0.52f, r[0].score, 0f)
        assertEquals(2L, r[0].bestId)
        assertEquals(listOf(1L, 2L, 3L), r[0].ids)
    }

    @Test fun aLongGapKeepsRangesApart() {
        val r = RangeMerge.merge(listOf(w(1, 1, 0, 0.5f), w(2, 1, 30_000, 0.6f)))
        assertEquals(2, r.size)
        assertEquals(30_000L, r[0].start)            // best score first
    }

    @Test fun differentSessionsNeverMerge() {
        val r = RangeMerge.merge(listOf(w(1, 1, 0, 0.5f), w(2, 2, 0, 0.5f)))
        assertEquals(2, r.size)
    }

    @Test fun unsortedInputIsHandled() {
        val r = RangeMerge.merge(listOf(w(3, 1, 3_000, 0.4f), w(1, 1, 0, 0.5f), w(2, 1, 1_500, 0.45f)))
        assertEquals(1, r.size)
        assertEquals(0L, r[0].start)
    }
}
