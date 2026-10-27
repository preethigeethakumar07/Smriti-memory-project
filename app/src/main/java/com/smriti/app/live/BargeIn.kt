package com.smriti.app.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Lets the user interrupt. While Smriti is thinking or speaking, this listens for the user starting to talk and returns,
 * so the caller can stop the voice and go back to listening. It does not understand words; it only notices sustained speech
 * over the room noise. The "voice communication" microphone is used because Android applies echo cancellation to it, which
 * keeps Smriti's own voice from interrupting itself.
 */
class BargeIn(private val context: Context) {

    /** loudest recent level, for the debug log and tuning */
    @Volatile var peak = 0
        private set

    /** Suspends until speech is heard (true), or the coroutine is cancelled / the microphone is unavailable (false). */
    suspend fun awaitSpeech(speakerOn: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@withContext false
        val rate = 16_000
        val frame = rate / 50                                   // 20 ms
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return@withContext false
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, frame * 8))
        } catch (_: Throwable) { return@withContext false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@withContext false }
        val fx = ArrayList<android.media.audiofx.AudioEffect>(2)
        runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.let { it.enabled = true; fx.add(it) } }
        runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.let { it.enabled = true; fx.add(it) } }
        try {
            rec.startRecording()
            val buf = ShortArray(frame)
            var floor = 0.0
            var loud = 0
            var n = 0
            // speaking over Smriti's own voice needs a clearer signal than speaking into a quiet room
            val need = if (speakerOn) 9 else 6                  // consecutive 20 ms frames (about 180 ms or 120 ms)
            val factor = if (speakerOn) 4.5 else 3.5
            val minLevel = if (speakerOn) 2200.0 else 1300.0
            while (isActive) {
                val r = rec.read(buf, 0, frame)
                if (r <= 0) continue
                var s = 0.0
                for (i in 0 until r) s += buf[i].toDouble() * buf[i]
                val rms = sqrt(s / r)
                if (rms > peak) peak = rms.toInt()
                n++
                if (n <= 15) { floor = if (floor == 0.0) rms else floor * 0.8 + rms * 0.2; continue }     // first 300 ms set the noise floor
                if (rms > max(minLevel, floor * factor)) { if (++loud >= need) return@withContext true }
                else { loud = 0; floor = floor * 0.98 + rms * 0.02 }
            }
            false
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { rec.stop() }
            fx.forEach { runCatching { it.release() } }
            rec.release()
        }
    }
}
