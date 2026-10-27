package com.smriti.app.live

import kotlin.math.max

/**
 * The "Test microphone" button in Settings: the user speaks for a few seconds and Smriti says what it heard, how loud the voice and the
 * room were, and what to change. Plain Kotlin on the recorded samples (and the same voice detector Live uses), so it is unit-tested.
 */
object MicTest {
    class Report(
        val seconds: Double,
        /** seconds of the recording the detector counted as speech */
        val speechSeconds: Double,
        /** average loudness of the speech frames (rms, 16-bit), or the loudest frame when no speech was found */
        val voiceLoudness: Int,
        val roomNoise: Int,
        /** the level a voice must pass to count as speech in this room */
        val mustPass: Int,
        /** the part of the recording with the speech (with a little before and after), or null when there was none */
        val clip: ShortArray?
    )

    fun analyse(pcm: ShortArray): Report {
        val frames = pcm.size / AudioPrep.FRAME
        val va = VoiceActivity()
        var first = -1
        var lastLoud = -1
        var speechFrames = 0
        var loudSum = 0.0
        var peak = 0.0
        for (f in 0 until frames) {
            val rms = AudioPrep.rms(pcm.copyOfRange(f * AudioPrep.FRAME, (f + 1) * AudioPrep.FRAME))
            peak = max(peak, rms)
            val ev = va.feed(rms, false)
            if (ev == VoiceActivity.Event.START && first < 0) first = f
            if (va.inSpeech || ev == VoiceActivity.Event.START || ev == VoiceActivity.Event.END) {
                if (rms > va.threshold) { speechFrames++; loudSum += rms; lastLoud = f }
            }
        }
        val clip = if (first >= 0 && lastLoud >= first) {
            val from = ((first - 20).coerceAtLeast(0)) * AudioPrep.FRAME
            val to = ((lastLoud + 12).coerceAtMost(frames)) * AudioPrep.FRAME
            pcm.copyOfRange(from, to)
        } else null
        return Report(
            seconds = frames * 0.02, speechSeconds = speechFrames * 0.02,
            voiceLoudness = if (speechFrames > 0) (loudSum / speechFrames).toInt() else peak.toInt(),
            roomNoise = va.floor.toInt(), mustPass = va.threshold.toInt(), clip = clip
        )
    }

    /** the plain-words result shown to the user; [transcript] is what Gemma-4 made of the clip, or null when it made nothing */
    fun message(r: Report, transcript: String?): String {
        val noisy = r.roomNoise > 700
        return when {
            r.clip == null || r.speechSeconds < 0.4 ->
                "I did not hear you. The loudest moment was ${r.voiceLoudness} and a voice needs to pass ${r.mustPass} here. " +
                    "Hold the phone about a hand's width from your mouth and speak a little louder."
            transcript.isNullOrBlank() ->
                "I heard you for ${"%.1f".format(r.speechSeconds)} s (voice ${r.voiceLoudness}, room ${r.roomNoise}) but could not make out the words. " +
                    (if (noisy) "The room is loud: move somewhere quieter or speak closer to the phone." else "Try a shorter sentence, a little slower, closer to the phone.")
            else ->
                "Heard: \"${transcript.trim()}\" (voice ${r.voiceLoudness}, room ${r.roomNoise})." +
                    (if (noisy) " The room is loud, so recognition may miss words." else "")
        }
    }
}
