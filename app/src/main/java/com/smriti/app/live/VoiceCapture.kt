package com.smriti.app.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Smriti's own ears. One microphone stream runs for the whole Live session and is cut into utterances by a voice detector:
 *  - the last 400 ms before the voice starts are kept, so the first word is never lost (the old recogniser restarted between
 *    questions and missed it);
 *  - an utterance ends after about 0.9 s of quiet, so a sentence with a pause in it is not chopped;
 *  - each utterance is returned as a WAV file for Gemma-4 to transcribe on the phone.
 * Because the same stream also tells when the user starts talking, it doubles as the interruption detector: [speaking] turns true
 * the moment speech starts, even while Smriti is answering, and nothing the user said is lost because the recording keeps going.
 */
class VoiceCapture(private val context: Context) {

    class Utterance(val wav: ByteArray, val ms: Long, val startedWhileSmritiSpoke: Boolean)

    private val _speaking = MutableStateFlow(false)
    /** true from the moment speech is detected until the utterance is finished */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _level = MutableStateFlow(0f)
    /** 0..1 loudness for the Live dial */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val out = Channel<Utterance>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var job: Job? = null

    /** Smriti's own voice is playing: a clearer, louder signal is needed to count as the user speaking. */
    @Volatile var speakerOn = false
    /** the user muted the session */
    @Volatile var paused = false
    /** what the detector currently thinks the room noise is, and the level a voice must pass (rms of 16-bit samples) */
    @Volatile var noiseFloor = 0.0
        private set
    @Volatile var lastThreshold = 0.0
        private set
    /** how many steady-noise stretches were dropped instead of being sent as "speech" */
    @Volatile var noiseDrops = 0
        private set
    /** loudest level seen, for tuning */
    @Volatile var peak = 0
        private set

    val running: Boolean get() = job?.isActive == true

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start(scope: CoroutineScope): Boolean {
        if (running) return true
        if (!hasPermission()) return false
        job = scope.launch(Dispatchers.IO) { loop() }
        return true
    }

    fun stop() {
        job?.cancel(); job = null
        _speaking.value = false; _level.value = 0f
        drain()
    }

    private val ring = ArrayDeque<ShortArray>()

    /**
     * The last [ms] of sound as a WAV, or null when the room was quiet. Used so a remembered moment can carry what was HEARD
     * as well as what was seen. The sound is turned into a vector and thrown away; it is never saved.
     */
    @Synchronized
    fun recentWav(ms: Int): ByteArray? {
        val frames = (ms / 20).coerceAtMost(ring.size)
        if (frames < 25) return null
        val o = ByteArrayOutputStream()
        var s = 0.0; var n = 0
        for (f in ring.toList().takeLast(frames)) { writeFrame(o, f); for (x in f) { s += x.toDouble() * x; n++ } }
        val rms = if (n == 0) 0.0 else sqrt(s / n)
        return if (rms < 250.0) null else wav(o.toByteArray())
    }

    /** debug builds: hands a finished clip to the listener as if the microphone had just heard it */
    fun inject(wav: ByteArray, ms: Long) { out.trySend(Utterance(wav, ms, false)) }

    /** throws away anything already captured (for example what the room said while Smriti was answering) */
    fun drain() { while (out.tryReceive().isSuccess) { /* discard */ } }

    /** The next finished utterance, or null if nobody spoke within [timeoutMs]. */
    suspend fun next(timeoutMs: Long): Utterance? = withTimeoutOrNull(timeoutMs) { out.receive() }

