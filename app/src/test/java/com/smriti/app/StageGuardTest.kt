package com.smriti.app

import com.smriti.app.pipeline.EventType
import com.smriti.app.pipeline.FaultInjector
import com.smriti.app.pipeline.PipelineBus
import com.smriti.app.pipeline.PipelineConfig
import com.smriti.app.pipeline.Stage
import com.smriti.app.pipeline.StageGuard
import com.smriti.app.pipeline.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StageGuardTest {

    @Before fun setUp() { PipelineBus.clear(); FaultInjector.clear() }
    @After fun tearDown() { FaultInjector.clear() }

    private fun stageEvents(stage: String) =
        PipelineBus.snapshot().filter { it.type == EventType.STAGE && it.stage == stage }

    @Test fun successEmitsStartThenOkWithDuration() = runBlocking {
        val g = StageGuard()
        val r = g.run(1, Stage.LABEL, fallback = -1) { 42 }
        assertTrue(r.ok); assertTrue(r.ran); assertEquals(42, r.value)
        assertEquals(listOf(Status.START, Status.OK), stageEvents(Stage.LABEL).map { it.status })
        assertTrue(stageEvents(Stage.LABEL).last().durMs >= 0)
    }

    @Test fun failureReturnsFallbackAndReportsError() = runBlocking {
        val g = StageGuard()
        val r = g.run(1, Stage.OCR, fallback = "none") { error("boom") }
        assertFalse(r.ok); assertTrue(r.ran); assertEquals("none", r.value)
        val err = stageEvents(Stage.OCR).last()
        assertEquals(Status.ERROR, err.status); assertEquals("boom", err.message)
    }

    @Test fun tripsAfterLimitAndStopsRunning() = runBlocking {
        val g = StageGuard(limit = 3)
        var calls = 0
        repeat(3) { g.run(it.toLong(), Stage.DETECT, fallback = 0) { calls++; error("x") } }
        assertTrue(g.isTripped(Stage.DETECT))
        val after = g.run(9, Stage.DETECT, fallback = 0) { calls++; 1 }
        assertEquals(3, calls)                         // the block is not even called once tripped
        assertFalse(after.ran); assertFalse(after.ok)
        assertEquals(Status.DISABLED, stageEvents(Stage.DETECT).last().status)
    }

    @Test fun rearmRestoresTheStage() = runBlocking {
        val g = StageGuard(limit = 1)
        g.run(1, Stage.LABEL, fallback = 0) { error("x") }
        assertTrue(g.isTripped(Stage.LABEL))
        g.rearm(Stage.LABEL)
        assertFalse(g.isTripped(Stage.LABEL))
        assertEquals(7, g.run(2, Stage.LABEL, fallback = 0) { 7 }.value)
    }

    @Test fun aSuccessResetsTheFailureCount() = runBlocking {
        val g = StageGuard(limit = 3)
        repeat(2) { g.run(it.toLong(), Stage.OCR, fallback = 0) { error("x") } }
        g.run(3, Stage.OCR, fallback = 0) { 1 }
        repeat(2) { g.run(it.toLong(), Stage.OCR, fallback = 0) { error("x") } }
        assertFalse(g.isTripped(Stage.OCR))            // 2 + reset + 2 never reaches 3 in a row
    }

    @Test fun disabledStageIsSkippedNotFailed() = runBlocking {
        val g = StageGuard()
        var ran = false
        val r = g.run(1, Stage.DETECT, fallback = 0, enabled = false) { ran = true; 1 }
        assertFalse(ran); assertFalse(r.ran); assertTrue(r.ok)
        assertEquals(Status.SKIPPED, stageEvents(Stage.DETECT).single().status)
    }

    @Test fun cancellationIsNeverSwallowed() {
        val g = StageGuard()
        var thrown: Throwable? = null
        runBlocking {
            try { g.run(1, Stage.LABEL, fallback = 0) { throw CancellationException("stop") } }
            catch (e: CancellationException) { thrown = e }
        }
        assertTrue(thrown is CancellationException)
        assertFalse(g.isTripped(Stage.LABEL))
    }

    @Test fun faultInjectionMakesTheStageFail() = runBlocking {
        FaultInjector.set(Stage.LABEL, true)
        val g = StageGuard()
        val r = g.run(1, Stage.LABEL, fallback = "fallback") { "real" }
        assertFalse(r.ok); assertEquals("fallback", r.value)
        FaultInjector.set(Stage.LABEL, false)
        assertEquals("real", g.run(2, Stage.LABEL, fallback = "fallback") { "real" }.value)
    }

    @Test fun busRingKeepsTheNewestEvents() {
        PipelineBus.clear()
        repeat(450) { PipelineBus.info("t", "m$it") }
        val snap = PipelineBus.snapshot()
        assertEquals(400, snap.size)
        assertEquals("m449", snap.last().message)
    }

    @Test fun presetsToggleTheRightStages() {
        val fast = PipelineConfig.preset("fast")
        assertFalse(fast.detectOn); assertTrue(fast.labelOn); assertFalse(fast.ocrOn)
        val custom = PipelineConfig.preset("balanced").withStage(Stage.OCR, false)
        assertEquals("custom", custom.preset); assertFalse(custom.ocrOn); assertTrue(custom.labelOn)
    }

    @Test fun privacyStageCannotBeToggledOffByConfig() {
        val c = PipelineConfig()
        assertEquals(c, c.withStage(Stage.PRIVACY, false))
    }
}
