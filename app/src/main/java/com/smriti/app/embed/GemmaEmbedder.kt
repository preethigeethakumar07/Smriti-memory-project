package com.smriti.app.embed

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.InputData
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.sqrt

/**
 * Meaning-based vectors from the EmbeddingGemma-2 model that Google AI Edge Gallery also uses (LiteRT-LM engine).
 * The model file is NOT in the APK: scripts/install-gallery-model.ps1 copies it into [modelFile]'s folder on the phone.
 * Everything here is optional: if the file is missing or the engine fails to load, [state] says why and the app keeps
 * using keyword search.
 */
class GemmaEmbedder(private val context: Context) {

    sealed interface State {
        data object NotLoaded : State
        data class Missing(val expected: String) : State
        data class Ready(val backend: String, val loadMs: Long, val dim: Int) : State
        data class Failed(val reason: String) : State
    }

    @Volatile var state: State = State.NotLoaded
        private set

    private var engine: EmbeddingEngine? = null

    /** Where the model has to be. The Gallery's own folder is private to the Gallery, so it is copied here with adb. */
    fun modelFile(): File {
        // app-private folder first (written by the install script through run-as), then the app's external folder
        val dirs = listOf(File(context.filesDir, "models"), File(context.getExternalFilesDir(null), "models"))
        for (dir in dirs) {
            dir.listFiles { f -> f.name.endsWith(".litertlm") && f.name.contains("embeddinggemma", true) }
                ?.maxByOrNull { it.lastModified() }?.let { return it }
        }
        return File(dirs[0], "embeddinggemma-2.litertlm")
    }

    /** Loads once. NPU first, then GPU, then CPU; the backend that worked is reported, nothing is claimed that did not load. */
    @Synchronized
    fun load(): State {
        if (state is State.Ready) return state
        val f = modelFile()
        if (!f.exists()) { state = State.Missing(f.absolutePath); com.smriti.app.ai.AiMeter.missing(METER, ROLE); return state }
        val errors = ArrayList<String>()
        // text runs on the NPU; the image/audio encoders sit beside it on GPU/CPU (the NPU build has no vision executor of its own)
        val tries: List<Triple<String, () -> Backend, () -> Backend>> = listOf(
            Triple("NPU", { Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir) }, { Backend.GPU() }),
            Triple("NPU", { Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir) }, { Backend.CPU() }),
            Triple("GPU", { Backend.GPU() }, { Backend.GPU() }),
            Triple("CPU", { Backend.CPU() }, { Backend.CPU() })
        )
        for ((name, backend, vision) in tries) {
            val t0 = SystemClock.elapsedRealtime()
            try {
                val e = EmbeddingEngine(EmbeddingEngineConfig(modelPath = f.absolutePath, backend = backend(), visionBackend = vision(), audioBackend = Backend.CPU()))
                e.initialize()
                val dim = e.computeEmbedding(listOf(InputData.Text("warm up"))).embedding.size
                engine = e
                val ms = SystemClock.elapsedRealtime() - t0
                state = State.Ready(name, ms, dim)
                com.smriti.app.ai.AiMeter.loaded(METER, ROLE, if (name == "NPU") "NPU (text) + GPU (vision)" else name, ms)
                return state
            } catch (t: Throwable) {
                errors += "$name: ${t.javaClass.simpleName} ${t.message?.take(160)}"
            }
        }
        state = State.Failed(errors.joinToString(" | "))
        com.smriti.app.ai.AiMeter.failed(METER, ROLE, errors.firstOrNull().orEmpty())
        return state
    }

    fun embedText(text: String): FloatArray? = run(InputData.Text(text))

    /** Raw audio bytes as the model expects them (WAV/PCM). Only the vector is kept; the sound itself is never saved. */
    fun embedAudio(bytes: ByteArray): FloatArray? = run(InputData.Audio(bytes))

    /**
     * One vector for a moment made of sound AND picture, the way the Gallery's Video Moment Finder embeds a window of video
     * (audio first, then the frame). Text queries then match what was seen and what was heard together.
     */
    fun embedMoment(picture: Bitmap, wav: ByteArray?): FloatArray? {
        val out = ByteArrayOutputStream()
        picture.compress(Bitmap.CompressFormat.PNG, 100, out)
        val parts = ArrayList<InputData>(2)
        if (wav != null) parts += InputData.Audio(wav)
        parts += InputData.Image(out.toByteArray())
        val e = engine ?: return null
        return try {
            com.smriti.app.ai.AiMeter.time(METER, ROLE, meterBackend()) { normalise(asFloats(e.computeEmbedding(parts).embedding)) }.also { lastError = null }
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message?.take(300)}"
            // some builds only take one part at a time: fall back to the picture alone
            if (wav != null) embedMoment(picture, null) else null
        }
    }

    /** The bitmap given here must already be face-blurred; only the returned vector is kept. */
    fun embedImage(bitmap: Bitmap): FloatArray? {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return run(InputData.Image(out.toByteArray()))
    }

    /** Last error message from a failed embedding, for the debug test and the dashboard log. */
    @Volatile var lastError: String? = null
        private set

    private fun run(input: InputData): FloatArray? {
        val e = engine ?: return null
        return try {
            com.smriti.app.ai.AiMeter.time(METER, ROLE, meterBackend()) { normalise(asFloats(e.computeEmbedding(listOf(input)).embedding)) }.also { lastError = null }
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message?.take(300)}"; null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun asFloats(raw: Any): FloatArray =
        if (raw is FloatArray) raw else FloatArray((raw as Collection<Number>).size).also { out -> (raw as Collection<Number>).forEachIndexed { i, n -> out[i] = n.toFloat() } }

    fun close() { runCatching { engine?.close() }; engine = null; state = State.NotLoaded }

    private fun meterBackend() = (state as? State.Ready)?.backend.let { if (it == "NPU") "NPU (text) + GPU (vision)" else it ?: "-" }

    companion object {
        const val METER = "EmbeddingGemma-2"
        const val ROLE = "search by meaning (text, picture, sound)"

        fun normalise(v: FloatArray): FloatArray {
            var s = 0.0
            for (x in v) s += x * x
            val n = sqrt(s).toFloat().coerceAtLeast(1e-9f)
            return FloatArray(v.size) { v[it] / n }
        }

        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return 0f
            var d = 0f
            for (i in a.indices) d += a[i] * b[i]
            return d
        }
    }
}
