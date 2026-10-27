package com.smriti.app

import com.smriti.app.memory.MovePlaceEntity
import com.smriti.app.memory.MoveRouteEntity
import com.smriti.app.move.Announcement
import com.smriti.app.move.CueType
import com.smriti.app.move.DistanceCalls
import com.smriti.app.move.Follow
import com.smriti.app.move.Geo
import com.smriti.app.move.HapticId
import com.smriti.app.move.HeadingFilter
import com.smriti.app.move.MoveController
import com.smriti.app.move.MoveIntent
import com.smriti.app.move.MoveMode
import com.smriti.app.move.MoveOutput
import com.smriti.app.move.MovePhrases
import com.smriti.app.move.MovePrefs
import com.smriti.app.move.MoveSim
import com.smriti.app.move.MoveStore
import com.smriti.app.move.Pt
import com.smriti.app.move.Say
import com.smriti.app.move.StraightGuide
import com.smriti.app.move.WifiPrint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeStore : MoveStore {
    val placeList = ArrayList<MovePlaceEntity>()
    val routeList = ArrayList<MoveRouteEntity>()
    private var nextId = 1L
    override suspend fun home() = placeList.lastOrNull { it.kind == "home" }
    override suspend fun places() = placeList.sortedBy { if (it.kind == "home") 0 else 1 }
    override suspend fun savePlace(p: MovePlaceEntity): Long {
        if (p.kind == "home") placeList.removeAll { it.kind == "home" }
        val id = nextId++; placeList += p.copy(id = id); return id
    }
    override suspend fun route(id: Long) = routeList.firstOrNull { it.id == id }
    override suspend fun lastTrip() = routeList.lastOrNull { it.kind == "trip" }
    override suspend fun saveRoute(r: MoveRouteEntity): Long {
        if (r.id == 0L) { val id = nextId++; routeList += r.copy(id = id); return id }
        val i = routeList.indexOfFirst { it.id == r.id }
        if (i >= 0) routeList[i] = r else routeList += r
        return r.id
    }
}

private class FakeOut : MoveOutput {
    val said = ArrayList<String>()
    val tags = ArrayList<String>()
    val buzzes = ArrayList<HapticId>()
    val pulses = ArrayList<Pair<Int, Int>>()
    override fun speak(text: String, languageTag: String) { said += text; tags += languageTag }
    override fun buzz(h: HapticId) { buzzes += h }
    override fun pulse(onMs: Int, amplitude: Int) { pulses += onMs to amplitude }
}

class MoveControllerTest {
    private val unconfined = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private class Rig(prefs: MovePrefs = MovePrefs(), scope: CoroutineScope) {
        val store = FakeStore()
        val out = FakeOut()
        var p = prefs
        val c = MoveController(store, out, { p }, scope, wallClock = { 1_700_000_000_000L })
    }

    private fun rig(prefs: MovePrefs = MovePrefs()) = Rig(prefs, unconfined)

    private fun home(r: Rig, sim: MoveSim, x: Double = 0.0, y: Double = 0.0, radius: Int = 20) {
        val ll = sim.mapPoint(x, y)
        runBlocking { r.store.savePlace(MovePlaceEntity(name = "Home", kind = "home", lat = ll.lat, lon = ll.lon, radiusM = radius, wifi = "", createdAt = 1)) }
    }

    // ---- going out and coming back --------------------------------------------------------------------------------
    @Test fun retraceOutdoorsBringsThePersonBackToTheStart() {
        val r = rig(); val sim = MoveSim(r.c)
        sim.place(0.0, 0.0, 0.0)
        r.c.startTrip(sim.t)
        sim.walkLeg(0.0, 120.0); sim.walkLeg(90.0, 80.0); sim.walkLeg(0.0, 40.0); sim.walkLeg(300.0, 30.0)
        runBlocking { r.c.goHome(sim.t) }
        assertEquals(MoveMode.GUIDING, r.c.state.value.mode)
        assertEquals(Follow.RETRACE, r.c.state.value.follow)
        val n = sim.follow(700)
        assertTrue("arrived after $n strides", r.c.state.value.arrived)
        assertTrue("ended ${sim.distanceTo(0.0, 0.0)} m from the start", sim.distanceTo(0.0, 0.0) < 16.0)
        assertTrue(r.out.said.any { it == "You have arrived" })
        assertTrue(r.out.buzzes.contains(HapticId.ARRIVE))
    }

