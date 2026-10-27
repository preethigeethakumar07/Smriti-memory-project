package com.smriti.app

import com.smriti.app.privacy.SensitiveMath
import com.smriti.app.privacy.SensitiveMath.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveMathTest {
    @Test fun marginIsRiskyMinusOrdinary() {
        assertEquals(0.05f, SensitiveMath.margin(0.35f, 0.30f), 1e-6f)
        assertEquals(-0.1f, SensitiveMath.margin(0.2f, 0.3f), 1e-6f)
    }

    @Test fun verdictBands() {
        assertEquals(Verdict.CLEAR, SensitiveMath.verdict(0.00f, 0.035f, 0.10f))
        assertEquals(Verdict.CLEAR, SensitiveMath.verdict(0.034f, 0.035f, 0.10f))
        assertEquals(Verdict.CHECK, SensitiveMath.verdict(0.035f, 0.035f, 0.10f))
        assertEquals(Verdict.CHECK, SensitiveMath.verdict(0.09f, 0.035f, 0.10f))
        assertEquals(Verdict.BLOCK, SensitiveMath.verdict(0.10f, 0.035f, 0.10f))
        assertEquals(Verdict.BLOCK, SensitiveMath.verdict(0.40f, 0.035f, 0.10f))
    }

    @Test fun onlyAClearNoLetsAPictureThrough() {
        assertFalse(SensitiveMath.answerIsSensitive("NO"))
        assertFalse(SensitiveMath.answerIsSensitive("  No. "))
        assertTrue(SensitiveMath.answerIsSensitive("YES"))
        assertTrue(SensitiveMath.answerIsSensitive("yes, the person is topless"))
        // a refusal, a lecture or no answer at all is treated as sensitive: when in doubt, drop
        assertTrue(SensitiveMath.answerIsSensitive("I cannot assist with that request."))
        assertTrue(SensitiveMath.answerIsSensitive(""))
        assertTrue(SensitiveMath.answerIsSensitive(null))
    }

    @Test fun percentileByNearestRank() {
        val v = (1..100).map { it.toFloat() }
        assertEquals(50f, SensitiveMath.percentile(v, 50.0), 0f)
        assertEquals(99f, SensitiveMath.percentile(v, 99.0), 0f)
        assertEquals(100f, SensitiveMath.percentile(v, 100.0), 0f)
        assertEquals(0f, SensitiveMath.percentile(emptyList(), 50.0), 0f)
    }

    @Test fun descriptionListsAreNotEmptyAndDistinct() {
        assertTrue(SensitiveMath.RISKY.size >= 5)
        assertTrue(SensitiveMath.ORDINARY.size >= 5)
        assertEquals(SensitiveMath.RISKY.size, SensitiveMath.RISKY.toSet().size)
        assertTrue(SensitiveMath.RISKY.intersect(SensitiveMath.ORDINARY.toSet()).isEmpty())
    }
}
