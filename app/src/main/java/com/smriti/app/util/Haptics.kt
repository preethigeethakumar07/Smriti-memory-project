package com.smriti.app.util

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Haptic cues for the whole app. On/off and strength come from Settings > Haptics (light or strong).
 *
 *   click()      any button, tab, switch, chip
 *   tap()        mic or camera start
 *   heard()      the phone caught what you said
 *   speakStart() Smriti starts to talk (a soft tick, so you can feel the answer begin)
 *   done()       an answer is ready / a session ended
 *   confirm()    something was saved or finished
 *   warn()       it did not understand, or something failed
 *   wake()       "Hey Smriti" was heard
 *   alert()      reminders
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)

    @Volatile var enabled = true
    @Volatile var strength = 1

    private val strong get() = strength >= 2

    fun click() = predefined(if (strong) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK)
    fun heard() = predefined(VibrationEffect.EFFECT_TICK)
    fun speakStart() = predefined(VibrationEffect.EFFECT_TICK)
    fun tap() = predefined(VibrationEffect.EFFECT_CLICK)
    fun done() = predefined(if (strong) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_CLICK)

    fun confirm() = pattern(longArrayOf(0, 25, 60, 25), amp(), amp())
    fun warn() = pattern(longArrayOf(0, 70, 80, 35), amp(), amp())
    fun wake() = pattern(longArrayOf(0, 20, 50, 30, 50, 45), amp(), amp(), amp())
    fun alert() = pattern(longArrayOf(0, 60, 70, 60), amp(), amp())

    // ---- the walk guide (move/): one feel for each instruction, so it can be followed with the phone in a pocket.
    // These have their own switch in the Walk guide and work even when Settings > Haptics is off.

    /** left: two short buzzes; right: one long buzz; turn around: three long ones; arrived: a rising pattern; keep going: a light tick */
    fun walkCue(id: com.smriti.app.move.HapticId) {
        val a = walkAmp()
        when (id) {
            com.smriti.app.move.HapticId.TICK -> raw(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            com.smriti.app.move.HapticId.LEFT -> rawWave(longArrayOf(0, 70, 110, 70), intArrayOf(0, a, 0, a))
            com.smriti.app.move.HapticId.RIGHT -> rawWave(longArrayOf(0, 320), intArrayOf(0, a))
            com.smriti.app.move.HapticId.UTURN -> rawWave(longArrayOf(0, 260, 120, 260, 120, 260), intArrayOf(0, a, 0, a, 0, a))
            com.smriti.app.move.HapticId.ARRIVE -> rawWave(longArrayOf(0, 50, 90, 90, 90, 220), intArrayOf(0, a / 2, 0, (a * 3) / 4, 0, a))
            com.smriti.app.move.HapticId.WARN -> rawWave(longArrayOf(0, 90, 90, 90), intArrayOf(0, a, 0, a))
        }
    }

    /** one pulse of the steady compass buzz */
    fun walkPulse(onMs: Int, amplitude: Int) {
        val a = if (strong) amplitude else (amplitude * 3 / 4).coerceAtLeast(60)
        raw(VibrationEffect.createOneShot(onMs.toLong(), a.coerceIn(1, 255)))
    }

    private fun walkAmp() = if (strong) 255 else 190

    private fun raw(effect: VibrationEffect) { runCatching { vibrator?.vibrate(effect) } }

    private fun rawWave(timings: LongArray, amps: IntArray) { runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(timings, amps, -1)) } }

    private fun amp() = if (strong) 255 else 110

    private fun predefined(effect: Int) {
        if (!enabled) return
        runCatching { vibrator?.vibrate(VibrationEffect.createPredefined(effect)) }
    }

    /** [amps] gives one amplitude per ON segment; the OFF segments get 0. */
    private fun pattern(timings: LongArray, vararg amps: Int) {
        if (!enabled) return
        val full = IntArray(timings.size)
        var k = 0
        for (i in timings.indices) full[i] = if (i % 2 == 1) amps.getOrElse(k++) { amp() } else 0
        runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(timings, full, -1)) }
    }
}

/** Lets any composable buzz without being handed the container. Provided once in AppRoot. */
val LocalHaptics = staticCompositionLocalOf<Haptics?> { null }
