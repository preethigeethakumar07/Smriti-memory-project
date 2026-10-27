package com.smriti.app

import com.smriti.app.gate.GateMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GateMathTest {

    private val thr = 0.08f

    private fun decide(hasRef: Boolean = true, score: Float = 0f, since: Long = 5_000, min: Long = 4_000, beat: Long = 20_000) =
        GateMath.decide(hasRef, score, since, min, beat, thr)

    @Test fun identicalFramesScoreZero() {
        val a = FloatArray(768) { 0.4f }
        assertEquals(0f, GateMath.changeScore(a, a.copyOf()), 0f)
    }

    @Test fun scoreIsMeanAbsoluteDifference() {
        val a = FloatArray(4) { 0f }
        val b = floatArrayOf(1f, 0f, 0f, 0f)
        assertEquals(0.25f, GateMath.changeScore(a, b), 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedSizesAreRejected() {
        GateMath.changeScore(FloatArray(3), FloatArray(4))
    }

    @Test fun firstFrameIsAlwaysAccepted() {
        val d = decide(hasRef = false, since = 0)
        assertTrue(d.accepted); assertEquals("first", d.reason)
    }

    @Test fun cooldownBlocksEvenBigChanges() {
        val d = decide(score = 0.9f, since = 1_000)
        assertFalse(d.accepted); assertEquals("cooldown", d.reason)
    }

    @Test fun changeAboveThresholdIsAccepted() {
        val d = decide(score = 0.2f)
        assertTrue(d.accepted); assertEquals("changed", d.reason)
    }

    @Test fun staticSceneIsRejectedUntilHeartbeat() {
        assertEquals("static", decide(score = 0.01f, since = 10_000).reason)
        val beat = decide(score = 0.01f, since = 25_000)
        assertTrue(beat.accepted); assertEquals("heartbeat", beat.reason)
    }

    @Test fun thresholdIsInclusive() {
        assertTrue(decide(score = thr).accepted)
        assertFalse(decide(score = thr - 0.001f).accepted)
    }
}