    @Test fun theWalkIsSavedWithItsMapStart() {
        val r = rig(); val sim = MoveSim(r.c)
        sim.place(0.0, 0.0, 0.0)
        r.c.startTrip(sim.t)
        sim.walkLeg(0.0, 60.0); sim.walkLeg(90.0, 40.0)
        r.c.stop(sim.t)
        assertEquals(1, r.store.routeList.size)
        val route = r.store.routeList[0]
        assertEquals("trip", route.kind); assertTrue(route.hasGps)
        assertTrue("walk length ${route.lengthM}", route.lengthM in 85.0..115.0)
        assertTrue(route.name.startsWith("Walk "))
    }

    // ---- the drawing of the walk ----------------------------------------------------------------------------------------
    @Test fun theMapShowsTheWalkTheWayBackAndWhereToGo() {
        val r = rig(); val sim = MoveSim(r.c)
        sim.place(0.0, 0.0, 0.0)
        r.c.startTrip(sim.t)
        sim.walkLeg(0.0, 60.0); sim.walkLeg(90.0, 40.0)
        val walked = r.c.trail.value
        assertTrue("walk out: ${walked.out.size} points", walked.out.size >= 10)                  // the recorded walk, point by point
        assertNotNull(walked.here); assertNotNull(walked.headingDeg); assertNotNull(walked.start)
        assertTrue(walked.back.isEmpty() && walked.route.isEmpty())
        runBlocking { r.c.goHome(sim.t) }
        val led = r.c.trail.value
        assertTrue(led.route.size >= 2)                                                          // the way to follow
        assertTrue(led.out.size >= 10)                                                           // the walk out is still drawn
        assertNotNull(led.dest)
        sim.follow(400)
        val done = r.c.trail.value
        assertTrue("way back: ${done.back.size} points", done.back.size >= 10)                   // the way back that was walked
        assertNull(done.here)                                                                    // nothing is running any more
        assertTrue(done.out.size >= 10 && done.route.size >= 2)                                   // the finished walk stays on the map
    }

    @Test fun aStraightWalkHomeShowsThePlaceItIsGoingTo() {
        val r = rig(); val sim = MoveSim(r.c)
        home(r, sim); sim.place(150.0, 0.0, 90.0)
        runBlocking { r.c.goHome(sim.t) }
        sim.follow(15)
        val t = r.c.trail.value
        assertNotNull(t.dest); assertEquals("Home", t.destLabel)
        assertTrue("dest ${t.dest}", Geo.distance(t.dest!!, Pt(-150.0, 0.0)) < 15.0)             // 150 m west of where the walk began
        assertTrue(t.back.size >= 3)
        assertTrue(t.route.isEmpty())                                                            // no path to follow, only a place
    }

    @Test fun theDemoWalkGoesOutAndLeadsItselfBack() {
        val r = rig(); val sim = MoveSim(r.c)
        r.c.saving = false                                                   // as the demo does: nothing is kept
        com.smriti.app.move.MoveDemo.script(r.c, sim)
        assertTrue(r.c.state.value.arrived)
        assertTrue("ended ${sim.distanceTo(0.0, 0.0)} m from the start", sim.distanceTo(0.0, 0.0) < 16.0)
        assertTrue(r.out.said.isNotEmpty() && r.out.buzzes.isNotEmpty())
        assertTrue("a demo is not saved as a walk", r.store.routeList.isEmpty())
        val t = r.c.trail.value
        assertTrue(t.out.size >= 10 && t.back.size >= 10 && t.route.size >= 2)    // the map has the walk out, the way back and the way to go
    }

    @Test fun aNewJobStartsWithACleanMap() {
        val r = rig(); val sim = MoveSim(r.c)
        sim.place(0.0, 0.0, 0.0)
        r.c.startTrip(sim.t)
        sim.walkLeg(0.0, 40.0)
        r.c.stop(sim.t)
        assertTrue(r.c.trail.value.out.size >= 5)                                                // the finished walk is still shown
        r.c.startTrip(sim.t)
        assertTrue(r.c.trail.value.out.size <= 1)                                                // a new walk starts empty
    }

