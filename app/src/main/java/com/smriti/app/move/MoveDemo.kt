package com.smriti.app.move

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * A demo walk, for showing how the walk guide works without going anywhere (a pitch, training a caregiver, trying it indoors): a simulated
 * person (see [MoveSim]) is fed to the real walk guide, which goes through everything it does on a real walk, with its voice, its buzz and
 * the path map: it remembers a walk 30 m north and 25 m east, then is asked to take the person home and leads them back with its cues.
 * The real sensors are muted while it runs, the screen says it is a demo, and it can be stopped at any time. Nothing is saved as a walk.
 */
class MoveDemo(
    private val controller: MoveController,
    private val sensors: MoveSensors?,
    private val scope: CoroutineScope,
    private val clock: () -> Long
) {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    @Volatile private var stopRequested = false
    private var job: Job? = null

    /** starts the demo at [speed] times real time; returns what to tell the user */
    fun start(speed: Int = 2): String {
        if (_running.value) return "The demo is already running."
        if (controller.isActive) return "Stop the current walk first, then try the demo."
        stopRequested = false
        _running.value = true
        sensors?.muted = true
        controller.saving = false
        job = scope.launch(Dispatchers.Default) {
            try {
                val sim = MoveSim(
                    controller, seed = 11, startMs = clock(),
                    pace = { ms -> if (stopRequested) throw CancellationException("demo stopped"); Thread.sleep((ms / speed).toLong()) }
                )
                script(controller, sim)
            } catch (_: CancellationException) {
                // stopped by the user
            } finally {
                controller.stop(clock())
                controller.saving = true
                sensors?.muted = false
                _running.value = false
            }
        }
        return "Demo walk started. Nobody has to move: Smriti pretends to walk out and then leads itself back."
    }

    fun stop() { stopRequested = true }

    companion object {
        /** out 30 m north and 25 m east (remembered as it goes), then "take me home" and follow the cues back to the start */
        fun script(c: MoveController, sim: MoveSim) {
            sim.place(0.0, 0.0, 0.0)
            c.startTrip(sim.t)
            sim.walkLeg(0.0, 30.0)
            sim.walkLeg(90.0, 25.0)
            runBlocking { c.goHome(sim.t) }
            sim.follow(300)
        }
    }
}
