package com.smriti.app.privacy

import android.graphics.Bitmap
import android.os.SystemClock
import com.smriti.app.ai.Brain
import com.smriti.app.embed.GemmaEmbedder
import com.smriti.app.pipeline.PipelineBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The sensitive-scene filter. A picture that looks undressed, nude or like bathing is dropped: it is not remembered (no vector, no
 * picture, no caption, no object list, no sound), it is not sent to Gemma-4 to be described, and the live guide does not scan it.
 *
 * Two steps. First a cheap look: the picture's meaning vector (already computed for memory, so it costs nothing extra there) is
 * compared with written descriptions of risky and of ordinary scenes. Only a picture that looks clearly more like the risky ones is
 * a candidate. Then Gemma-4 looks at the candidate and answers yes or no; a refusal or no answer counts as yes, because when in
 * doubt the picture is dropped. After a drop, everything pauses for a few seconds and then checks again.
 *
 * Limits, stated plainly: this is a best-effort safeguard, not a guarantee. The thresholds were set on ordinary pictures, and false
 * alarms (swimwear, bare arms) are accepted on purpose. Nothing about a dropped picture is kept except a counter.
 */
class PrivacyGuard(
    private val embedder: GemmaEmbedder,
    private val brain: Brain,
    private val enabled: () -> Boolean
) {
    data class Status(val blockedUntil: Long = 0L, val dropped: Int = 0)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** margins at which a picture is a candidate / dropped without asking (set from measurements on ordinary pictures) */
    @Volatile var checkMargin = DEFAULT_CHECK
    @Volatile var blockMargin = DEFAULT_BLOCK

    private class Anchors(val risky: List<FloatArray>, val ordinary: List<FloatArray>)
    @Volatile private var anchors: Anchors? = null

    /** true while the filter is holding everything after a drop */
    val blocked: Boolean get() = enabled() && SystemClock.elapsedRealtime() < _status.value.blockedUntil

    val droppedCount: Int get() = _status.value.dropped

    private fun anchors(): Anchors? {
        anchors?.let { return it }
        if (embedder.state !is GemmaEmbedder.State.Ready) return null
        val r = SensitiveMath.RISKY.mapNotNull { embedder.embedText(it) }
        val o = SensitiveMath.ORDINARY.mapNotNull { embedder.embedText(it) }
        if (r.size != SensitiveMath.RISKY.size || o.size != SensitiveMath.ORDINARY.size) return null
        return Anchors(r, o).also { anchors = it }
    }

    /** how much more [v] looks like a risky scene than an ordinary one, or null while the search model is not ready */
    fun marginOf(v: FloatArray): Float? {
        val a = anchors() ?: return null
        val risky = a.risky.maxOf { GemmaEmbedder.cosine(v, it) }
        val ordinary = a.ordinary.maxOf { GemmaEmbedder.cosine(v, it) }
        return SensitiveMath.margin(risky, ordinary)
    }

    /**
     * True when the picture must be dropped. [v] is the picture's meaning vector; [picture] is the (face-blurred) picture, looked at
     * by Gemma-4 only when the vector makes it a candidate. Starts the pause when it drops something.
     */
    suspend fun isSensitive(v: FloatArray, picture: Bitmap): Boolean {
        if (!enabled()) return false
        val m = marginOf(v) ?: return false
        return when (SensitiveMath.verdict(m, checkMargin, blockMargin)) {
            SensitiveMath.Verdict.CLEAR -> false
            SensitiveMath.Verdict.BLOCK -> { hold("looks undressed (margin %.3f)".format(m)); true }
            SensitiveMath.Verdict.CHECK -> confirm(picture).also { if (it) hold("confirmed by Gemma-4 (margin %.3f)".format(m)) }
        }
    }

    /** the same for a picture that has no vector yet (a live question, a guide scan): embeds it first */
    suspend fun screenFrame(picture: Bitmap): Boolean {
        if (!enabled()) return false
        if (blocked) return true
        val small = if (maxOf(picture.width, picture.height) > 512) {
            val s = 512f / maxOf(picture.width, picture.height)
            Bitmap.createScaledBitmap(picture, (picture.width * s).toInt(), (picture.height * s).toInt(), true)
        } else picture
        val v = try { embedder.embedImage(small) } finally { if (small !== picture) small.recycle() } ?: return false
        return isSensitive(v, picture)
    }

    private suspend fun confirm(picture: Bitmap): Boolean {
        if (!brain.ready) return true                       // cannot ask: when in doubt, drop
        val reply = brain.ask(
            system = "You are a content safety checker inside a private home assistant. Look at the whole picture. " +
                "Reply with exactly one word: YES if it shows a person who is naked, topless, undressed, in underwear or being bathed, or any sexual content. Otherwise reply NO.",
            question = "Is this picture sensitive?", image = picture, maxChars = 24, background = true
        )
        return SensitiveMath.answerIsSensitive(reply)
    }

    /** debug: the margins of many stored pictures, as percentiles and the highest few (to set thresholds on ordinary pictures) */
    fun report(vectors: List<Pair<Long, FloatArray>>): String {
        val ms = vectors.mapNotNull { (id, v) -> marginOf(v)?.let { id to it } }
        if (ms.isEmpty()) return "no margins (search model not ready?)"
        val values = ms.map { it.second }
        val top = ms.sortedByDescending { it.second }.take(6).joinToString { "#${it.first}:%.3f".format(it.second) }
        return "n=${ms.size} min=%.3f p50=%.3f p90=%.3f p99=%.3f p99.9=%.3f max=%.3f | top: %s | check=%.3f block=%.3f".format(
            values.min(), SensitiveMath.percentile(values, 50.0), SensitiveMath.percentile(values, 90.0), SensitiveMath.percentile(values, 99.0),
            SensitiveMath.percentile(values, 99.9), values.max(), top, checkMargin, blockMargin
        )
    }

    /** debug: behaves as if a sensitive picture had just been dropped (to test the pause end to end without any such picture) */
    fun forceHold() = hold("forced by a debug command")

    private fun hold(why: String) {
        val s = _status.value
        _status.value = Status(SystemClock.elapsedRealtime() + HOLD_MS, s.dropped + 1)
        PipelineBus.info("privacy", "sensitive picture dropped: $why")       // the reason only, never the picture
    }

    companion object {
        const val HOLD_MS = 8_000L
        // measured on 907 ordinary pictures from the phone: median -0.009, 99th percentile 0.036, maximum 0.066.
        // Gemma-4 looks at anything above the 99th percentile; above 1.5 times the highest ordinary picture the picture is dropped unasked.
        const val DEFAULT_CHECK = 0.035f
        const val DEFAULT_BLOCK = 0.10f
    }
}
