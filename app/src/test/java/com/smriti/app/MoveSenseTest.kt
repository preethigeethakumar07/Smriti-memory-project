package com.smriti.app

import com.smriti.app.move.CompassBuzz
import com.smriti.app.move.Cue
import com.smriti.app.move.CueType
import com.smriti.app.move.CuePolicy
import com.smriti.app.move.Geo
import com.smriti.app.move.Guidance
import com.smriti.app.move.HapticId
import com.smriti.app.move.HeadingFromRotation
import com.smriti.app.move.LatLon
import com.smriti.app.move.PdrTracker
import com.smriti.app.move.Pt
import com.smriti.app.move.RouteBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class MoveSenseTest {

    // ---- geometry ----------------------------------------------------------------------------------------------
    @Test fun distanceAndBearingOverShortWalks() {
        val a = LatLon(12.9716, 77.5946)
        val north = LatLon(12.9716 + 0.001, 77.5946)            // 0.001 degree of latitude is about 111 m
        assertEquals(111.2, Geo.distance(a, north), 1.0)
        assertEquals(0.0, Geo.bearing(a, north), 0.5)
        val east = Geo.fromLocal(a, 100.0, 0.0)
        assertEquals(100.0, Geo.distance(a, east), 0.5)
        assertEquals(90.0, Geo.bearing(a, east), 0.5)
    }

    @Test fun localConversionRoundTrips() {
        val o = LatLon(28.6139, 77.2090)
        val p = Geo.fromLocal(o, 123.0, -456.0)
        val back = Geo.toLocal(o, p)
        assertEquals(123.0, back.x, 0.01); assertEquals(-456.0, back.y, 0.01)
    }

    @Test fun turnsAreSignedAndWrapAround() {
        assertEquals(20.0, Geo.diff(10.0, 350.0), 1e-9)       // 350 -> 10 is a small turn right
        assertEquals(-20.0, Geo.diff(350.0, 10.0), 1e-9)
        assertEquals(180.0, abs(Geo.diff(0.0, 180.0)), 1e-9)
        assertEquals(0.0, Geo.diff(Geo.circularMean(listOf(350.0, 10.0)), 0.0), 1e-6)      // 0 and 360 are the same direction
        assertEquals(90.0, Geo.bearing(Pt(0.0, 0.0), Pt(5.0, 0.0)), 1e-9)
    }

    // ---- dead reckoning ----------------------------------------------------------------------------------------
    @Test fun walkingEastMovesEast() {
        val t = PdrTracker(0.70)
        repeat(100) { t.step(90.0) }
        assertEquals(70.0, t.x, 0.01); assertEquals(0.0, t.y, 0.01)
        assertEquals(100, t.steps); assertEquals(70.0, t.distanceM, 0.01)
    }

    @Test fun anSquareWalkEndsWhereItStarted() {
        val t = PdrTracker(0.70)
        for (h in listOf(0.0, 90.0, 180.0, 270.0)) repeat(30) { t.step(h) }
        assertEquals(0.0, t.x, 0.01); assertEquals(0.0, t.y, 0.01)
    }

    @Test fun aGoodGpsFixPullsThePositionBack() {
        val t = PdrTracker(0.70)
        repeat(200) { t.step(90.0) }                         // 140 m, uncertainty 7 m
        val before = t.x
        val k = t.fuse(150.0, 0.0, 4.0)
        assertTrue(k in 0.5..0.95)
        assertTrue(t.x in before..150.0)
        assertTrue("uncertainty shrinks", t.sigma < 7.0)
    }

    @Test fun aPoorGpsFixIsIgnored() {
        val t = PdrTracker(0.70)
        repeat(50) { t.step(0.0) }
        assertEquals(0.0, t.fuse(500.0, 500.0, 80.0), 0.0)
        assertEquals(0.0, t.x, 1e-9)
    }

    @Test fun theStrideIsLearnedFromGps() {
        val t = PdrTracker(0.70)
        val real = 0.80
        var gx = 0.0
        repeat(40) {                                         // 40 blocks of 20 steps = 640 m east
            repeat(20) { t.step(90.0); gx += real }
            t.fuse(gx, 0.0, 3.0)
        }
        assertTrue("stride ${t.effectiveStride}", t.effectiveStride in 0.76..0.84)
    }

    // ---- heading from the phone's orientation -------------------------------------------------------------------
    private val flatNorth = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val flatEast = floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
    private val uprightNorth = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)

    @Test fun flatInTheHandUsesTheTopEdge() {
        assertEquals(0.0, HeadingFromRotation().heading(flatNorth)!!, 0.5)
        assertEquals(90.0, HeadingFromRotation().heading(flatEast)!!, 0.5)
    }

    @Test fun uprightLikeACameraUsesTheBackCamera() {
        assertEquals(0.0, HeadingFromRotation().heading(uprightNorth)!!, 0.5)
    }

    @Test fun noOrientationMeansNoHeading() {
        assertNull(HeadingFromRotation().heading(FloatArray(9)))
    }

    // ---- guidance ----------------------------------------------------------------------------------------------
    private val lShape = listOf(Pt(0.0, 0.0), Pt(80.0, 0.0), Pt(80.0, 60.0))

    @Test fun facingTheRightWayIsStraight() {
        val g = Guidance(lShape)
        val cue = g.update(5.0, 0.0, 90.0)
        assertEquals(CueType.STRAIGHT, cue.type)
        assertEquals(140.0 - 5.0, cue.remainingM, 1.0)                     // the path is 80 + 60 m long
    }

    @Test fun facingTheWrongWayIsATurnAround() {
        val g = Guidance(lShape)
        assertEquals(CueType.U_TURN, g.update(5.0, 0.0, 270.0).type)
    }

    @Test fun facingNorthWhenThePathGoesEastIsATurnRight() {
        assertEquals(CueType.RIGHT, Guidance(lShape).update(5.0, 0.0, 0.0).type)
    }

    @Test fun leftAndRightAreNotMixedUp() {
        // heading east (90) and the path points north: the user must turn LEFT
        val north = Guidance(listOf(Pt(0.0, 0.0), Pt(0.0, 100.0)))
        assertEquals(CueType.LEFT, north.update(0.0, 5.0, 90.0).type)
        // heading west (270) and the path points north: turn RIGHT
        assertEquals(CueType.RIGHT, Guidance(listOf(Pt(0.0, 0.0), Pt(0.0, 100.0))).update(0.0, 5.0, 270.0).type)
        // a little off to one side
        assertEquals(CueType.SLIGHT_RIGHT, Guidance(listOf(Pt(0.0, 0.0), Pt(0.0, 100.0))).update(0.0, 5.0, 330.0).type)
        assertEquals(CueType.SLIGHT_LEFT, Guidance(listOf(Pt(0.0, 0.0), Pt(0.0, 100.0))).update(0.0, 5.0, 30.0).type)
    }

    @Test fun farFromThePathIsOffRoute() {
        val g = Guidance(lShape)
        val cue = g.update(40.0, 45.0, 0.0)                    // facing north, the nearest part of the path is to the east
        assertEquals(CueType.OFF_ROUTE, cue.type)
        assertTrue(cue.crossTrackM > 30.0)
        assertTrue("the way back is to the right: ${cue.errDeg}", cue.errDeg in 60.0..120.0)
    }

    @Test fun arrivingIsLatched() {
        val g = Guidance(lShape, arriveRadiusM = 6.0)
        assertEquals(CueType.ARRIVED, g.update(80.0, 57.0, 0.0).type)
        assertEquals(CueType.ARRIVED, g.update(60.0, 40.0, 0.0).type)       // even if the estimate then wanders off
    }

    @Test fun theNextCornerIsAnnouncedWithItsSide() {
        val g = Guidance(lShape)
        val cue = g.update(65.0, 0.0, 90.0)
        assertEquals(CueType.LEFT, cue.nextTurnDir)                         // east then north is a left turn
        assertEquals(15.0, cue.nextTurnM!!, 1.0)
    }

    @Test fun theCueDoesNotFlickerAtTheEdgeOfABand() {
        val g = Guidance(listOf(Pt(0.0, 0.0), Pt(0.0, 200.0)))
        assertEquals(CueType.STRAIGHT, g.update(0.0, 5.0, 10.0).type)
        assertEquals(CueType.STRAIGHT, g.update(0.0, 6.0, 22.0).type)      // would be slight if it were entering the band
        assertEquals(CueType.SLIGHT_LEFT, g.update(0.0, 7.0, 40.0).type)      // facing north-east, the path is to the left
    }

    // ---- when to speak -----------------------------------------------------------------------------------------
    private fun cue(t: CueType, err: Double = 0.0, turnM: Double? = null, turnId: Int = -1, dir: CueType? = null) = Cue(t, err, 100.0, 1.0, turnM, dir, turnId)

    @Test fun speaksOnChangeButNotTooOften() {
        val p = CuePolicy(minGapMs = 3000)
        assertEquals("Go straight", p.next(cue(CueType.STRAIGHT), 0)!!.phrase)
        assertNull(p.next(cue(CueType.STRAIGHT), 1000))
        assertNull("changed but too soon", p.next(cue(CueType.LEFT), 1500))
        assertEquals("Turn left", p.next(cue(CueType.LEFT), 3500)!!.phrase)
    }

    @Test fun repeatsWhileTheUserStillHasToTurn() {
        val p = CuePolicy(minGapMs = 3000, repeatMs = 14000)
        p.next(cue(CueType.RIGHT), 0)
        assertNull(p.next(cue(CueType.RIGHT), 8000))
        assertEquals("Turn right", p.next(cue(CueType.RIGHT), 15000)!!.phrase)
    }

    @Test fun arrivalIsSaidOnce() {
        val p = CuePolicy()
        assertEquals(HapticId.ARRIVE, p.next(cue(CueType.ARRIVED), 0)!!.haptic)
        assertNull(p.next(cue(CueType.ARRIVED), 5000))
    }

    @Test fun aCornerIsAnnouncedAtTwentyAndAtFiveMetres() {
        val p = CuePolicy()
        p.next(cue(CueType.STRAIGHT), 0)
        val far = p.next(cue(CueType.STRAIGHT, turnM = 19.0, turnId = 2, dir = CueType.LEFT), 5000)
        assertEquals("In 15 metres turn left", far!!.phrase)
        assertNull(p.next(cue(CueType.STRAIGHT, turnM = 15.0, turnId = 2, dir = CueType.LEFT), 9000))
        assertEquals("Turn left now", p.next(cue(CueType.STRAIGHT, turnM = 4.0, turnId = 2, dir = CueType.LEFT), 13000)!!.phrase)
        assertNull(p.next(cue(CueType.STRAIGHT, turnM = 3.0, turnId = 2, dir = CueType.LEFT), 14000))
    }

    @Test fun keepGoingIsSaidRarely() {
        val p = CuePolicy(keepGoingMs = 45_000)
        p.next(cue(CueType.STRAIGHT), 0)
        assertNull(p.next(cue(CueType.STRAIGHT), 20_000))
        assertEquals("Keep going", p.next(cue(CueType.STRAIGHT), 46_000)!!.phrase)
    }

    @Test fun everyCueHasAShortPhraseAndABuzz() {
        for (t in CueType.values()) {
            val phrase = CuePolicy.phrase(cue(t, err = 40.0))
            assertTrue("$t: '$phrase'", phrase.isNotBlank() && phrase.length < 60)
            assertNotNull(CuePolicy.haptic(t))
        }
        assertEquals(HapticId.LEFT, CuePolicy.haptic(CueType.SLIGHT_LEFT))
        assertEquals(HapticId.RIGHT, CuePolicy.haptic(CueType.RIGHT))
    }

    // ---- the haptic compass -------------------------------------------------------------------------------------
    @Test fun theBuzzGetsSteadierTheCloserTheAim() {
        val on = CompassBuzz.pulse(4.0)!!; val near = CompassBuzz.pulse(25.0)!!; val far = CompassBuzz.pulse(70.0)!!
        assertTrue(on.onMs > near.onMs && near.onMs > far.onMs)
        assertTrue(on.offMs < near.offMs && near.offMs < far.offMs)
        assertNull("facing away: no buzz", CompassBuzz.pulse(170.0))
        assertEquals(CompassBuzz.pulse(20.0)!!.onMs, CompassBuzz.pulse(-20.0)!!.onMs)
    }

    // ---- recording a route ---------------------------------------------------------------------------------------
    @Test fun aStraightWalkSimplifiesToItsEnds() {
        val rnd = Random(5)
        val b = RouteBuilder(minGapM = 3.0)
        for (i in 0..60) b.add(Pt(i * 3.1, rnd.nextGaussian() * 0.3))
        val s = b.simplified(1.5)
        assertEquals(2, s.size)
    }

    @Test fun aCornerIsKept() {
        val b = RouteBuilder(minGapM = 3.0)
        for (i in 0..20) b.add(Pt(i * 3.0, 0.0))
        for (j in 1..20) b.add(Pt(60.0, j * 3.0))
        val s = b.simplified(1.5)
        assertEquals(3, s.size)
        assertEquals(Pt(60.0, 0.0), s[1])
        assertEquals(120.0, RouteBuilder.length(s), 0.5)
    }

    @Test fun pointsCloserThanTheGapAreSkipped() {
        val b = RouteBuilder(minGapM = 3.0)
        b.add(Pt(0.0, 0.0)); b.add(Pt(1.0, 0.0)); b.add(Pt(2.5, 0.0)); b.add(Pt(3.2, 0.0))
        assertEquals(2, b.size)
    }

    // ---- whole walks, simulated ----------------------------------------------------------------------------------
    private class Walk(val arrived: Boolean, val steps: Int, val trueEnd: Pt, val cues: List<CueType>)

    /**
     * A person who does what the cues say. The phone reports a heading with a constant error (bias) and random error; the stride really
     * is a few percent different from the one assumed; GPS (when there is any) has random error.
     */
    private fun walk(
        path: List<Pt>, start: Pt, startHeading: Double, rnd: Random,
        realStride: Double, assumedStride: Double = 0.70, headingBias: Double, headingSigma: Double,
        gpsSigma: Double?, arriveM: Double, maxSteps: Int = 600
    ): Walk {
        val pdr = PdrTracker(assumedStride); pdr.reset(start.x, start.y)
        val g = Guidance(path, arriveRadiusM = arriveM)
        var tx = start.x; var ty = start.y; var h = startHeading
        val cues = ArrayList<CueType>()
        for (i in 1..maxSteps) {
            val measured = h + headingBias + rnd.nextGaussian() * headingSigma
            pdr.step(measured)
            tx += realStride * Math.sin(Math.toRadians(h)); ty += realStride * Math.cos(Math.toRadians(h))
            if (gpsSigma != null && i % 2 == 0) pdr.fuse(tx + rnd.nextGaussian() * gpsSigma, ty + rnd.nextGaussian() * gpsSigma, gpsSigma)
            val cue = g.update(pdr.x, pdr.y, measured)
            if (cues.lastOrNull() != cue.type) cues += cue.type
            if (cue.type == CueType.ARRIVED) return Walk(true, i, Pt(tx, ty), cues)
            // the person turns towards what the cue says (at most 35 degrees a step), and otherwise keeps walking straight
            if (cue.type != CueType.STRAIGHT) h += (cue.errDeg * 0.6).coerceIn(-35.0, 35.0)
        }
        return Walk(false, maxSteps, Pt(tx, ty), cues)
    }

    @Test fun outdoorsWithGpsTheWalkerArrivesAtTheTarget() {
        val path = listOf(Pt(0.0, 0.0), Pt(80.0, 0.0), Pt(80.0, 60.0), Pt(20.0, 60.0))
        val w = walk(path, Pt(0.0, 0.0), 90.0, Random(11), realStride = 0.72, headingBias = 3.0, headingSigma = 5.0, gpsSigma = 4.0, arriveM = 6.0)
        assertTrue("arrived after ${w.steps} steps", w.arrived)
        assertTrue("true position ${w.trueEnd} is within 12 m of the end", Geo.distance(w.trueEnd, path.last()) < 12.0)
        assertTrue("walked a sensible number of steps (${w.steps})", w.steps in 250..330)
    }

    @Test fun indoorsWithoutGpsTheWalkerStillArrivesNearTheTarget() {
        val path = listOf(Pt(0.0, 0.0), Pt(20.0, 0.0), Pt(20.0, 15.0))
        val w = walk(path, Pt(0.0, 0.0), 90.0, Random(2), realStride = 0.67, headingBias = 6.0, headingSigma = 4.0, gpsSigma = null, arriveM = 2.5)
        assertTrue("arrived after ${w.steps} steps", w.arrived)
        assertTrue("true position ${w.trueEnd} within 8 m of the end", Geo.distance(w.trueEnd, path.last()) < 8.0)
    }

    @Test fun walkingTheRecordedRouteBackLeadsHome() {
        // out: 60 m east then 40 m north. back: the same points reversed, starting at the far end facing north.
        val out = listOf(Pt(0.0, 0.0), Pt(60.0, 0.0), Pt(60.0, 40.0))
        val back = out.reversed()
        val w = walk(back, Pt(60.0, 40.0), 0.0, Random(21), realStride = 0.70, headingBias = 2.0, headingSigma = 4.0, gpsSigma = 5.0, arriveM = 6.0)
        assertTrue("arrived after ${w.steps} steps", w.arrived)
        assertTrue("home reached: ${w.trueEnd}", Geo.distance(w.trueEnd, Pt(0.0, 0.0)) < 12.0)
        assertTrue("the first instruction is to turn around", w.cues.first() == CueType.U_TURN)
    }

    @Test fun walkingTheWrongWayIsToldToTurnAround() {
        val path = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0))
        val g = Guidance(path)
        val pdr = PdrTracker(); pdr.reset(10.0, 0.0)
        var turnedAround = false
        repeat(40) { pdr.step(270.0); if (g.update(pdr.x, pdr.y, 270.0).type == CueType.U_TURN) turnedAround = true }
        assertTrue(turnedAround)
    }

    @Test fun theOffRouteWalkerIsBroughtBack() {
        val path = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0))
        val g = Guidance(path)
        val cue = g.update(50.0, 35.0, 90.0)
        assertEquals(CueType.OFF_ROUTE, cue.type)
        // the way back is south; the walker faces east, so the turn is to the right
        assertTrue("${cue.errDeg}", cue.errDeg in 60.0..120.0)
    }
}
