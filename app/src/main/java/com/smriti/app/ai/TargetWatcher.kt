package com.smriti.app.ai

import com.smriti.app.embed.GemmaEmbedder
import com.smriti.app.memory.VisualEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "Look for it": the user asked first ("where is my pill"), then started the camera. The question is turned into a vector once;
 * every window that gets remembered during the recording is compared with it straight away. A close window is shown to
 * Gemma-4, one at a time and not too often, and a confirmed one is a FIND: its picture and its place in the recording are kept and
 * the Live screen is told, so the user hears about it while the camera is still pointing at the thing.
 */
class TargetWatcher(
    val target: String,
    private val sessionStart: Long,
    private val visual: VisualMemory,
    private val search: MomentSearch,
    private val scope: CoroutineScope
) {
    /** a confirmed find */
    class Find(val window: VisualEntity, val offsetMs: Long, val score: Float)

    private val _finds = MutableStateFlow<List<Find>>(emptyList())
    val finds: StateFlow<List<Find>> = _finds.asStateFlow()

    @Volatile private var query: FloatArray? = null
    private val queue = Channel<Pair<VisualEntity, Float>>(capacity = 3, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val scores = ArrayList<Float>()
    private var lastQueuedAt = 0L
    private var lastCheckedVec: FloatArray? = null
    @Volatile var checks = 0; private set
    @Volatile private var closed = false

    init {
        scope.launch { query = visual.embedder.embedText(target) }
        scope.launch {
            for ((window, score) in queue) {
                if (closed) break
                val yes = search.verifyWindow(target, window)
                checks++
                if (yes) _finds.value = _finds.value + Find(window, window.timestamp - sessionStart, score)
                delay(CHECK_GAP_MS)             // gives the phone a breath between looks
            }
        }
    }

    /** Called for every window as it is remembered. Cheap: one dot product, and a check only when it looks promising. */
    fun offer(window: VisualEntity, vec: FloatArray) {
        val q = query ?: return
        val s = GemmaEmbedder.cosine(q, vec)
        synchronized(scores) { scores += s }
        val median = synchronized(scores) { scores.sorted()[scores.size / 2] }
        // promising: clearly above what the recording scores so far, and above the loose floor
        if (s < maxOf(LOOSE_MIN, median + 0.01f)) return
        // not the same scene again, and not too soon after the last one handed to Gemma-4
        val last = lastCheckedVec
        if (last != null && GemmaEmbedder.cosine(last, vec) > SAME_VIEW) return
        if (window.timestamp - lastQueuedAt < MIN_SPACING_MS) return
        lastQueuedAt = window.timestamp
        lastCheckedVec = vec
        queue.trySend(window to s)
    }

    /** the confirmed windows, oldest first */
    fun foundWindows(): List<VisualEntity> = _finds.value.map { it.window }.sortedBy { it.timestamp }

    fun close() { closed = true; queue.close() }

    private companion object {
        const val LOOSE_MIN = 0.28f
        const val SAME_VIEW = 0.985f
        const val MIN_SPACING_MS = 3_000L
        const val CHECK_GAP_MS = 800L
    }
}
