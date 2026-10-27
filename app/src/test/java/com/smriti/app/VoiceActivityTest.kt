package com.smriti.app

import com.smriti.app.live.AudioPrep
import com.smriti.app.live.VoiceActivity
import com.smriti.app.live.VoiceActivity.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class VoiceActivityTest {
    private val rnd = Random(7)

    /** rms of a frame: [level] with some random wobble, like real room noise */
    private fun noise(level: Double, wobble: Double = 0.25) = level * (1.0 + wobble * (rnd.nextDouble() * 2 - 1))

    /** speech-like loudness: syllables, so the level moves a lot and dips between words */
    private fun speech(level: Double, i: Int) = level * (0.55 + 0.45 * sin(i * 2 * PI / 9.0).coerceAtLeast(-0.3)) * (0.9 + 0.2 * rnd.nextDouble())

    private fun run(va: VoiceActivity, frames: List<Double>, strict: Boolean = false): List<Pair<Int, Event>> =
        frames.mapIndexedNotNull { i, r -> va.feed(r, strict).takeIf { it != Event.NONE }?.let { i to it } }

    @Test fun quietRoomThenASentence() {
        val va = VoiceActivity()
        val frames = List(60) { noise(100.0) } + List(100) { speech(3500.0, it) } + List(80) { noise(100.0) }
        val ev = run(va, frames)
        assertEquals(listOf(Event.START, Event.END), ev.map { it.second })
        assertTrue("starts about when the speech starts", ev[0].first in 60..75)
        assertTrue("ends about 0.9 s after it stops", ev[1].first in 160..215)
    }

    @Test fun aRoomThatIsLoudFromTheStartTriggersNothing() {
        val va = VoiceActivity()
        val ev = run(va, List(600) { noise(1500.0, 0.3) })
        assertTrue("no speech in steady babble: $ev", ev.isEmpty())
    }

    @Test fun speechOverSteadyBabbleIsDetected() {
        val va = VoiceActivity()
        val frames = List(250) { noise(1200.0, 0.3) } + List(100) { speech(5200.0, it) + noise(1200.0, 0.3) * 0.4 } + List(100) { noise(1200.0, 0.3) }
        val ev = run(va, frames)
        assertTrue("start in the babble: $ev", ev.any { it.second == Event.START && it.first in 250..270 })
        assertTrue(ev.any { it.second == Event.END })
    }

    /** the failure seen in a crowded hall: quiet when Live starts, then the room fills with talk */
    @Test fun theRoomGettingLouderDoesNotMeanSpeechForEver() {
        val va = VoiceActivity()
        val frames = List(100) { noise(100.0) } + List(1500) { noise(1600.0, 0.3) }         // 30 s of babble after a quiet start
        val ev = run(va, frames)
        val starts = ev.count { it.second == Event.START }
        assertTrue("at most one or two false starts before the floor catches up (was $starts): $ev", starts <= 2)
        assertTrue("the false start ends as noise, not as a 15 s clip: $ev", ev.any { it.second == Event.NOISE } && ev.none { it.second == Event.MAX })
        // and afterwards the babble no longer counts as speech
        val after = run(va, List(500) { noise(1600.0, 0.3) })
        assertTrue("quiet again: $after", after.isEmpty())
    }

    @Test fun speechStillWorksRightAfterTheRoomGotLouder() {
        val va = VoiceActivity()
        run(va, List(100) { noise(100.0) } + List(700) { noise(1600.0, 0.3) })        // let it adapt
        val ev = run(va, List(100) { speech(6500.0, it) } + List(80) { noise(1600.0, 0.3) })
        assertTrue("$ev", ev.any { it.second == Event.START } && ev.any { it.second == Event.END })
    }

    @Test fun theFloorFollowsTheRoomBackDown() {
        val va = VoiceActivity()
        run(va, List(400) { noise(1600.0, 0.3) })
        val loudFloor = va.floor
        run(va, List(300) { noise(80.0) })
        assertTrue("floor $loudFloor then ${va.floor}", va.floor < loudFloor / 5)
    }

    @Test fun aShortKnockIsNotSpeech() {
        val va = VoiceActivity()
        val ev = run(va, List(60) { noise(100.0) } + List(3) { 4000.0 } + List(60) { noise(100.0) })
        assertTrue(ev.isEmpty())
    }

    @Test fun strictModeNeedsAClearerVoice() {
        val frames = List(60) { noise(100.0) } + List(100) { speech(1800.0, it) } + List(80) { noise(100.0) }
        assertTrue("normal mode hears it", run(VoiceActivity(), frames).any { it.second == Event.START })
        assertTrue("strict mode (Smriti is talking) does not", run(VoiceActivity(), frames, strict = true).none { it.second == Event.START })
    }

    @Test fun veryLongContinuousSpeechIsCutAtTheMaximum() {
        val va = VoiceActivity(noiseAfterFrames = 10_000)               // disable the steadiness check for this test
        val ev = run(va, List(60) { noise(100.0) } + List(900) { speech(4000.0, it) })
        assertTrue(ev.any { it.second == Event.MAX })
    }

    // ---- audio clean-up for the retry ----------------------------------------------------------------------------
    @Test fun loudestWindowKeepsTheVoice() {
        val quiet = ShortArray(16_000 * 3) { (rnd.nextGaussian() * 80).toInt().toShort() }
        val voice = ShortArray(16_000 * 2) { (sin(it * 0.1) * 6000).toInt().toShort() }
        val pcm = quiet + voice + quiet                                  // 8 s, the voice in the middle
        val win = AudioPrep.loudestWindow(pcm, 3000)
        assertEquals(3000 * 16, win.size)
        assertTrue("the window is mostly voice", AudioPrep.rms(win) > 3000)
    }

    @Test fun shortClipsAreKeptWhole() {
        val pcm = ShortArray(16_000) { 1000 }
        assertEquals(pcm.size, AudioPrep.loudestWindow(pcm, 6000).size)
    }

    @Test fun normaliseRaisesQuietSpeechButNotSilence() {
        val quietVoice = ShortArray(16_000) { (sin(it * 0.1) * 1200).toInt().toShort() }
        val loud = AudioPrep.normalise(quietVoice)
        assertTrue(AudioPrep.rms(loud) > 3 * AudioPrep.rms(quietVoice))
        assertTrue(loud.maxOf { it.toInt() } <= 32767)
        val silence = ShortArray(16_000) { 3 }
        assertEquals(AudioPrep.rms(silence), AudioPrep.rms(AudioPrep.normalise(silence)), 1e-9)
    }

    @Test fun wavRoundTrip() {
        val pcm = ShortArray(3200) { (it % 100).toShort() }
        val back = AudioPrep.samples(AudioPrep.toWav(pcm))!!
        assertTrue(pcm.contentEquals(back))
        assertFalse(AudioPrep.samples(ByteArray(10)) != null)
    }
}
