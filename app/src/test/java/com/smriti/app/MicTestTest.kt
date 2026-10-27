package com.smriti.app

import com.smriti.app.live.MicTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class MicTestTest {
    private val rnd = Random(3)

    /** [seconds] of room noise at [noise] rms with a burst of speech-like sound of [voice] rms between [from] and [to] seconds */
    private fun recording(seconds: Double, noise: Double, voice: Double = 0.0, from: Double = 0.0, to: Double = 0.0): ShortArray {
        val n = (seconds * 16_000).toInt()
        return ShortArray(n) { i ->
            val t = i / 16_000.0
            val noiseV = rnd.nextGaussian() * noise
            val voiceV = if (voice > 0 && t in from..to) voice * 1.4 * sin(2 * PI * 180 * t) * (0.55 + 0.45 * sin(2 * PI * 3.5 * t)) else 0.0
            (noiseV + voiceV).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    @Test fun findsTheSpeechAndCutsAClipAroundIt() {
        val r = MicTest.analyse(recording(6.0, 40.0, voice = 3000.0, from = 2.0, to = 4.0))
        assertNotNull(r.clip)
        assertTrue("speech ${r.speechSeconds}", r.speechSeconds in 1.0..2.6)
        assertTrue("voice ${r.voiceLoudness}", r.voiceLoudness > 1500)
        assertTrue("clip ${r.clip!!.size / 16000.0}", r.clip!!.size / 16_000.0 in 1.8..3.6)
    }

    @Test fun anEmptyRoomHasNoClip() {
        val r = MicTest.analyse(recording(6.0, 40.0))
        assertNull(r.clip)
        assertTrue(MicTest.message(r, null).startsWith("I did not hear you"))
    }

    @Test fun aQuietVoiceIsToldToSpeakCloser() {
        val r = MicTest.analyse(recording(6.0, 40.0, voice = 300.0, from = 2.0, to = 4.0))     // far below the 900 needed
        assertNull(r.clip)
        val m = MicTest.message(r, null)
        assertTrue(m, m.contains("a voice needs to pass") && m.contains("speak a little louder"))
    }

    @Test fun heardWordsAreShown() {
        val r = MicTest.analyse(recording(6.0, 40.0, voice = 3000.0, from = 1.5, to = 3.5))
        val m = MicTest.message(r, "where is my laptop")
        assertTrue(m, m.startsWith("Heard: \"where is my laptop\""))
    }

    @Test fun soundWithoutWordsAsksForAShorterSentence() {
        val r = MicTest.analyse(recording(6.0, 40.0, voice = 3000.0, from = 1.5, to = 3.5))
        val m = MicTest.message(r, "  ")
        assertTrue(m, m.contains("could not make out the words") && m.contains("shorter sentence"))
    }

    @Test fun aLoudRoomIsNamedAsTheLikelyCause() {
        val r = MicTest.analyse(recording(8.0, 1600.0, voice = 6000.0, from = 3.0, to = 5.0))
        assertTrue("room ${r.roomNoise}", r.roomNoise > 700)
        assertTrue(MicTest.message(r, "").contains("room is loud"))
        assertTrue(MicTest.message(r, "hello there").contains("room is loud"))
    }

    @Test fun secondsAreReported() {
        assertEquals(6.0, MicTest.analyse(recording(6.0, 40.0)).seconds, 0.05)
    }
}
