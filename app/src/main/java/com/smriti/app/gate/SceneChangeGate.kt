package com.smriti.app.gate

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

data class GateDecision(val accepted: Boolean, val score: Float, val reason: String)

/** Pure decision logic, separated from bitmaps so it can be unit tested on the JVM. */
object GateMath {

    /** Mean absolute difference of two equal-length grayscale thumbnails, 0..1. */
    fun changeScore(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "thumbnail sizes differ" }
        if (a.isEmpty()) return 0f
        var sum = 0f
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum / a.size
    }

    fun decide(
        hasReference: Boolean,
        score: Float,
        sinceLastAcceptedMs: Long,
        minIntervalMs: Long,
        heartbeatMs: Long,
        threshold: Float
    ): GateDecision = when {
        !hasReference -> GateDecision(true, 1f, "first")
        sinceLastAcceptedMs < minIntervalMs -> GateDecision(false, score, "cooldown")
        score >= threshold -> GateDecision(true, score, "changed")
        sinceLastAcceptedMs >= heartbeatMs -> GateDecision(true, score, "heartbeat")
        else -> GateDecision(false, score, "static")
    }
}

/**
 * Cheap CPU gate (about 1 ms): decides whether a frame is worth sending to the expensive models.
 * Compares a 32x24 grayscale thumbnail against the last ACCEPTED frame (not the previous frame),
 * so slow pans still accumulate into a change. A heartbeat forces an occasional capture of a static scene.
 * Works on the UN-rotated frame: rotation does not change whether the scene changed.
 */
class SceneChangeGate(
    private val changeThreshold: Float = 0.08f,
    private val heartbeatMs: Long = 20_000L
) {
    private var reference: FloatArray? = null
    private var lastAcceptedAt = 0L

    @Synchronized
    fun evaluate(frame: Bitmap, nowMs: Long, minIntervalMs: Long): GateDecision {
        val gray = thumbnail(frame)
        val ref = reference
        val score = if (ref == null) 1f else GateMath.changeScore(gray, ref)
        val d = GateMath.decide(
            hasReference = ref != null, score = score,
            sinceLastAcceptedMs = nowMs - lastAcceptedAt, minIntervalMs = minIntervalMs,
            heartbeatMs = heartbeatMs, threshold = changeThreshold
        )
        if (d.accepted) {
            reference = gray
            lastAcceptedAt = nowMs
        }
        return d
    }

    @Synchronized
    fun reset() {
        reference = null
        lastAcceptedAt = 0L
    }

    val threshold: Float get() = changeThreshold

    private fun thumbnail(src: Bitmap): FloatArray {
        val small = Bitmap.createScaledBitmap(src, W, H, true)
        val px = IntArray(W * H)
        small.getPixels(px, 0, W, 0, 0, W, H)
        if (small !== src) small.recycle()
        return FloatArray(px.size) { i ->
            val c = px[i]
            (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
        }
    }

    private companion object {
        const val W = 32
        const val H = 24
    }
}