    @Test fun stoppingAWalkTooShortToKeepSaysSo() {
        val r = rig(); val sim = MoveSim(r.c)
        sim.place(0.0, 0.0, 0.0)
        r.c.startTrip(sim.t)
        sim.walkLeg(0.0, 4.0)
        assertTrue(r.c.stop(sim.t).contains("too short"))
        assertTrue(r.store.routeList.isEmpty())
        r.c.startTrip(sim.t)
        sim.walkLeg(0.0, 40.0)
        assertEquals("Okay. I saved your walk.", r.c.stop(sim.t))
        assertEquals(1, r.store.routeList.size)
    }

    @Test fun aLongWalkIsSavedOnceAndKeptUpToDate() {
        val r = rig(); val sim = MoveSim(r.c)
        sim.place(0.0, 0.0, 0.0)
        r.c.startTrip(sim.t)
        sim.walkLeg(0.0, 100.0)                                // about 80 s of walking: the walk is saved every 30 s on the way
        assertEquals("saved while still walking", 1, r.store.routeList.size)
        val midway = r.store.routeList[0].lengthM
        sim.walkLeg(90.0, 60.0)
        r.c.stop(sim.t)
        assertEquals(1, r.store.routeList.size)                // the same walk, updated, not a second one
        assertTrue(r.store.routeList[0].lengthM > midway + 30)
    }

    @Test fun aRealIndoorWalkRecordedOnThePhoneCanBeWalkedBack() {
        // recorded on the iQOO 15 by walking round a room with no GPS (steps and compass only): 40 m in five points
        val points = "0.0,0.0;-0.0,3.5;-23.3,5.3;-23.1,-5.1;-20.3,-6.5"
        val r = rig(); val sim = MoveSim(r.c, gpsOn = false)
        val id = runBlocking { r.store.saveRoute(MoveRouteEntity(name = "Walk", kind = "trip", originLat = 0.0, originLon = 0.0, hasGps = false, points = points, lengthM = 40.4, createdAt = 1)) }
        sim.place(-20.3, -6.5, 0.0)                               // standing where the walk ended
        val reply = runBlocking { r.c.guideRoute(id, reverse = true, nowMs = sim.t) }
        assertTrue(reply, reply.startsWith("Okay"))
        sim.follow(250)
        assertTrue(r.c.state.value.arrived)
        assertTrue("ended ${sim.distanceTo(0.0, 0.0)} m from where the walk began", sim.distanceTo(0.0, 0.0) < 9.0)
    }

    @Test fun retraceIndoorsWithoutGpsStillLeadsBackNearTheStart() {
        val r = rig(); val sim = MoveSim(r.c, gpsOn = false, compassBiasDeg = 4.0)
        sim.place(0.0, 0.0, 0.0)
        r.c.startTrip(sim.t)
        sim.walkLeg(0.0, 25.0); sim.walkLeg(90.0, 18.0); sim.walkLeg(180.0, 8.0)
        runBlocking { r.c.goHome(sim.t) }
        sim.follow(300)
        assertTrue(r.c.state.value.arrived)
        assertTrue("ended ${sim.distanceTo(0.0, 0.0)} m from the start", sim.distanceTo(0.0, 0.0) < 12.0)
    }

    @Test fun aPhoneInAPocketIsLearnedFromGpsWhileWalking() {
        val r = rig(); val sim = MoveSim(r.c, bodyOffsetDeg = 90.0)
        sim.place(0.0, 0.0, 0.0)
        r.c.startTrip(sim.t)
        sim.walkLeg(0.0, 100.0); sim.walkLeg(90.0, 60.0)
        // the recorded walk must have gone where the person really went (about 100 north, 60 east), not a quarter turn off
        r.c.stop(sim.t)
        val pts = com.smriti.app.move.RouteText.decode(r.store.routeList[0].points)
        val end = pts.last()
        assertTrue("recorded end $end", Geo.distance(end, Pt(60.0, 100.0)) < 25.0)
    }

