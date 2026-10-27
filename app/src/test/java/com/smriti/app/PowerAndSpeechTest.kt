package com.smriti.app

import com.smriti.app.core.FrameRates
import com.smriti.app.core.PowerPolicy
import com.smriti.app.live.SpeechText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerAndSpeechTest {

    // ---- power policy ------------------------------------------------------------------------------------------
    @Test fun liveCameraIsSmoothWhenCoolAndSlowsWhenWarm() {
        assertEquals(24, PowerPolicy.decide(PowerPolicy.Where.LIVE_CAMERA, 0).cameraFps)
        assertEquals(15, PowerPolicy.decide(PowerPolicy.Where.LIVE_CAMERA, 2).cameraFps)
        assertEquals(10, PowerPolicy.decide(PowerPolicy.Where.LIVE_CAMERA, 3).cameraFps)
        assertEquals(500L, PowerPolicy.decide(PowerPolicy.Where.LIVE_CAMERA, 0).tickMs)
        assertEquals(2000L, PowerPolicy.decide(PowerPolicy.Where.LIVE_CAMERA, 3).tickMs)
    }

    @Test fun homeRunsTheCameraAtTheMinimum() {
        val p = PowerPolicy.decide(PowerPolicy.Where.HOME, 0)
        assertEquals(10, p.cameraFps)
        assertEquals(1000L, p.tickMs)
        assertTrue(p.allowInventory)
        assertFalse(PowerPolicy.decide(PowerPolicy.Where.HOME, 2).allowInventory)
    }

    @Test fun inventoryNeverRunsDuringLive() {
        assertFalse(PowerPolicy.decide(PowerPolicy.Where.LIVE_CAMERA, 0).allowInventory)
        assertFalse(PowerPolicy.decide(PowerPolicy.Where.LIVE_VOICE, 0).allowInventory)
    }

    @Test fun aCoveredLensIsLookedAtRarely() {
        val p = PowerPolicy.decide(PowerPolicy.Where.HOME, 0, blankStreak = PowerPolicy.BLANK_BACKOFF)
        assertTrue(p.tickMs >= 4000L)
        assertTrue(p.ambientGapScale >= 4)
        val q = PowerPolicy.decide(PowerPolicy.Where.HOME, 0, blankStreak = PowerPolicy.BLANK_BACKOFF - 1)
        assertEquals(1000L, q.tickMs)
    }

    @Test fun theForecastSlowsTheAppBeforeThePhoneIsHot() {
        assertEquals(0, PowerPolicy.level(0, 0.3f))
        assertEquals(1, PowerPolicy.level(0, 0.75f))
        assertEquals(2, PowerPolicy.level(0, 0.9f))
        assertEquals(3, PowerPolicy.level(0, 0.97f))
        assertEquals(3, PowerPolicy.level(3, 0.1f))        // the real status is never lowered by a forecast
        assertEquals(2, PowerPolicy.level(2, Float.NaN))
        assertEquals(0, PowerPolicy.level(0, null))
    }

    @Test fun warmPhoneShowsAReason() {
        assertNull(PowerPolicy.decide(PowerPolicy.Where.HOME, 1).label)
        assertNotNull(PowerPolicy.decide(PowerPolicy.Where.HOME, 2).label)
        assertTrue(PowerPolicy.decide(PowerPolicy.Where.LIVE_CAMERA, 3).label!!.contains("hot", ignoreCase = true))
    }

    @Test fun picksTheClosestFixedFrameRate() {
        val offered = listOf(FrameRates.Range(10, 10), FrameRates.Range(15, 15), FrameRates.Range(10, 24), FrameRates.Range(24, 24), FrameRates.Range(10, 30), FrameRates.Range(30, 30))
        assertEquals(24, FrameRates.pick(offered, 24)!!.upper)
        assertEquals(24, FrameRates.pick(offered, 24)!!.lower)
        assertEquals(10, FrameRates.pick(offered, 10)!!.lower)
        assertEquals(10, FrameRates.pick(offered, 5)!!.upper)
        assertEquals(30, FrameRates.pick(offered, 60)!!.upper)
        assertNull(FrameRates.pick(emptyList(), 24))
    }

    // ---- speech and captions -----------------------------------------------------------------------------------
    private val list = "On the left side:\n*   A keyboard (appears to be a computer keyboard)\n*   A light-colored rectangular object\n\nIn the center:\n*   A tablet or small computer screen showing code."

    @Test fun aHeadingAloneIsNeverSpoken() {
        val said = SpeechText.spoken(list, detailed = false)
        assertTrue(said, said.startsWith("On the left side: A keyboard"))
        assertFalse(said.contains("*"))
    }

    @Test fun shortAnswersStayShort() {
        assertEquals("The laptop was on the kitchen table at 9:28 AM.", SpeechText.spoken("The laptop was on the kitchen table at 9:28 AM.\nPlace: kitchen\nSeen 3 times.", detailed = false))
    }

    @Test fun detailedSpeechReadsEverything() {
        val said = SpeechText.spoken(list, detailed = true)
        assertTrue(said.contains("In the center"))
        assertTrue(said.contains("showing code"))
    }

    @Test fun longSpeechIsCutAtASentence() {
        val long = (1..60).joinToString(" ") { "This is sentence number $it." }
        val said = SpeechText.spoken(long, detailed = false)
        assertTrue(said.length <= 340)
        assertTrue(said.endsWith("."))
    }

    @Test fun theScreenShowsTheWholeAnswer() {
        val shown = SpeechText.shown(list)
        assertTrue(shown.contains("On the left side:"))
        assertTrue(shown.contains("A keyboard"))
        assertTrue(shown.contains("showing code"))
        assertFalse(shown.contains("*"))
        assertTrue(shown.contains("• A keyboard"))
        assertEquals("one\ntwo", SpeechText.shown("one\n\n  two  "))
    }

    @Test fun veryLongAnswersAreCutOnTheScreenWithAnEllipsis() {
        val long = (1..400).joinToString(" ") { "Word$it." }
        val shown = SpeechText.shown(long)
        assertTrue(shown.length <= 1501)
        assertTrue(shown.endsWith("…"))
    }

    @Test fun captionStaysLongerForLongText() {
        assertEquals(8_000L, SpeechText.captionHoldMs("short"))
        assertTrue(SpeechText.captionHoldMs("x".repeat(600)) > 20_000L)
        assertEquals(40_000L, SpeechText.captionHoldMs("x".repeat(5000)))
    }

    @Test fun emptyAnswerSpeaksNothing() {
        assertEquals("", SpeechText.spoken("  \n ", detailed = false))
    }
}
