package com.smriti.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.smriti.app.pipeline.PipelineBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Gemma-4 E2B running on the phone (LiteRT-LM): the part that can actually WRITE an answer, look at a picture and
 * answer questions that are not about memory. Memory facts are handed to it as context so it answers from what Smriti
 * really saw instead of inventing. The model file is the one Google AI Edge Gallery downloads; see scripts/install-gallery-model.ps1.
 */
class Brain(private val context: Context) {

    sealed interface State {
        data object NotLoaded : State
        data class Missing(val expected: String) : State
        data class Ready(val backend: String, val loadMs: Long) : State
        data class Failed(val reason: String) : State
    }

    @Volatile var state: State = State.NotLoaded
        private set
    @Volatile var lastError: String? = null
        private set

    private var engine: Engine? = null
    private val one = Mutex()
    /** true while a request is running; background work such as the object inventory waits for a free moment */
    val busy: Boolean get() = one.isLocked

    val ready: Boolean get() = state is State.Ready

    fun modelFile(): File {
        val dir = File(context.filesDir, "models")
        dir.listFiles { f -> f.name.endsWith(".litertlm") && f.name.startsWith("gemma-4", true) }?.maxByOrNull { it.lastModified() }?.let { return it }
        return File(dir, "gemma-4-E2B-it.litertlm")
    }

    private var triedCpu = false

    @Synchronized
    fun load(cpuOnly: Boolean = false): State {
        if (state is State.Ready) return state
        val f = modelFile()
        if (!f.exists()) { state = State.Missing(f.absolutePath); AiMeter.missing(METER, ROLE); return state }
        val errors = ArrayList<String>()
        val order = listOf<Pair<String, () -> Backend>>("GPU" to { Backend.GPU() }, "CPU" to { Backend.CPU() }).filter { !cpuOnly || it.first == "CPU" }
        for ((name, make) in order) {
            val t0 = SystemClock.elapsedRealtime()
            try {
                val e = Engine(
                    EngineConfig(
                        modelPath = f.absolutePath,
                        backend = make(),
                        visionBackend = if (name == "GPU") Backend.GPU() else Backend.CPU(),
                        audioBackend = Backend.CPU(),
                        cacheDir = context.cacheDir.path
                    )
                )
                e.initialize()
                engine = e
                val loadedMs = SystemClock.elapsedRealtime() - t0
                state = State.Ready(name, loadedMs)
                AiMeter.loaded(METER, ROLE, name, loadedMs)
                return state
            } catch (t: Throwable) {
                errors += "$name: ${t.javaClass.simpleName} ${t.message?.take(200)}"
            }
        }
        state = State.Failed(errors.joinToString(" | "))
        AiMeter.failed(METER, ROLE, errors.firstOrNull().orEmpty())
        return state
    }

    /**
     * One question, optionally with a picture. [system] carries the facts Smriti remembers; the model is told to use only those
     * for questions about the past. Returns null if the model is unavailable or fails, so the caller can fall back to templates.
     */
    /** the "Short" answer style from Settings: replies meant for the user are kept to a sentence or two (set by the app) */
    @Volatile var shortAnswers: Boolean = true

    suspend fun ask(system: String, question: String, image: Bitmap? = null, maxChars: Int = 600, background: Boolean = false, forUser: Boolean = false): String? {
        if (!background) preemptBackground()          // the user is waiting: any background request in flight gives way
        return one.withLock {
        withContext(Dispatchers.Default) {
            val e = engine ?: return@withContext null
            try {
                val parts = ArrayList<Content>()
                if (image != null) parts += Content.ImageBytes(png(image))
                parts += Content.Text(question)
                val reply = e.createConversation(ConversationConfig(systemInstruction = Contents.of(if (forUser && shortAnswers) system + SHORT_RULE else system))).use { c ->
                    if (background) { preempted = false; bgConv = c }
                    try {
                        AiMeter.time(METER, ROLE, backendName()) { c.sendMessage(Contents.of(*parts.toTypedArray())).toString() }
                    } finally { if (background) bgConv = null }
                }
                if (background && preempted) { preempted = false; return@withContext null }       // cut short for the user: its partial text is dropped
                lastError = null
                clean(reply).take(maxChars).ifBlank { null }
            } catch (t: Throwable) {
                if (background) {
                    // background work never rebuilds the engine, and a request cut short for the user is not an error
                    if (!preempted) lastError = "background ${t.javaClass.simpleName}: ${t.message?.take(200)}"
                    preempted = false
                    return@withContext null
                }
                lastError = "${t.javaClass.simpleName}: ${t.message?.take(300)}"
                PipelineBus.warn("brain", "Gemma answer failed: $lastError")
                // a GPU that loads but cannot run (missing vendor library) must not leave Smriti mute: rebuild on the CPU once
                if ((state as? State.Ready)?.backend == "GPU" && !triedCpu) {
                    triedCpu = true
                    runCatching { engine?.close() }; engine = null; state = State.NotLoaded
                    if (load(cpuOnly = true) is State.Ready) return@withContext askAgain(system, question, image, maxChars)
                }
                null
            }
        }
        }
    }