    // ---- lost, with a saved home -------------------------------------------------------------------------------------
    @Test fun fromFarAwayTheGuideGoesStraightHome() {
        val r = rig(); val sim = MoveSim(r.c)
        home(r, sim)
        sim.place(260.0, 220.0, 45.0)                         // 340 m from home, facing away from it
        val reply = runBlocking { r.c.goHome(sim.t) }
        assertTrue(reply.startsWith("Okay"))
        assertEquals(Follow.BEARING, r.c.state.value.follow)
        val n = sim.follow(900)
        assertTrue("arrived after $n strides; ${sim.distanceTo(0.0, 0.0)} m from home", r.c.state.value.arrived)
        // arrival is within the saved radius (20 m) of the estimate, and the estimate is a few metres off
        assertTrue("ended ${sim.distanceTo(0.0, 0.0)} m from home", sim.distanceTo(0.0, 0.0) < 28.0)
        // the first thing said is to turn around, and the distance is announced on the way
        assertTrue("first said: ${r.out.said.first()}", r.out.said.first().let { it == "Turn around" || it.startsWith("Turn") })
        assertTrue("said: ${r.out.said}", r.out.said.any { it.contains("metres to go") })
    }

    @Test fun withoutGpsTheGuideWaitsForIt() {
        val r = rig(); val sim = MoveSim(r.c, gpsOn = false)
        home(r, sim)
        sim.place(100.0, 0.0, 90.0)
        runBlocking { r.c.goHome(sim.t) }
        assertEquals(MoveMode.GUIDING, r.c.state.value.mode)
        assertTrue(r.c.state.value.note.contains("GPS"))
        assertTrue(r.out.said.isEmpty())                      // it cannot say which way yet
    }

    @Test fun noHomeAndNoWalkSaysSo() {
        val r = rig()
        val reply = runBlocking { r.c.goHome(1000) }
        assertTrue(reply.contains("save your home"))
        assertEquals(MoveMode.IDLE, r.c.state.value.mode)
    }

    @Test fun withoutAHomeTheLastWalkIsFollowedBack() {
        val r = rig(); val sim = MoveSim(r.c)
        sim.place(0.0, 0.0, 0.0)
        r.c.startTrip(sim.t)
        sim.walkLeg(0.0, 80.0); sim.walkLeg(90.0, 50.0)
        r.c.stop(sim.t)
        val reply = runBlocking { r.c.goHome(sim.t) }
        assertTrue(reply, reply.startsWith("Okay"))
        sim.follow(500)
        assertTrue(r.c.state.value.arrived)
        assertTrue(sim.distanceTo(0.0, 0.0) < 16.0)
    }

    // ---- what is said and buzzed -------------------------------------------------------------------------------------
    @Test fun voiceAndBuzzCanBeSwitchedOffSeparately() {
        val quiet = rig(MovePrefs(voice = false, buzz = true)); val sim = MoveSim(quiet.c)
        home(quiet, sim); sim.place(150.0, 0.0, 90.0)
        runBlocking { quiet.c.goHome(sim.t) }
        sim.follow(40)
        assertTrue(quiet.out.said.isEmpty()); assertTrue(quiet.out.buzzes.isNotEmpty())

        val silent = rig(MovePrefs(voice = true, buzz = false)); val sim2 = MoveSim(silent.c)
        home(silent, sim2); sim2.place(150.0, 0.0, 90.0)
        runBlocking { silent.c.goHome(sim2.t) }
        sim2.follow(40)
        assertTrue(silent.out.said.isNotEmpty()); assertTrue(silent.out.buzzes.isEmpty())
        assertTrue(silent.c.state.value.phrase.isNotEmpty())     // the screen still shows it
    }

    @Test fun theCompassBuzzGetsSteadierAsTheAimImproves() {
        val r = rig(MovePrefs(compassBuzz = true)); val sim = MoveSim(r.c)
        home(r, sim); sim.place(200.0, 0.0, 270.0)               // facing home already
        runBlocking { r.c.goHome(sim.t) }
        sim.idle(3000)
        assertTrue("pulses ${r.out.pulses}", r.out.pulses.isNotEmpty())
        assertTrue(r.out.pulses.all { it.first >= 300 })         // the long steady buzz when facing the right way

        val r2 = rig(MovePrefs(compassBuzz = true)); val sim2 = MoveSim(r2.c)
        home(r2, sim2); sim2.place(200.0, 0.0, 330.0)            // 60 degrees off
        runBlocking { r2.c.goHome(sim2.t) }
        sim2.idle(4000)
        assertTrue(r2.out.pulses.isNotEmpty()); assertTrue(r2.out.pulses.all { it.first < 100 })
    }

