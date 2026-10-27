package com.smriti.app.memory

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/**
 * "3D-lite" spatial memory without any reconstruction: the user teaches a few named places
 * ("desk", "kitchen"); each is stored as a 48-bin colour histogram (never an image), and the
 * current frame is matched against them. Cheap enough to run on every accepted frame.
 */
class ZoneTracker(private val dao: ZoneDao) {

    private var zones: List<Pair<String, FloatArray>> = emptyList()

    suspend fun load() {
        zones = dao.all().map { it.name to parse(it.signature) }
    }

    suspend fun learn(name: String, frame: Bitmap) {
        dao.insert(ZoneEntity(name = name.trim().lowercase(), signature = signature(frame).joinToString(",")))
        load()
    }

    val knownZones: List<String> get() = zones.map { it.first }.distinct()

    fun classify(frame: Bitmap): String {
        if (zones.isEmpty()) return UNKNOWN
        val sig = signature(frame)
        var best = UNKNOWN
        var bestD = Float.MAX_VALUE
        for ((name, ref) in zones) {
            val d = distance(sig, ref)
            if (d < bestD) { bestD = d; best = name }
        }
        return if (bestD <= MATCH_THRESHOLD) best else UNKNOWN
    }

    private fun signature(src: Bitmap): FloatArray {
        val small = Bitmap.createScaledBitmap(src, 64, 48, true)
        val px = IntArray(64 * 48)
        small.getPixels(px, 0, 64, 0, 0, 64, 48)
        if (small !== src) small.recycle()
        val h = FloatArray(BINS * 3)
        for (c in px) {
            h[Color.red(c) * BINS / 256]++
            h[BINS + Color.green(c) * BINS / 256]++
            h[2 * BINS + Color.blue(c) * BINS / 256]++
        }
        val n = px.size.toFloat()
        for (i in h.indices) h[i] /= n
        return h
    }

    /** Mean per-channel L1 distance, range 0..2. */
    private fun distance(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += abs(a[i] - b[i])
        return s / 3f
    }

    private fun parse(s: String) = s.split(",").map { it.toFloat() }.toFloatArray()

    companion object {
        const val UNKNOWN = "somewhere"
        private const val BINS = 16
        private const val MATCH_THRESHOLD = 0.45f
    }
}
