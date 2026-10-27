package com.smriti.app.live

import android.content.Context
import android.os.SystemClock
import com.smriti.app.ai.SessionRules
import com.smriti.app.core.SmritiEngine
import com.smriti.app.data.AppSettings
import com.smriti.app.memory.ChatRepository
import com.smriti.app.privacy.TextScrubber
import com.smriti.app.util.Haptics
import com.smriti.app.util.SpeechInput
import com.smriti.app.util.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

enum class LivePhase { IDLE, LISTENING, THINKING, SPEAKING, PAUSED }

data class LiveState(
    val active: Boolean = false,
    val phase: LivePhase = LivePhase.IDLE,
    val cameraOn: Boolean = true,
    val muted: Boolean = false,
    val source: String = "phone",
    val startedAt: Long = 0L,
    /** 0..1 microphone loudness while listening */
    val level: Float = 0f,
    /** the one short hint the Live screen may show, for example a missing offline speech pack */
    val notice: String? = null,
    /** which recogniser is listening: "on-device" or "system" */
    val voicePath: String = "on-device",
    /** the last answer, shown briefly as a caption while it is spoken */
    val answer: String? = null,
    /** what the user is saying, live (partial while speaking, final while it thinks) */
    val heard: String = "",
    /** what the phone is doing while it thinks, e.g. "Searching memory" */
    val step: String = "",
    /** what this recording was started to find, or null */
    val target: String? = null,
    /** set as soon as the target is confirmed in view, e.g. "Found at 00:07" */
    val found: String? = null,
    /** a short message about the last thing heard, e.g. that the words could not be made out */
    val hint: String = "",
    /** true while the microphone hears sound loud enough to be a voice (the loudness itself is in [LiveSession.level]) */
    val hearing: Boolean = false
)

/**
 * Hands-free conversation, like a call: listen, think, speak, listen again, until the user ends it.
 * Nothing is typed or shown as text while it runs; every line is saved to the chat so the user can read
 * it afterwards and carry on from there (by text, voice or camera).
 *
 * Everything stays on the phone: the recogniser is the on-device one where available and the answer is
 * composed from stored text rows only.
 */