    @Test fun hindiPhrasesAreSpokenWithAHindiVoice() {
        val r = rig(MovePrefs(language = "hi-IN")); val sim = MoveSim(r.c)
        home(r, sim); sim.place(150.0, 0.0, 90.0)
        runBlocking { r.c.goHome(sim.t) }
        sim.follow(10)
        assertTrue(r.out.said.isNotEmpty())
        assertTrue(r.out.tags.all { it == "hi-IN" })
        assertTrue(r.out.said.first().any { it.code > 0x900 })
    }

    @Test fun theUserIsToldToHoldThePhoneWhenThereIsNoDirection() {
        val r = rig()
        runBlocking { r.store.savePlace(MovePlaceEntity(name = "Home", kind = "home", lat = 12.9716, lon = 77.5946, radiusM = 20, wifi = "", createdAt = 1)) }
        r.c.onFix(1000, com.smriti.app.move.GpsFix(12.9726, 77.5946, 5.0))
        runBlocking { r.c.goHome(2000) }
        r.c.tick(2500)
        assertTrue(r.out.said.contains("Hold the phone flat in your hand"))
        assertTrue(r.c.state.value.note.contains("Hold the phone"))
    }

    // ---- arriving ---------------------------------------------------------------------------------------------------
    @Test fun theWifiAtHomeConfirmsArrivalWhenThePositionIsOff() {
        val r = rig(); val sim = MoveSim(r.c)
        val print = "a1,a2,a3,a4,a5,a6"
        val ll = sim.mapPoint(0.0, 0.0)
        runBlocking { r.store.savePlace(MovePlaceEntity(name = "Home", kind = "home", lat = ll.lat, lon = ll.lon, radiusM = 12, wifi = print, createdAt = 1)) }
        sim.place(40.0, 0.0, 270.0)
        runBlocking { r.c.goHome(sim.t) }
        r.c.onWifi("x1,x2")
        sim.idle(2000)
        assertEquals(MoveMode.GUIDING, r.c.state.value.mode)
        r.c.onWifi("a1,a2,a3,a9")                               // home's networks are heard, and home is within 60 m
        sim.idle(500)
        assertTrue(r.c.state.value.arrived)
    }

    @Test fun savingHereAveragesTheFixesAndNeedsGps() {
        val r = rig()
        val none = runBlocking { r.c.saveHere("Home", true, 1000) }
        assertTrue(none.contains("sky"))
        assertTrue(r.store.placeList.isEmpty())
        r.c.onWifi("a1,a2,a3")
        r.c.onFix(1000, com.smriti.app.move.GpsFix(12.97160, 77.59460, 6.0))
        r.c.onFix(2000, com.smriti.app.move.GpsFix(12.97162, 77.59462, 6.0))
        r.c.onFix(3000, com.smriti.app.move.GpsFix(12.97158, 77.59458, 30.0))      // a poor fix counts for little
        val said = runBlocking { r.c.saveHere("Home", true, 3500) }
        assertTrue(said, said.startsWith("Saved Home"))
        val p = r.store.placeList.single()
        assertEquals("home", p.kind); assertEquals(12.9716, p.lat, 0.00003); assertEquals(77.5946, p.lon, 0.00003)
        assertEquals("a1,a2,a3", p.wifi)
        assertTrue(p.radiusM in 12..40)
    }

    @Test fun aNewHomeReplacesTheOldOne() {
        val r = rig()
        r.c.onFix(1000, com.smriti.app.move.GpsFix(12.97, 77.59, 5.0))
        runBlocking { r.c.saveHere("Flat", true, 1500); r.c.saveHere("Mum's house", true, 1600) }
        assertEquals(1, r.store.placeList.count { it.kind == "home" })
        assertEquals("Mum's house", r.store.placeList.first { it.kind == "home" }.name)
    }

    // ---- voice requests ---------------------------------------------------------------------------------------------
    @Test fun voiceRequestsAreRoutedOrLeftAlone() {
        val r = rig()
        assertNull(runBlocking { r.c.handleIntent(MoveIntent.Stop, 1000) })                       // nothing to stop
        assertNull(runBlocking { r.c.handleIntent(MoveIntent.ToPlace("chemist"), 1000) })         // not a saved place: the normal assistant answers
        val started = runBlocking { r.c.handleIntent(MoveIntent.StartTrip, 2000) }
        assertTrue(started!!.startsWith("Okay"))
        assertEquals(MoveMode.RECORDING, r.c.state.value.mode)
        val stopped = runBlocking { r.c.handleIntent(MoveIntent.Stop, 3000) }
        assertNotNull(stopped)
        assertEquals(MoveMode.IDLE, r.c.state.value.mode)
    }