    private fun loop() {
        val rate = RATE
        val frame = rate / 50                                         // 20 ms
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, frame * 16))
        } catch (_: Throwable) { return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
        val fx = ArrayList<android.media.audiofx.AudioEffect>(3)
        runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.let { it.enabled = true; fx.add(it) } }
        runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.let { it.enabled = true; fx.add(it) } }
        runCatching { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(rec.audioSessionId)?.let { it.enabled = true; fx.add(it) } }

        val buf = ShortArray(frame)
        val pre = ArrayDeque<ShortArray>()
        val utt = ByteArrayOutputStream()
        var va = VoiceActivity()
        var uttFrames = 0
        var startedWhileSpeaking = false
        try {
            rec.startRecording()
            while (job?.isActive == true) {
                val r = rec.read(buf, 0, frame)
                if (r <= 0) continue
                var s = 0.0
                for (i in 0 until r) s += buf[i].toDouble() * buf[i]
                val rms = sqrt(s / r)
                if (rms > peak) peak = rms.toInt()
                _level.value = _level.value * 0.6f + (rms / 5000.0).toFloat().coerceIn(0f, 1f) * 0.4f
                if (paused) { va = VoiceActivity(); utt.reset(); pre.clear(); uttFrames = 0; _speaking.value = false; synchronized(this) { ring.clear() }; continue }
                synchronized(this) { ring.addLast(buf.copyOf(r)); while (ring.size > 500) ring.removeFirst() }      // the last 10 seconds

                val copy = buf.copyOf(r)
                val wasInSpeech = va.inSpeech
                val ev = va.feed(rms, speakerOn)
                noiseFloor = va.floor; lastThreshold = va.threshold
                if (!wasInSpeech) { pre.addLast(copy); while (pre.size > PREROLL_FRAMES) pre.removeFirst() }

                if (ev == VoiceActivity.Event.START) {
                    uttFrames = pre.size
                    startedWhileSpeaking = speakerOn
                    utt.reset()
                    pre.forEach { writeFrame(utt, it) }
                    pre.clear()
                    _speaking.value = true
                } else if (wasInSpeech) {
                    writeFrame(utt, copy); uttFrames++
                    when (ev) {
                        VoiceActivity.Event.END, VoiceActivity.Event.MAX -> {
                            val keep = uttFrames - (va.quietFrames - TAIL_FRAMES).coerceAtLeast(0)
                            val bytes = utt.toByteArray().copyOf((keep * frame * 2).coerceAtMost(utt.size()))
                            if (va.speechFrames * 20 >= MIN_SPEECH_MS) out.trySend(Utterance(wav(bytes, rate), keep * 20L, startedWhileSpeaking))
                            utt.reset(); uttFrames = 0; _speaking.value = false
                        }
                        VoiceActivity.Event.NOISE -> {         // a steady roar, not a person: dropped, and the floor moved up to it
                            noiseDrops++
                            utt.reset(); uttFrames = 0; _speaking.value = false
                        }
                        else -> {}
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { rec.stop() }
            fx.forEach { runCatching { it.release() } }
            rec.release()
            _speaking.value = false
        }
    }

    /**
     * Records [seconds] of sound exactly as Live hears it (same microphone, same echo, noise and gain processing). Null when a Live session
     * is using the microphone, the permission is missing, or the microphone cannot start. Used by the Settings microphone test.
     */
    suspend fun recordClip(seconds: Int): ShortArray? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (running || !hasPermission()) return@withContext null
        val frame = RATE / 50
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, frame * 16))
        } catch (_: Throwable) { return@withContext null }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@withContext null }
        val fx = ArrayList<android.media.audiofx.AudioEffect>(3)
        runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.let { it.enabled = true; fx.add(it) } }
        runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.let { it.enabled = true; fx.add(it) } }
        runCatching { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(rec.audioSessionId)?.let { it.enabled = true; fx.add(it) } }
        val out = ShortArray(seconds * RATE)
        var n = 0
        val buf = ShortArray(frame)
        try {
            rec.startRecording()
            while (n < out.size) {
                val r = rec.read(buf, 0, minOf(frame, out.size - n))
                if (r <= 0) continue
                System.arraycopy(buf, 0, out, n, r); n += r
            }
        } finally { runCatching { rec.stop() }; fx.forEach { runCatching { it.release() } }; rec.release() }
        out
    }

    /**
     * Debug builds: listens for [seconds] with no Live session running and reports how loud the room is, and how the old fixed-floor
     * detector and the new adaptive one would each have behaved on exactly that sound (to measure a noisy venue instead of guessing).
     */
    suspend fun survey(seconds: Int): String {
        val pcm = recordClip(seconds) ?: return "could not record: a Live session is using the microphone, or there is no permission"
        val levels = (0 until pcm.size / AudioPrep.FRAME).map { f -> AudioPrep.rms(pcm.copyOfRange(f * AudioPrep.FRAME, (f + 1) * AudioPrep.FRAME)) }
        if (levels.size < 50) return "too little sound captured (${levels.size} frames)"
        val sorted = levels.sorted()
        fun p(q: Double) = sorted[((sorted.size - 1) * q).toInt()].toInt()

        // the old detector: floor from the first 300 ms, never raised
        var oldFloor = 0.0; var loudRun = 0; var inSp = false; var quiet = 0; var total = 0; var oldUtts = 0; var oldMax = 0; var oldLoud = 0
        for ((i, r) in levels.withIndex()) {
            if (i < 15) { oldFloor = if (oldFloor == 0.0) r else oldFloor * 0.8 + r * 0.2; continue }
            val loud = r > max(900.0, oldFloor * 3.0)
            if (loud) oldLoud++
            if (!inSp) { if (loud) loudRun++ else { loudRun = 0; oldFloor = oldFloor * 0.98 + r * 0.02 }; if (loudRun >= 5) { inSp = true; quiet = 0; total = 0 } }
            else { total++; if (loud) quiet = 0 else quiet++; if (quiet >= 45 || total >= 750) { if (total >= 750) oldMax++; oldUtts++; inSp = false; loudRun = 0 } }
        }
        // the new detector on the same sound
        val va = VoiceActivity(); var noise = 0; var ends = 0; var spoken = 0
        for (r in levels) when (va.feed(r, false)) {
            VoiceActivity.Event.NOISE -> noise++
            VoiceActivity.Event.END, VoiceActivity.Event.MAX -> { ends++; spoken += va.speechFrames }
            else -> {}
        }
        return "room for ${levels.size / 50} s: loudness mean=${levels.average().toInt()} p10=${p(0.1)} p50=${p(0.5)} p90=${p(0.9)} p99=${p(0.99)} max=${sorted.last().toInt()} | " +
            "OLD detector: floor from first 300 ms=${oldFloor.toInt()} loud=${oldLoud * 100 / levels.size}% of frames, clips sent=$oldUtts (15 s cut-offs: $oldMax) | " +
            "NEW detector: floor now=${va.floor.toInt()} voice must pass ${va.threshold.toInt()}, clips sent=$ends, steady-noise drops=$noise, speech heard=${spoken * 20 / 1000.0} s"
    }

    private fun writeFrame(o: ByteArrayOutputStream, f: ShortArray) {
        val b = ByteBuffer.allocate(f.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.asShortBuffer().put(f)
        o.write(b.array())
    }

    companion object {
        const val RATE = 16_000
        private const val PREROLL_FRAMES = 20            // 400 ms kept from before the voice started
        private const val TAIL_FRAMES = 10               // 0.2 s of that quiet stays in the clip
        private const val MIN_SPEECH_MS = 450

        /** a complete 16 kHz mono 16-bit WAV file around raw samples */
        fun wav(pcm: ByteArray, rate: Int = RATE): ByteArray {
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            h.put("data".toByteArray()).putInt(pcm.size)
            return h.array() + pcm
        }
    }
}
