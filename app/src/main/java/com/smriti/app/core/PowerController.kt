package com.smriti.app.core

import android.os.SystemClock
import com.smriti.app.live.LiveSession
import com.smriti.app.source.PhoneCameraSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Applies [PowerPolicy] while the app runs: every couple of seconds it looks at where the user is (Home, Live voice, Live camera),
 * how warm the phone is (the OS status and its 10 second forecast) and whether the lens is covered, and sets the camera frame rate,
 * the analysis tick and the gap between remembered moments.
 */
class PowerController(
    scope: CoroutineScope,
    private val engine: SmritiEngine,
    private val camera: PhoneCameraSource,
    private val live: LiveSession
) {
    private val _plan = MutableStateFlow(PowerPolicy.decide(PowerPolicy.Where.HOME, 0))
    /** what the app is doing now; [PowerPolicy.Plan.label] is the line to show when the phone is warm */
    val plan: StateFlow<PowerPolicy.Plan> = _plan.asStateFlow()
    private var lastFps = -1

    init {
        scope.launch {
            var headroom: Float? = null
            var headroomAt = 0L
            while (true) {
                val now = SystemClock.elapsedRealtime()
                if (now - headroomAt > 5_000) { headroom = engine.governor.headroom(); headroomAt = now }
                val l = live.state.value
                val where = when {
                    l.active && l.cameraOn -> PowerPolicy.Where.LIVE_CAMERA
                    l.active -> PowerPolicy.Where.LIVE_VOICE
                    else -> PowerPolicy.Where.HOME
                }
                val level = PowerPolicy.level(engine.governor.status, headroom)
                val p = PowerPolicy.decide(where, level, engine.visual.blankStreak)
                _plan.value = p
                if (p.cameraFps != lastFps) { lastFps = p.cameraFps; camera.setFrameRate(p.cameraFps) }
                engine.tickMs = p.tickMs
                engine.ambientGapScale = p.ambientGapScale
                delay(if (where == PowerPolicy.Where.HOME) 3_000 else 1_500)
            }
        }
    }
}
