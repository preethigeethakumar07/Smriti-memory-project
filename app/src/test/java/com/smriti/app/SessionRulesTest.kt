package com.smriti.app

import com.smriti.app.ai.SessionRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class SessionRulesTest {

    private fun at(d: Int, h: Int, m: Int) = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, d, h, m, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    @Test fun shortOrEmptySessionsAreNotKept() {
        assertFalse(SessionRules.keep(10_000, 5))       // too short
        assertFalse(SessionRules.keep(60_000, 0))       // nothing indexed
        assertTrue(SessionRules.keep(15_000, 1))
    }

    @Test fun idleAndSplitLimits() {
        assertFalse(SessionRules.isIdle(0, 59_000))
        assertTrue(SessionRules.isIdle(0, 61_000))
        assertFalse(SessionRules.shouldSplit(0, 29 * 60_000L))
        assertTrue(SessionRules.shouldSplit(0, 30 * 60_000L))
    }

    @Test fun mostCommonPlaceIgnoresUnknown() {
        assertEquals("kitchen", SessionRules.mostCommon(listOf("somewhere", "kitchen", "", "hall", "kitchen")))
        assertEquals("", SessionRules.mostCommon(listOf("somewhere", "")))
    }

    @Test fun durationAndClockText() {
        assertEquals("45 s", SessionRules.duration(45_000))
        assertEquals("14 min", SessionRules.duration(14 * 60_000L + 20_000))
        assertEquals("1 h 05 min", SessionRules.duration(65 * 60_000L))
        assertEquals("00:03", SessionRules.clock(3_400))
        assertEquals("1:02:05", SessionRules.clock((3600 + 125) * 1000L))
    }

    @Test fun labelSaysTodayYesterdayOrDate() {
        val now = at(10, 16, 0)
        assertEquals("Today 3:20 PM - 3:34 PM", SessionRules.label(at(10, 15, 20), at(10, 15, 34), now, Locale.US).replace(' ', ' '))
        assertEquals("Today 3:20 PM", SessionRules.label(at(10, 15, 20), at(10, 15, 20) + 39_000, now, Locale.US).replace(' ', ' '))
        assertEquals("Yesterday", SessionRules.dayWord(at(9, 22, 0), now, Locale.US))
        assertEquals("7 Oct", SessionRules.dayWord(at(7, 9, 0), now, Locale.US))
    }
}