    /** the background request now running, if any, and whether it was cut short */
    @Volatile private var bgConv: com.google.ai.edge.litertlm.Conversation? = null
    @Volatile private var preempted = false

    /** stops the background request in flight (the object inventory) so a request from the user can run at once */
    fun preemptBackground() {
        val c = bgConv ?: return
        preempted = true
        runCatching { c.cancelProcess() }
    }

    /** Translates [text] into [language]. Numbers, times and names are kept. Returns null if the model is unavailable. */
    suspend fun translate(text: String, language: String): String? =
        ask(
            system = "You are a translator. Translate the user's message into $language. Keep numbers, clock times, names and emoji exactly. Keep line breaks. Keep the meaning and tense: a report of something done (\"Flashlight on.\") stays a short report, never a command. Reply with only the translation, nothing else.",
            question = text, maxChars = 900
        )

    /**
     * Speech to text with Gemma-4's own audio understanding, fully on the phone and independent of the system recogniser.
     * [wav] is a complete WAV file (16 kHz mono 16-bit works). Returns the words only, or null if nothing was said or it failed.
     */
    suspend fun transcribe(wav: ByteArray, languageHint: String = "English"): String? {
        preemptBackground()
        return one.withLock {
        withContext(Dispatchers.Default) {
            val e = engine ?: return@withContext null
            try {
                val sys = "You are a speech recogniser. Write down exactly the words spoken in the audio, in $languageHint or the language spoken, with normal punctuation. " +
                    "Reply with only the words. If there is no speech, reply with exactly: [silence]"
                val reply = e.createConversation(ConversationConfig(systemInstruction = Contents.of(sys))).use { c ->
                    AiMeter.time(METER, ROLE, backendName()) { c.sendMessage(Contents.of(Content.AudioBytes(wav), Content.Text("Transcribe."))).toString() }
                }
                val text = clean(reply).trim().trim('"')
                lastError = null
                // a model that did not hear speech tends to talk to the user instead; none of that is a question
                if (text.isBlank() || NOT_SPEECH.containsMatchIn(text)) null else text
            } catch (t: Throwable) {
                lastError = "asr ${t.javaClass.simpleName}: ${t.message?.take(300)}"
                null
            }
        }
        }
    }

    /** the same request after the engine was rebuilt on the CPU (the mutex is already held by the caller) */
    private fun askAgain(system: String, question: String, image: Bitmap?, maxChars: Int): String? {
        val e = engine ?: return null
        return try {
            val parts = ArrayList<Content>()
            if (image != null) parts += Content.ImageBytes(png(image))
            parts += Content.Text(question)
            val reply = e.createConversation(ConversationConfig(systemInstruction = Contents.of(system))).use { c -> AiMeter.time(METER, ROLE, backendName()) { c.sendMessage(Contents.of(*parts.toTypedArray())).toString() } }
            lastError = null
            clean(reply).take(maxChars).ifBlank { null }
        } catch (t: Throwable) {
            lastError = "CPU retry: ${t.javaClass.simpleName}: ${t.message?.take(300)}"; null
        }
    }

    fun close() { runCatching { engine?.close() }; engine = null; state = State.NotLoaded }

    private fun backendName() = (state as? State.Ready)?.backend ?: "GPU"

    private companion object {
        /** added to the system prompt of a user-facing reply when the answer style is Short */
        const val SHORT_RULE = " IMPORTANT: the user chose short answers. Reply in one or two short sentences, about 25 words at most, unless they ask for a list or for details."
        const val METER = "Gemma-4 E2B"
        const val ROLE = "answers, vision, speech, translation"
        val NOT_SPEECH = Regex(
            "^\\s*(none|\\[?silence\\]?|\\(?no speech\\)?)\\W*$|transcri|provide (the|an?|me) (audio|text|images?)|i (can(no|')t|am unable|do not|don't) (hear|understand|process|access)|" +
                "no (clear )?(speech|audio)|i('m| am) (ready|here)|as an ai|how can i help",
            RegexOption.IGNORE_CASE
        )
    }

    private fun png(b: Bitmap): ByteArray {
        val m = maxOf(b.width, b.height)
        val s = if (m > 768) Bitmap.createScaledBitmap(b, b.width * 768 / m, b.height * 768 / m, true) else b
        val out = ByteArrayOutputStream()
        s.compress(Bitmap.CompressFormat.PNG, 100, out)
        if (s !== b) s.recycle()
        return out.toByteArray()
    }

    /** the model may wrap its answer in role markers or thinking tags; keep only plain text */
    private fun clean(s: String): String =
        s.replace(Regex("(?s)<think>.*?</think>"), "").replace(Regex("<[^>]{1,30}>"), "").trim()
}