    @Test fun aSavedPlaceCanBeAskedForByName() {
        val r = rig(); val sim = MoveSim(r.c)
        val ll = sim.mapPoint(120.0, 0.0)
        runBlocking { r.store.savePlace(MovePlaceEntity(name = "Chemist", kind = "other", lat = ll.lat, lon = ll.lon, radiusM = 15, wifi = "", createdAt = 1)) }
        sim.place(0.0, 0.0, 90.0)
        val reply = runBlocking { r.c.handleIntent(MoveIntent.ToPlace("the chemist"), sim.t) }
        assertNotNull(reply)
        assertEquals("Chemist", r.c.state.value.title)
        sim.follow(300)
        assertTrue(r.c.state.value.arrived)
        assertTrue(sim.distanceTo(120.0, 0.0) < 20.0)
    }

    // ---- the pieces ---------------------------------------------------------------------------------------------------
    @Test fun distanceCallsAreMadeOnceEachAndNotForMarksAlreadyPassed() {
        val d = DistanceCalls()
        assertNull(d.next(350.0))                                  // starts inside the 500 band: not announced
        assertNull(d.next(320.0))
        assertEquals(300, d.next(299.0))
        assertNull(d.next(290.0))
        assertEquals(200, d.next(190.0))
        assertEquals(100, d.next(90.0))                            // jumped over nothing, but 100 is the next mark
        assertEquals(50, d.next(45.0)); assertEquals(20, d.next(10.0))
        assertNull(d.next(5.0))
        val near = DistanceCalls(); assertNull(near.next(8.0)); assertNull(near.next(4.0))
    }

    @Test fun straightGuideLeadsToAPointFromAnywhere() {
        val g = StraightGuide(Pt(0.0, 100.0), arriveRadiusM = 8.0)
        assertEquals(CueType.STRAIGHT, g.update(0.0, 0.0, 0.0).type)
        assertEquals(CueType.RIGHT, g.update(0.0, 0.0, 270.0).type)
        assertEquals(CueType.U_TURN, g.update(0.0, 0.0, 180.0).type)
        assertEquals(100.0, g.update(0.0, 0.0, 0.0).remainingM, 0.01)
        assertEquals(CueType.ARRIVED, g.update(2.0, 95.0, 0.0).type)
        assertEquals(CueType.ARRIVED, g.update(60.0, 0.0, 0.0).type)       // arrival is kept
    }

    @Test fun phrasesAreShortInBothLanguages() {
        for (s in Say.values().filter { it != Say.NONE }) {
            val a = Announcement("English", HapticId.TICK, s, 20)
            assertTrue(MovePhrases.text(a, "en-IN") == "English")
            val hi = MovePhrases.text(a, "hi-IN")
            assertTrue("$s: $hi", hi.isNotBlank() && hi != "English" && hi.length < 70)
        }
        assertEquals("About 200 metres to go", MovePhrases.toGo(200, "en").phrase)
        assertEquals("About 1 kilometre to go", MovePhrases.toGo(1000, "en").phrase)
        assertEquals("hi-IN", MovePhrases.tagFor("hi-IN")); assertEquals("en-IN", MovePhrases.tagFor("ta-IN"))
    }

    @Test fun wifiAddressesAreHashedNotStored() {
        val h = WifiPrint.hash("AA:BB:CC:DD:EE:FF")
        assertEquals(10, h.length)
        assertFalse(h.contains(":"))
        assertEquals(h, WifiPrint.hash("aa:bb:cc:dd:ee:ff"))
    }

    // ---- the heading ------------------------------------------------------------------------------------------------
    private fun filterRun(f: HeadingFilter, from: Long, seconds: Int, game: (Double) -> Double, compass: (Double) -> Double?, field: Double? = 45.0): Double? {
        var h: Double? = null
        var t = from
        repeat(seconds * 10) { t += 100; val s = (t - from) / 1000.0; h = f.update(t, game(s), compass(s), field) }
        return h
    }

    @Test fun theCompassGivesNorthToTheGyroscope() {
        val f = HeadingFilter()
        val h = filterRun(f, 0, 3, { 10.0 }, { 100.0 })
        assertEquals(100.0, h!!, 0.5)
    }

