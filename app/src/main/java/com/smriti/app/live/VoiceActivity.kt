package com.smriti.app.live

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Decides, one 20 ms frame at a time, whether someone is speaking to the phone. Plain Kotlin, so it is unit-tested against simulated rooms.
 *
 * The old detector measured the room noise once, in the first 300 ms, and never raised it. When people started talking in the room
 * afterwards, the babble stayed "louder than the floor" for ever: the detector believed someone was speaking all the time and handed
 * Gemma-4 15 second clips of chatter. This one follows the room:
 *  - the noise floor is the 25th percentile of the last 3 seconds of frames that were not speech, so it rises and falls with the room;
 *  - an utterance that goes on for 3 seconds at a steady loudness (low variation, never a pause) is noise, not speech: it is dropped
 *    and the floor is moved up to it, so the very next frames are quiet again;
 *  - speech must be [ratio] times the floor (and at least [minAbs]), and [startFrames] frames in a row.
 */
class VoiceActivity(
    private val warmFrames: Int = 15,
    private val startFrames: Int = 5,
    private val endQuietFrames: Int = 45,
    private val maxFrames: Int = 750,
    private val minAbs: Double = 900.0,
    private val ratio: Double = 2.6,
    private val strictMinAbs: Double = 2200.0,
    private val strictRatio: Double = 4.5,
    private val floorWindow: Int = 150,
    private val noiseAfterFrames: Int = 150
) {
    enum class Event { NONE, START, END, MAX, NOISE }

    /** the current estimate of the room noise (rms of 16-bit samples) */
    var floor = 0.0; private set
    var threshold = 0.0; private set
    var inSpeech = false; private set

    /** counted while speech lasts, and still valid right after an END / MAX / NOISE event */
    var speechFrames = 0; private set
    var quietFrames = 0; private set
    var totalFrames = 0; private set

    private var warm = 0
    private var loudRun = 0
    private val ring = DoubleArray(floorWindow)
    private var ringCount = 0
    private var ringPos = 0
    private var sinceFloor = 0
    private val speechRing = DoubleArray(50)
    private var speechCount = 0
    private var speechPos = 0

    fun feed(rms: Double, strict: Boolean = false): Event {
        if (warm < warmFrames) { addNoise(rms); warm++; recomputeFloor(); return Event.NONE }
        threshold = max(if (strict) strictMinAbs else minAbs, floor * if (strict) strictRatio else ratio)
        val loud = rms > threshold

        if (!inSpeech) {
            addNoise(rms)                                   // every frame outside speech tells how loud the room is
            if (++sinceFloor >= 5) { sinceFloor = 0; recomputeFloor() }
            if (loud) loudRun++ else loudRun = 0
            if (loudRun >= startFrames) {
                inSpeech = true; quietFrames = 0; speechFrames = loudRun; totalFrames = 0; speechCount = 0
                return Event.START
            }
            return Event.NONE
        }

        totalFrames++
        if (loud) { quietFrames = 0; speechFrames++ } else quietFrames++
        speechRing[speechPos] = rms; speechPos = (speechPos + 1) % speechRing.size; if (speechCount < speechRing.size) speechCount++

        if (quietFrames >= endQuietFrames) return finish(Event.END)
        if (totalFrames >= noiseAfterFrames && steadyNoise()) { adoptNoise(); return finish(Event.NOISE) }
        if (totalFrames >= maxFrames) { adoptNoise(); return finish(Event.MAX) }
        return Event.NONE
    }

    private fun finish(e: Event): Event { inSpeech = false; loudRun = 0; return e }

    /** a steady loudness for the last second, with no pause for 3 seconds, is a room, not a person */
    private fun steadyNoise(): Boolean {
        if (speechCount < speechRing.size || quietFrames > 0) return false
        var sum = 0.0
        for (x in speechRing) sum += x
        val mean = sum / speechRing.size
        var sq = 0.0
        for (x in speechRing) sq += (x - mean) * (x - mean)
        val cv = sqrt(sq / speechRing.size) / max(mean, 1.0)
        return cv < 0.25
    }

    /** the room is now this loud: move the floor up to it, so the next frames do not count as speech */
    private fun adoptNoise() {
        if (speechCount == 0) return
        var sum = 0.0
        for (i in 0 until speechCount) sum += speechRing[i]
        val level = sum / speechCount * 0.9
        java.util.Arrays.fill(ring, level)
        ringCount = ring.size
        floor = level
    }

    private fun addNoise(rms: Double) {
        ring[ringPos] = rms; ringPos = (ringPos + 1) % ring.size; if (ringCount < ring.size) ringCount++
    }

    private fun recomputeFloor() {
        if (ringCount == 0) return
        val a = ring.copyOf(ringCount)
        a.sort()
        floor = a[((ringCount - 1) * 0.25).toInt()]
    }
}
