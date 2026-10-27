package com.smriti.app.ai

import android.graphics.Bitmap
import android.os.SystemClock
import com.smriti.app.embed.GemmaEmbedder
import com.smriti.app.pipeline.PipelineBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the Live screen shows while Smriti helps the user find one object with the camera. */
data class GuideState(
    val target: String,
    val phase: Phase,
    /** where the object is in the camera picture (fractions), null while it is not in view */
    val box: GuideMath.Box? = null,
    /** the picture the box refers to, so the screen can map it onto the preview */
    val frameW: Int = 0,
    val frameH: Int = 0,
    val hint: String = "Searching. Move the phone slowly around.",
    /** how long the search has been running */
    val startedAt: Long = 0L,
    /** counts up each time the object comes into view after being lost, so the screen can react once */
    val foundCount: Int = 0
) {
    enum class Phase { SEARCHING, FOUND, GAVE_UP }
}

/**
 * Helps the user find one named object with the live camera, the way a person would point it out. Every few seconds the camera
 * picture is cut into overlapping tiles; EmbeddingGemma scores each tile against the name, the best tile is checked by Gemma-4
 * (a clear yes or no), and the place is shown as a ring on the preview with a hint about which way to turn. Pictures are used in
 * memory only and never stored. The scan slows down when the phone is hot.
 */
class Guide(
    private val scope: CoroutineScope,
    private val frame: () -> Bitmap?,
    private val embedder: GemmaEmbedder,
    private val brain: Brain,
    private val hot: () -> Boolean,
    /** true when the picture is too private to look at (the sensitive-scene filter) */
    private val screen: suspend (Bitmap) -> Boolean = { false }
) {
    private val _state = MutableStateFlow<GuideState?>(null)
    val state: StateFlow<GuideState?> = _state.asStateFlow()
    private var job: Job? = null

    val active: Boolean get() = _state.value != null

    fun start(target: String) {
        stop()
        val now = SystemClock.elapsedRealtime()
        _state.value = GuideState(target, GuideState.Phase.SEARCHING, startedAt = now)
        job = scope.launch { run(target, now) }
    }

    fun stop() {
        job?.cancel(); job = null
        _state.value = null
    }

    private suspend fun run(target: String, startedAt: Long) {
        android.util.Log.i("SmritiGuide", "started for $target")
        val qv = embedder.embedText("a photo of a $target") ?: run { _state.value = GuideState(target, GuideState.Phase.GAVE_UP, startedAt = startedAt, hint = "The search model is not ready."); return }
        var lost = 0
        var noFrame = 0
        var foundCount = 0
        var lastFoundAt = 0L
        var smoothed: GuideMath.Box? = null
        while (scope.isActive) {
            val now = SystemClock.elapsedRealtime()
            val cur = _state.value ?: return
            // give up after 30 s without ever finding it, or stop watching 45 s after the last sighting
            if (lastFoundAt == 0L && now - startedAt > SEARCH_MS) { _state.value = cur.copy(phase = GuideState.Phase.GAVE_UP, box = null, hint = "I cannot see $target from here."); return }
            if (lastFoundAt != 0L && now - lastFoundAt > TRACK_MS) { _state.value = null; return }
            val bmp = frame()
            if (bmp == null) { if (++noFrame % 6 == 1) android.util.Log.w("SmritiGuide", "no camera picture yet for $target"); delay(500); continue }
            val fw = bmp.width; val fh = bmp.height
            val box = try { scan(bmp, qv, target) } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (e: Throwable) {
                android.util.Log.w("SmritiGuide", "scan failed: ${e.javaClass.simpleName}: ${e.message}"); null
            } finally { bmp.recycle() }
            if (box != null) {
                lost = 0
                lastFoundAt = SystemClock.elapsedRealtime()
                // a little smoothing so the ring does not jump between scans
                val s = smoothed
                val b = if (s == null || cur.phase != GuideState.Phase.FOUND) box else GuideMath.Box(
                    (s.left + box.left) / 2, (s.top + box.top) / 2, (s.right + box.right) / 2, (s.bottom + box.bottom) / 2
                )
                smoothed = b
                if (cur.phase != GuideState.Phase.FOUND) foundCount++
                _state.value = cur.copy(phase = GuideState.Phase.FOUND, box = b, frameW = fw, frameH = fh, hint = GuideMath.hint(target, b), foundCount = foundCount)
            } else if (++lost >= 2 && cur.phase == GuideState.Phase.FOUND) {
                smoothed = null
                _state.value = cur.copy(phase = GuideState.Phase.SEARCHING, box = null, hint = "Lost it. Move back slowly.")
            }
            delay(if (hot()) 2_500L else 600L)          // a hot phone gets a rest between scans
        }
    }

    /** One look: tile scores, then Gemma-4's yes or no on the best tile. Returns the box, or null when the object is not in view. */
    private suspend fun scan(bmp: Bitmap, qv: FloatArray, target: String): GuideMath.Box? {
        if (screen(bmp)) return null
        val t0 = SystemClock.elapsedRealtime()
        val tiles = GuideMath.tiles()
        val scores = ArrayList<Float>(tiles.size)
        for (t in tiles) {
            val x = (t.left * bmp.width).toInt(); val y = (t.top * bmp.height).toInt()
            val w = ((t.right - t.left) * bmp.width).toInt().coerceAtLeast(8); val h = ((t.bottom - t.top) * bmp.height).toInt().coerceAtLeast(8)
            val crop = Bitmap.createBitmap(bmp, x.coerceAtMost(bmp.width - w), y.coerceAtMost(bmp.height - h), w, h)
            val v = try { embedder.embedImage(crop) } finally { crop.recycle() }
            scores += if (v == null) -1f else GemmaEmbedder.cosine(qv, v)
        }
        val d = GuideMath.decide(scores)
        android.util.Log.i("SmritiGuide", "$target: best %.3f tile ${d.bestTile} found=${d.found} (${SystemClock.elapsedRealtime() - t0} ms) scores=$scores".format(d.best))
        val box = d.box ?: return null
        // Gemma-4 looks at the best tile and says yes or no, so a similar-looking thing is not highlighted as the object
        if (brain.ready) {
            val tile = tiles[d.bestTile]
            val x = (tile.left * bmp.width).toInt(); val y = (tile.top * bmp.height).toInt()
            val w = ((tile.right - tile.left) * bmp.width).toInt().coerceAtLeast(8); val h = ((tile.bottom - tile.top) * bmp.height).toInt().coerceAtLeast(8)
            val crop = Bitmap.createBitmap(bmp, x.coerceAtMost(bmp.width - w), y.coerceAtMost(bmp.height - h), w, h)
            val said = try {
                brain.ask(
                    system = "You check a picture against a name. Scan the whole picture. Small, partly hidden or dark objects count. " +
                        "Reply with exactly one word: YES if \"$target\" is visible in this picture, otherwise NO.",
                    question = "Is \"$target\" visible in this picture?", image = crop, maxChars = 12, background = true
                )
            } finally { crop.recycle() }
            if (said != null && !said.trim().startsWith("yes", ignoreCase = true)) return null
            // no answer at all (Gemma was busy with the user's own question): trust a strong tile score, drop a weak one
            if (said == null && d.best < STRONG) return null
        }
        return box
    }

    private companion object {
        const val SEARCH_MS = 30_000L
        const val TRACK_MS = 45_000L
        const val STRONG = 0.42f
    }
}
