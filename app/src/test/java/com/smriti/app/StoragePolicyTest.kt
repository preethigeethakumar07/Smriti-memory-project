package com.smriti.app

import com.smriti.app.ai.StoragePolicy
import com.smriti.app.ai.StoragePolicy.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePolicyTest {
    private val H = 3_600_000L
    private val D = 24 * H
    private val now = 100 * D

    /** a recording of [n] moments, one every 4 s, ending [endedAgo] ms ago */
    private fun session(id: Long, n: Int, endedAgo: Long, thumb: Long = 16_000, other: Long = 3_500, protectCover: Boolean = false): List<Item> =
        (0 until n).map { i -> Item(id * 10_000 + i, now - endedAgo - (n - i) * 4_000L, id, thumb, other, protected = protectCover && i == n / 2 + 1) }

    @Test fun underBudgetNothingHappens() {
        val items = session(1, 100, 5 * D)                       // 100 * 19.5 KB = 1.95 MB
        val p = StoragePolicy.plan(items, budgetBytes = 300_000_000L, maxRows = 6000, now = now)
        assertTrue(p.isEmpty)
        assertEquals(p.bytesBefore, p.bytesAfter)
    }

    @Test fun smallerPicturesComeBeforeDeleting() {
        val items = session(1, 1000, 5 * D)                      // 19.5 MB
        val p = StoragePolicy.plan(items, budgetBytes = 20_000_000L, maxRows = 6000, now = now)       // 97 % used
        assertTrue(p.recompress.isNotEmpty())
        assertTrue("nothing is deleted while smaller pictures are enough", p.delete.isEmpty())
        assertTrue(p.bytesAfter <= 20_000_000L * 0.70)
    }

    @Test fun recentMomentsAreNeverTouched() {
        val items = session(1, 500, 30 * 60_000L)                // finished 30 minutes ago
        val p = StoragePolicy.plan(items, budgetBytes = 1_000_000L, maxRows = 6000, now = now)
        assertTrue(p.delete.isEmpty())
        assertTrue(p.recompress.isEmpty())
    }

    @Test fun thinningKeepsTheFirstAndLastMomentOfARecording() {
        val items = session(1, 400, 5 * D)
        val p = StoragePolicy.plan(items, budgetBytes = 100_000_000L, maxRows = 400, now = now)       // too many rows
        assertTrue(p.delete.isNotEmpty())
        val first = items.first().id; val last = items.last().id
        assertFalse(first in p.delete)
        assertFalse(last in p.delete)
        // every second one only: no two neighbours are both deleted
        val deleted = p.delete.toSet()
        for (i in 0 until items.size - 1) assertFalse(items[i].id in deleted && items[i + 1].id in deleted)
    }

    @Test fun protectedMomentsSurvive() {
        val items = session(1, 400, 5 * D, protectCover = true)
        val cover = items[201].id
        val p = StoragePolicy.plan(items, budgetBytes = 1_000_000L, maxRows = 400, now = now)         // far over budget
        assertFalse(cover in p.delete)
    }

    @Test fun oldestGoFirstWhenThinningIsNotEnough() {
        val old = session(1, 50, 20 * D)
        val newer = session(2, 50, 4 * D)
        val p = StoragePolicy.plan(old + newer, budgetBytes = 600_000L, maxRows = 6000, now = now)
        val del = p.delete.toSet()
        val oldDeleted = old.count { it.id in del }
        val newerDeleted = newer.count { it.id in del }
        assertTrue(oldDeleted >= newerDeleted)
    }

    @Test fun reachesTheTargetWhenPossible() {
        val items = session(1, 2000, 10 * D) + session(2, 2000, 3 * D)       // about 78 MB
        val budget = 60_000_000L
        val p = StoragePolicy.plan(items, budget, maxRows = 6000, now = now)
        assertTrue(p.bytesAfter <= budget * 0.70 + 20_000)
        assertTrue(p.bytesAfter < p.bytesBefore)
    }

    @Test fun recompressAndDeleteNeverOverlap() {
        val items = session(1, 3000, 6 * D) + session(2, 500, 40 * H)
        val p = StoragePolicy.plan(items, 30_000_000L, 6000, now)
        assertTrue(p.recompress.toSet().intersect(p.delete.toSet()).isEmpty())
    }

    @Test fun rowCapAloneTriggersThinning() {
        val items = session(1, 5500, 5 * D, thumb = 1_000, other = 500)         // tiny bytes but 92 % of the row cap
        val p = StoragePolicy.plan(items, 500_000_000L, 6000, now)
        assertTrue(p.delete.isNotEmpty())
    }

    @Test fun forcedPlanFreesMoreThanTheDefaultOne() {
        val items = session(1, 1000, 5 * D)                                        // 19.5 MB of a 40 MB budget (49 %)
        val normal = StoragePolicy.plan(items, 40_000_000L, 6000, now)
        val forced = StoragePolicy.plan(items, 40_000_000L, 6000, now, startAt = 0.30, target = 0.25)
        assertTrue(normal.isEmpty)
        assertTrue(forced.bytesAfter < forced.bytesBefore)
    }

    @Test fun sizeText() {
        assertEquals("812 KB", StoragePolicy.size(812_000))
        assertEquals("4.3 MB", StoragePolicy.size(4_300_000))
        assertEquals("1.2 GB", StoragePolicy.size(1_200_000_000))
    }
}
