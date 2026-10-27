package com.smriti.app.live

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Clean-up for the one retry of a clip Gemma-4 could not read: the loudest few seconds (the person talking to the phone is closer
 * than the room) at a healthy volume. Plain Kotlin on 16 kHz mono 16-bit WAV files, so it is unit-tested.
 */
object AudioPrep {
    const val FRAME = 320            // 20 ms at 16 kHz

    /** the samples of a 16-bit mono WAV file made by [VoiceCapture.wav] (a 44 byte header), or null when it is not one */
    fun samples(wav: ByteArray): ShortArray? {
        if (wav.size < 46 || wav[0] != 'R'.code.toByte() || wav[8] != 'W'.code.toByte()) return null
        val n = (wav.size - 44) / 2
        val out = ShortArray(n)
        ByteBuffer.wrap(wav, 44, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }

    /** the loudest window of at most [maxMs] (a clip shorter than that is kept whole) */
    fun loudestWindow(pcm: ShortArray, maxMs: Int): ShortArray {
        val win = maxMs * 16
        if (pcm.size <= win) return pcm
        val frames = pcm.size / FRAME
        val per = DoubleArray(frames) { f -> var s = 0.0; for (i in f * FRAME until (f + 1) * FRAME) s += pcm[i].toDouble() * pcm[i]; s }
        val wf = win / FRAME
        var best = 0; var bestSum = -1.0; var cur = 0.0
        for (f in 0 until frames) {
            cur += per[f]
            if (f >= wf) cur -= per[f - wf]
            if (f >= wf - 1 && cur > bestSum) { bestSum = cur; best = f - wf + 1 }
        }
        return pcm.copyOfRange(best * FRAME, best * FRAME + win)
    }

    /** scales so the loudest sample reaches about 80 % of full scale (never more than 20 times louder, so silence is not blown up) */
    fun normalise(pcm: ShortArray): ShortArray {
        var peak = 0
        for (x in pcm) peak = maxOf(peak, abs(x.toInt()))
        if (peak < 200) return pcm
        val gain = (0.8 * Short.MAX_VALUE / peak).coerceAtMost(20.0)
        return ShortArray(pcm.size) { (pcm[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }
    }

    fun rms(pcm: ShortArray): Double {
        if (pcm.isEmpty()) return 0.0
        var s = 0.0
        for (x in pcm) s += x.toDouble() * x
        return sqrt(s / pcm.size)
    }

    fun toWav(pcm: ShortArray): ByteArray {
        val b = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.asShortBuffer().put(pcm)
        return VoiceCapture.wav(b.array())
    }
}
