package com.smriti.app.spatial

/**
 * Gives every object a stable name across frames ("cup#1", "cup#2"), even while the phone moves: objects are matched by
 * where they are in the ROOM (direction in world angles), not by where they are in the picture, which is why the
 * phone's orientation matters. Without a compass the angles are camera-relative, so matching only holds for a moment.
 */
class ObjectTracker(private val maxAgeMs: Long = 10_000L, private val matchDeg: Float = 12f) {

    class Track(val id: Int, val label: String, var point: Point3, var firstSeen: Long, var lastSeen: Long, var hits: Int = 1) {
        val name get() = "$label#$id"
    }

    private val tracks = ArrayList<Track>()
    private var next = 1

    /** Matches this frame's detections to tracks and returns the tracks seen in this frame. */
    @Synchronized
    fun update(points: List<Point3>, now: Long): List<Track> {
        tracks.removeAll { now - it.lastSeen > maxAgeMs }
        val used = HashSet<Track>()
        val seen = ArrayList<Track>()
        for (p in points) {
            val best = tracks.filter { it.label == p.label && it !in used }
                .minByOrNull { Spatial3D.angleBetween(it.point.azimuthDeg, it.point.elevationDeg, p.azimuthDeg, p.elevationDeg) }
            val close = best != null && Spatial3D.angleBetween(best.point.azimuthDeg, best.point.elevationDeg, p.azimuthDeg, p.elevationDeg) < matchDeg
            val t = if (close) best!!.also { it.point = p; it.lastSeen = now; it.hits++ } else Track(next++, p.label, p, now, now).also { tracks += it }
            used += t
            seen += t
        }
        return seen
    }

    @Synchronized fun active(now: Long): List<Track> = tracks.filter { now - it.lastSeen <= maxAgeMs }.toList()

    @Synchronized fun reset() { tracks.clear(); next = 1 }
}