    @Test fun aTurnIsFollowedAtOnce() {
        val f = HeadingFilter()
        filterRun(f, 0, 3, { 10.0 }, { 100.0 })
        // 90 degrees to the right over two seconds: both sensors see it
        val h = filterRun(f, 3000, 2, { s -> 10.0 + 45.0 * s }, { s -> 100.0 + 45.0 * s })
        assertEquals(190.0, h!!, 1.0)
    }

    @Test fun aMagneticDisturbanceIsIgnoredAndForgotten() {
        val f = HeadingFilter()
        filterRun(f, 0, 6, { 10.0 }, { 100.0 })
        // a steel beam: the compass is 40 degrees out for four seconds while the gyroscope says nothing moved
        val during = filterRun(f, 6000, 4, { 10.0 }, { 140.0 })
        assertEquals(100.0, during!!, 4.0)
        val after = filterRun(f, 10000, 8, { 10.0 }, { 100.0 })
        assertEquals(100.0, after!!, 2.0)
    }

    @Test fun aFieldThatIsTooStrongMeansTheCompassIsNotTrusted() {
        val f = HeadingFilter()
        filterRun(f, 0, 6, { 10.0 }, { 100.0 })
        val h = filterRun(f, 6000, 4, { 10.0 }, { 150.0 }, field = 120.0)
        assertEquals(100.0, h!!, 3.0)
        assertTrue(f.compassDisturbed(10_000))
    }

    @Test fun gyroscopeDriftIsPulledBackByTheCompass() {
        val f = HeadingFilter()
        filterRun(f, 0, 3, { 10.0 }, { 100.0 })
        // two minutes of a gyroscope drifting a degree a minute
        val h = filterRun(f, 3000, 120, { s -> 10.0 + s / 60.0 }, { 100.0 })
        assertEquals(100.0, h!!, 1.0)
    }

    @Test fun howAPhoneIsCarriedIsLearnedFromTheGpsCourse() {
        val f = HeadingFilter()
        // in a pocket the phone points a quarter turn off the way the person walks (they walk at 40 degrees, the phone says 130)
        var t = 0L
        var h: Double? = null
        repeat(30) { t += 100; h = f.update(t, 10.0, 130.0, 45.0) }
        assertEquals(130.0, h!!, 1.0)                                        // before any GPS, the phone's own direction
        repeat(12) { t += 1000; f.update(t, 10.0, 130.0, 45.0); f.onGpsCourse(t, 40.0, 1.3, 10.0) }
        assertEquals(40.0, f.heading!!, 3.0)
        // the GPS is lost (indoors): it holds for a minute
        repeat(600) { t += 100; h = f.update(t, 10.0, 130.0, 45.0) }
        assertEquals(40.0, h!!, 4.0)
        // the phone is taken out: what was learned is forgotten
        f.poseChanged()
        t += 100; h = f.update(t, 10.0, 130.0, 45.0)
        assertEquals(130.0, h!!, 1.0)
    }

    @Test fun aGpsCourseIsNotUsedInATurnOrAtSlowSpeed() {
        val f = HeadingFilter()
        var t = 0L
        repeat(30) { t += 100; f.update(t, 10.0, 100.0, 45.0) }
        val before = f.bodyOffsetDeg
        f.onGpsCourse(t, 200.0, 0.3, 10.0)                                  // standing still: the course means nothing
        assertEquals(before, f.bodyOffsetDeg, 1e-9)
        // turning 80 degrees in two seconds: the course lags behind, so it is ignored
        repeat(20) { t += 100; f.update(t, 10.0 + 4.0 * (it + 1), 100.0 + 4.0 * (it + 1), 45.0) }
        f.onGpsCourse(t, 100.0, 1.4, 10.0)
        assertEquals(before, f.bodyOffsetDeg, 1e-9)
        f.onGpsCourse(t, 100.0, 1.4, 60.0)                                  // a course that says it is unsure
        assertEquals(before, f.bodyOffsetDeg, 1e-9)
    }

    @Test fun theHeadingIsUnknownUntilThereIsANorth() {
        val f = HeadingFilter()
        assertNull(f.update(100, 30.0, null, null))
        assertNull(f.heading)
        assertEquals(35.0, f.update(200, 30.0, 35.0, 45.0)!!, 0.5)
    }
}
