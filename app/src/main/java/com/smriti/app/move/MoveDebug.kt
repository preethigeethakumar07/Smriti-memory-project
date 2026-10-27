package com.smriti.app.move

import com.smriti.app.memory.MovePlaceEntity
import com.smriti.app.memory.MoveRouteEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** an in-memory store, for simulated walks that must not touch the real saved places */
class MemoryMoveStore : MoveStore {
    val placeList = ArrayList<MovePlaceEntity>()
    val routeList = ArrayList<MoveRouteEntity>()
    private var nextId = 1L
    override suspend fun home() = placeList.lastOrNull { it.kind == "home" }
    override suspend fun places() = placeList.toList()
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

/** remembers what the guide said instead of saying it */
class RecordingOutput : MoveOutput {
    val said = ArrayList<String>()
    var buzzes = 0
    var pulses = 0
    override fun speak(text: String, languageTag: String) { said += text }
    override fun buzz(h: HapticId) { buzzes++ }
    override fun pulse(onMs: Int, amplitude: Int) { pulses++ }
}

/** Simulated walks for the debug command `moveSim`: the whole walk guide, run on the phone itself, with nobody walking. */
object MoveDebug {

    suspend fun simulate(scenario: String): String {
        val all = listOf("retrace", "indoor", "bearing", "pocket")
        val which = if (scenario == "all") all else listOf(scenario)
        return which.map { run(it) }.joinToString("\n")
    }

    private suspend fun run(scenario: String): String {
        val store = MemoryMoveStore()
        val out = RecordingOutput()
        val c = MoveController(store, out, { MovePrefs() }, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val started = System.nanoTime()
        val goal: Pt
        val sim: MoveSim
        when (scenario) {
            "retrace", "pocket", "indoor" -> {
                sim = MoveSim(c, gpsOn = scenario != "indoor", bodyOffsetDeg = if (scenario == "pocket") 90.0 else 0.0, compassBiasDeg = if (scenario == "indoor") 4.0 else 0.0)
                sim.place(0.0, 0.0, 0.0)
                c.startTrip(sim.t)
                if (scenario == "indoor") { sim.walkLeg(0.0, 25.0); sim.walkLeg(90.0, 18.0); sim.walkLeg(180.0, 8.0) }
                else { sim.walkLeg(0.0, 120.0); sim.walkLeg(90.0, 80.0); sim.walkLeg(0.0, 40.0); sim.walkLeg(300.0, 30.0) }
                goal = Pt(0.0, 0.0)
                c.goHome(sim.t)
            }
            "bearing" -> {
                sim = MoveSim(c)
                val ll = sim.mapPoint(0.0, 0.0)
                store.savePlace(MovePlaceEntity(name = "Home", kind = "home", lat = ll.lat, lon = ll.lon, radiusM = 20, wifi = "", createdAt = 1))
                sim.place(260.0, 220.0, 45.0)
                goal = Pt(0.0, 0.0)
                c.goHome(sim.t)
            }
            else -> return "unknown scenario '$scenario' (retrace, indoor, bearing, pocket, all)"
        }
        val strides = sim.follow(900)
        val s = c.state.value
        val end = sim.distanceTo(goal.x, goal.y)
        val ms = (System.nanoTime() - started) / 1_000_000
        val first = out.said.take(6).joinToString(" / ")
        return "$scenario: arrived=${s.arrived} strides=$strides endDistance=${"%.1f".format(end)}m said=${out.said.size} buzzes=${out.buzzes} took=${ms}ms first=[$first]"
    }
}