class LiveSession(
    private val context: Context,
    private val engine: SmritiEngine,
    private val chat: ChatRepository,
    private val settings: StateFlow<AppSettings?>,
    private val haptics: Haptics
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val speech = SpeechInput(context)
    /** the phone's own ears: one continuous microphone stream, transcribed on-device by Gemma-4 (used whenever Gemma is loaded) */
    private val capture = VoiceCapture(context)
    private var levelJob: Job? = null

    private val _level = MutableStateFlow(0f)
    /** microphone loudness 0..1, kept out of [state] so the screen is not redrawn dozens of times a second */
    val level: StateFlow<Float> = _level.asStateFlow()

    private fun pushLevel(l: Float) {
        val q = (l * 25f).toInt() / 25f              // steps of 0.04, so most updates change nothing
        _level.value = q
        val h = q > 0.12f
        if (h != _state.value.hearing) _state.update { it.copy(hearing = h) }
    }
    private var guideJob: Job? = null
    private var lookJob: Job? = null
    private var lastSpoken = ""
    @Volatile private var lastSpokenEndedAt = 0L
    private var loop: Job? = null
    private var stepJob: Job? = null
    private var silences = 0
    /** a question already spoken together with "Hey Smriti", answered before listening again */
    @Volatile private var pendingFirst: String? = null
    private var lastHeard = 0L

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    /** set by the app: handles "take me home" and the other walk guide requests; returns what to answer, or null when the request is not for the walk guide */
    @Volatile var moveHook: (suspend (com.smriti.app.move.MoveIntent) -> String?)? = null

    fun start(cameraOn: Boolean, source: String, firstQuestion: String? = null, target: String? = null) {
        // Coming back to a running session (from the orb or the notification) must not reset it.
        if (_state.value.active) return
        silences = 0
        pendingFirst = firstQuestion
        _state.value = LiveState(active = true, phase = LivePhase.LISTENING, cameraOn = cameraOn, source = source, startedAt = SystemClock.elapsedRealtime())
        haptics.tap()
        capture.paused = false
        if (cameraOn) engine.sessions.start(source, target.orEmpty())
        if (cameraOn && !target.isNullOrBlank()) {
            _state.update { it.copy(target = target.trim(), found = null) }
            // the target is confirmed in view while still filming: tell the user right away
            lookJob = scope.launch {
                engine.sessions.lookFinds.collect { list ->
                    val last = list.lastOrNull() ?: return@collect
                    _state.update { it.copy(found = "Found at ${SessionRules.clock(last.offsetMs)}") }
                    haptics.confirm()
                }
            }
        }
        levelJob = scope.launch { capture.level.collect { l -> if (capture.running) pushLevel(l) } }
        loop = scope.launch { run() }
        // the guide ("show me my keys"): speak and buzz when the object comes into view, and fall back to memory when it never does
        guideJob = scope.launch {
            var seen = 0
            engine.guide.state.collect { g ->
                if (g == null) { seen = 0; return@collect }
                if (g.foundCount > seen) {
                    seen = g.foundCount
                    haptics.confirm()
                    val where = g.box?.let { com.smriti.app.ai.GuideMath.describe(it) }.orEmpty()
                    if (settings.value?.voiceReply == true) scope.launch { TtsManager.speak(engine.localize("I see your ${g.target} $where."), engine.speakTag()) }
                }
                if (g.phase == com.smriti.app.ai.GuideState.Phase.GAVE_UP) {
                    engine.guide.stop()
                    haptics.warn()
                    // not in view: say where it was last seen
                    val a = runCatching { engine.ask("where did I last see my ${g.target}") }.getOrNull() ?: return@collect
                    val src = _state.value.source
                    chat.add("smriti", "I cannot see ${g.target} from here. $a", src, live = true, imagePath = engine.lastAnswerImage)
                    if (settings.value?.voiceReply == true) TtsManager.speak(a.lineSequence().first().take(220), engine.speakTag())
                }
            }
        }
        // what the phone is doing while it thinks, from the same events the website shows
        stepJob = scope.launch {
            com.smriti.app.pipeline.PipelineBus.events.collect { e ->
                if (e.type != com.smriti.app.pipeline.EventType.QUERY || _state.value.phase != LivePhase.THINKING) return@collect
                val step = when (e.stage) {
                    com.smriti.app.pipeline.Stage.INTENT -> if (e.status == com.smriti.app.pipeline.Status.OK) "Searching memory" else "Understanding your question"
                    com.smriti.app.pipeline.Stage.RETRIEVE -> if (e.message == "live frame") "Looking through the camera" else "Reading scenes from memory"
                    com.smriti.app.pipeline.Stage.ANSWER -> "Writing the answer"
                    else -> null
                }
                if (step != null) _state.update { it.copy(step = step) }
            }
        }
    }

    /** debug builds only: feeds a spoken clip into the listening loop (to test the whole voice path without a person) */
    fun debugInject(wav: ByteArray, ms: Long) = capture.inject(wav, ms)

    /** debug builds only: shows [text] as the caption, to check how long answers look on the screen */
    fun debugCaption(text: String) {
        _state.update { it.copy(phase = LivePhase.LISTENING, answer = SpeechText.shown(text), heard = "") }
    }

    fun end() {
        val s = _state.value
        if (!s.active) return
        loop?.cancel()
        stepJob?.cancel()
        levelJob?.cancel()
        pushLevel(0f)
        guideJob?.cancel(); engine.guide.stop()
        lookJob?.cancel()
        engine.sessions.end("Live ended")
        capture.stop()
        speech.stop()
        TtsManager.stop()
        val secs = (SystemClock.elapsedRealtime() - s.startedAt) / 1000
        _state.value = LiveState(source = s.source)
        haptics.confirm()
        scope.launch {
            chat.add("event", "Live session, ${"%d:%02d".format(secs / 60, secs % 60)}, ${if (s.source == "glasses") "glasses" else "phone"}", s.source, live = true)
        }
    }

    /** The last four seconds of sound, for the picture memory, only while a Live session has the microphone open and unmuted. */
    fun heardNow(): ByteArray? = if (capture.running && !capture.paused) capture.recentWav(4000) else null

    fun toggleCamera() {
        val on = !_state.value.cameraOn
        _state.update { it.copy(cameraOn = on) }
        if (on) engine.sessions.start(_state.value.source) else engine.sessions.end("camera turned off")
    }

    fun toggleMute() {
        val muted = !_state.value.muted
        _state.update { it.copy(muted = muted, phase = if (muted) LivePhase.PAUSED else LivePhase.LISTENING) }
        capture.paused = muted
        if (muted) speech.stop() else silences = 0
    }

    /** Tapping the orb wakes a paused session. */
    fun resume() {
        if (_state.value.phase == LivePhase.PAUSED && !_state.value.muted) {
            silences = 0
            _state.update { it.copy(phase = LivePhase.LISTENING) }
        }
    }

    private suspend fun run() {
        while (_state.value.active) {
            val s = _state.value
            val first = pendingFirst
            if (first != null) { pendingFirst = null; respond(first); continue }
            if (s.muted || s.phase == LivePhase.PAUSED) { delay(250); continue }
            // permission granted "only this time" lapses; wait for it instead of failing in a loop
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notice("Microphone permission needed: tap Allow, or enable it in Settings")
                delay(500); continue
            }
            if (s.notice?.startsWith("Microphone permission") == true) notice(null)

            _state.update { it.copy(phase = LivePhase.LISTENING, level = 0f, heard = "", hint = "") }
            val gemmaEars = engine.brain.ready && (capture.running || capture.start(scope))
            if (!gemmaEars && engine.brain.state !is com.smriti.app.ai.Brain.State.NotLoaded) {
                // say why recognition may be poor instead of silently using the weaker phone recogniser
                notice("Smriti's voice model is missing, so the phone's own recogniser is used and may miss words. Settings > AI models")
            }
            val heard = if (gemmaEars) listenGemma() else listenOnce()
            android.util.Log.d("SmritiLive", "heard text=${heard.text != null} silent=${heard.silent} silences=$silences")
            when {
                heard.text != null -> {
                    haptics.heard()
                    silences = 0
                    respond(heard.text)
                }
                heard.silent -> {
                    // Nobody spoke. After about a minute of quiet the session rests until the user taps the orb.
                    if (++silences >= 12) _state.update { it.copy(phase = LivePhase.PAUSED, level = 0f) }
                }
                else -> {
                    // A recogniser hiccup: wait, and give up resting after repeated ones rather than spinning.
                    delay(900)
                    if (++silences >= 5) _state.update { it.copy(phase = LivePhase.PAUSED, level = 0f) }
                }
            }
        }
    }

    private class Heard(val text: String?, val silent: Boolean)

    /** Waits for the voice detector to finish an utterance, then Gemma-4 turns it into words. */
    private suspend fun listenGemma(): Heard {
        capture.speakerOn = false
        val u = capture.next(6_000) ?: return Heard(null, silent = true)
        _state.update { it.copy(heard = "...", hint = "", phase = LivePhase.LISTENING, voicePath = "on-device") }
        val lang = settings.value?.language ?: "en-IN"
        val name = java.util.Locale.forLanguageTag(lang).getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { "English" }
        val t0 = SystemClock.elapsedRealtime()
        val pcm = AudioPrep.samples(u.wav)
        if (com.smriti.app.BuildConfig.DEBUG) {
            // debug builds keep the last clips, so a failure can be replayed through Gemma-4 afterwards (never in release builds)
            runCatching {
                val dir = context.cacheDir
                java.io.File(dir, "utt-prev.wav").delete(); java.io.File(dir, "utt-last.wav").renameTo(java.io.File(dir, "utt-prev.wav"))
                java.io.File(dir, "utt-last.wav").writeBytes(u.wav)
            }
            android.util.Log.i("SmritiLive", "clip ${u.ms} ms, loudness ${pcm?.let { AudioPrep.rms(it).toInt() }}, room ${capture.noiseFloor.toInt()}, voice must pass ${capture.lastThreshold.toInt()}, noise drops ${capture.noiseDrops}, brainBusy=${engine.brain.busy}")
        }
        // first try as recorded; if no words come out, one retry on the loudest 6 seconds at a healthy volume
        // (the person talking to the phone is closer, so louder, than the room)
        var text = kotlinx.coroutines.withTimeoutOrNull(25_000) { engine.brain.transcribe(u.wav, name)?.trim() }
        var retried = false
        if (text.isNullOrBlank() && pcm != null && u.ms >= 900 && SystemClock.elapsedRealtime() - t0 < 20_000) {
            retried = true
            val again = AudioPrep.toWav(AudioPrep.normalise(AudioPrep.loudestWindow(pcm, 6_000)))
            text = kotlinx.coroutines.withTimeoutOrNull(25_000) { engine.brain.transcribe(again, name)?.trim() }
        }
        if (com.smriti.app.BuildConfig.DEBUG) android.util.Log.i("SmritiLive", "heard ${if (text.isNullOrBlank()) "nothing" else "\"$text\""} after ${SystemClock.elapsedRealtime() - t0} ms, retried=$retried, err=${engine.brain.lastError}")
        if (text == null && SystemClock.elapsedRealtime() - t0 >= 24_000) {
            // Gemma-4 is busy or the phone is too hot to answer in time: say so instead of waiting silently
            android.util.Log.w("SmritiLive", "transcription timed out after ${SystemClock.elapsedRealtime() - t0} ms, brainBusy=${engine.brain.busy}")
            _state.update { it.copy(heard = "", hint = "Understanding is taking too long. The phone may be hot or busy. Please try again.") }
            delay(2_500)
            return Heard(null, silent = true)
        }
        if (text.isNullOrBlank() || text.length < 2) {
            // the voice detector heard something, but no words came out of it: say so instead of going quiet
            _state.update { it.copy(heard = "", hint = "I heard you but could not make out the words. Please say it again.") }
            delay(2_200)
            return Heard(null, silent = true)
        }
        // never answer Smriti's own voice coming back through the microphone
        if (isEcho(text, u.startedWhileSmritiSpoke)) { android.util.Log.d("SmritiLive", "ignored echo"); _state.update { it.copy(heard = "") }; return Heard(null, silent = true) }
        _state.update { it.copy(heard = text, hint = "") }
        return Heard(text, false)
    }

    /**
     * Smriti's own voice coming back through the microphone. Only a clip that started while Smriti was talking, or within five seconds
     * of it stopping, can be an echo: a question asked later that happens to reuse words of a long answer is the user, not an echo.
     */
    private fun isEcho(text: String, startedWhileSpeaking: Boolean): Boolean {
        if (lastSpoken.isBlank()) return false
        if (!startedWhileSpeaking && SystemClock.elapsedRealtime() - lastSpokenEndedAt > 5_000) return false
        val a = text.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        val b = lastSpoken.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        if (a.size < 3 || b.isEmpty()) return false
        return a.count { it in b }.toFloat() / a.size >= 0.75f
    }

    private suspend fun listenOnce(): Heard = suspendCancellableCoroutine { cont ->
        val lang = settings.value?.language ?: "en-IN"
        speech.strictOffline = settings.value?.strictOfflineVoice == true
        speech.listen(
            languageTag = lang,
            onResult = { t -> notice(null); publishPath(); _state.update { it.copy(heard = t) }; if (cont.isActive) cont.resume(Heard(t, false)) },
            onError = { msg ->
                android.util.Log.d("SmritiLive", "recogniser said: $msg (path=${speech.path})")
                publishPath()
                // a missing offline pack only matters here when system fallback is off; otherwise SpeechInput already switched
                if (msg.startsWith("Offline speech pack") || msg.startsWith("No speech recognizer")) notice(msg)
                // 6 = no speech, 7 = no match: both just mean "nothing said"
                val quiet = msg.contains("error 6") || msg.contains("error 7") || msg.contains("Didn't catch")
                if (cont.isActive) cont.resume(Heard(null, quiet))
            },
            onLevel = { l -> pushLevel(l) },
            onPartial = { p -> _state.update { it.copy(heard = p) } }
        )
        cont.invokeOnCancellation { speech.stop() }
    }

    private fun publishPath() = _state.update { it.copy(voicePath = speech.path) }

    private fun notice(text: String?) = _state.update { it.copy(notice = text) }

    private val barge = BargeIn(context)

    /**
     * Answers one question while watching for the user to speak again. If they do, the voice stops at once, the answer in
     * progress is dropped and the loop goes straight back to listening for the new question.
     */
    private suspend fun respond(said: String) = kotlinx.coroutines.coroutineScope {
        var interrupted = false
        val work = launch { handle(said) }
        val watch = launch {
            // give the first syllables of the answer a moment to start so the very start of the voice is not mistaken for the user
            delay(350)
            val spoke = if (capture.running) {
                // the ears are already recording: when speech starts, what is said is kept (including its first word) for the next turn
                capture.speaking.first { it }; true
            } else barge.awaitSpeech(speakerOn = _state.value.phase == LivePhase.SPEAKING)
            if (spoke) {
                interrupted = true
                TtsManager.stop()
                work.cancel()
            }
        }
        work.join()
        watch.cancel()
        if (interrupted) {
            android.util.Log.d("SmritiLive", "interrupted by the user (peak ${barge.peak})")
            haptics.tap()
            silences = 0
            _state.update { it.copy(phase = LivePhase.LISTENING, answer = null, step = "", level = 0f, heard = "") }
        }
    }

    private suspend fun handle(raw: String) {
        val said = raw.trim()
        android.util.Log.d("SmritiLive", "heard \"$said\" -> ${com.smriti.app.query.IntentParser.parse(said)}")
        if (said.lowercase().trim('.', '!', ' ') in END_WORDS) { end(); return }
        val src = _state.value.source
        chat.add("user", TextScrubber.scrub(said), src, live = true)
        lastHeard = SystemClock.elapsedRealtime()
        _state.update { it.copy(phase = LivePhase.THINKING, level = 0f, heard = said, step = "Understanding your question", answer = null) }
        // "take me home", "I am going out", "stop guiding": the walk guide (no camera; steps, compass and GPS), for a person who is out and about
        val moveReply: String? = try {
            com.smriti.app.move.MoveIntentParser.parse(said)?.let { intent -> moveHook?.invoke(intent) }
        } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (t: Throwable) { null }
        // "where is my keys", "show me the bottle": with the camera on, the camera helps find it live (a ring on the preview and which way to turn)
        val guideTarget = if (moveReply == null && _state.value.cameraOn) com.smriti.app.ai.GuideIntent.parse(said) else null
        // "where did I see my bottle": answered from memory as always; with the camera on, the live search for it is started too
        val seenTarget = if (moveReply == null && guideTarget == null && _state.value.cameraOn) com.smriti.app.ai.GuideIntent.parseSeen(said) else null
        val stopGuide = moveReply == null && guideTarget == null && engine.guide.active && com.smriti.app.ai.GuideIntent.isStop(said)
        val answered = try {
            when {
                moveReply != null -> engine.localize(moveReply)
                guideTarget != null -> { engine.guide.start(guideTarget); engine.localize("Okay, looking for $guideTarget. Move the phone slowly around.") }
                stopGuide -> { engine.guide.stop(); engine.localize("Okay, I stopped looking.") }
                else -> engine.ask(said)
            }
        } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (t: Throwable) { "Something went wrong. Please try again." }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()      // the user may have interrupted while it was thinking
        val answer = if (seenTarget != null && !answered.startsWith("I heard") && !answered.startsWith("Something went wrong")) {
            engine.guide.start(seenTarget)
            answered + " " + engine.localize("Move the phone slowly around and I will show you if I see it now.")
        } else answered
        chat.add("smriti", answer, src, live = true, imagePath = if (moveReply != null || guideTarget != null || stopGuide) null else engine.lastAnswerImage)
        if (!_state.value.active) return
        val shown = SpeechText.shown(answer)
        _state.update { it.copy(phase = LivePhase.SPEAKING, answer = shown, step = "") }
        if (answer.startsWith("I heard")) haptics.warn() else haptics.speakStart()
        val style = settings.value?.answerStyle ?: "short"
        val spoken = SpeechText.spoken(answer, detailed = style != "short")
        lastSpoken = spoken
        capture.speakerOn = true          // stricter voice detection while Smriti talks, so its own voice is not taken for the user
        try {
            TtsManager.speakAndWait(spoken, engine.speakTag())
        } finally {
            capture.speakerOn = false
            lastSpokenEndedAt = SystemClock.elapsedRealtime()
            TtsManager.stop()            // also runs when the user interrupts
        }
        // the caption lingers a little after the voice ends, but the microphone is already listening again
        val keep = SpeechText.captionHoldMs(shown)
        scope.launch { delay(keep); _state.update { if (it.answer == shown) it.copy(answer = null) else it } }
    }

    private companion object {
        val END_WORDS = setOf("stop", "end", "end session", "goodbye", "bye", "that's all", "thats all", "close", "exit", "stop listening")
    }
}
