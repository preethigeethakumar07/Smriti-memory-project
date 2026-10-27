package com.smriti.app

import com.smriti.app.ai.MomentRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MomentRefTest {

    @Test fun roundTrip() {
        val refs = listOf(MomentRef(12, 3_000, 7_000, "Kitchen 4:50 AM"), MomentRef(14, 125_000, 131_000, "Today 5:46 AM"))
        val back = MomentRef.decode(MomentRef.encode(refs))
        assertEquals(2, back.size)
        assertEquals(12L, back[0].sessionId); assertEquals(3_000L, back[0].startOffsetMs); assertEquals(7_000L, back[0].endOffsetMs)
        assertEquals("Kitchen 4:50 AM", back[0].label)
        assertEquals("02:05 - 02:11", back[1].range)
    }

    @Test fun separatorsInLabelsDoNotBreakIt() {
        val back = MomentRef.decode(MomentRef.encode(listOf(MomentRef(1, 0, 4_000, "a|b;c"))))
        assertEquals(1, back.size)
        assertEquals("a b c", back[0].label)
    }

    @Test fun emptyAndGarbageAreSafe() {
        assertNull(MomentRef.encode(emptyList()))
        assertTrue(MomentRef.decode(null).isEmpty())
        assertTrue(MomentRef.decode("not|valid;;").isEmpty())
    }
}
