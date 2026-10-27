package com.smriti.app.perception

import com.smriti.app.models.DetectedObject
import com.smriti.app.models.Label

/**
 * Turns one frame's detections into words about WHERE things are, so a later question like "where did you see my
 * pills" can be answered with a place and not just a time. Only names and relations are kept, never pixels.
 *
 *   where: object -> "on the table, next to the cup"      (from the boxes of the same frame)
 *   room:  "kitchen", "bedroom", "workspace"...           (from the scene labels and the furniture seen)
 */
object Spatial {

    /** Things other objects rest on or sit in. COCO names. */
    private val SURFACES = setOf("dining table", "desk", "couch", "bed", "chair", "bench", "sink", "oven", "microwave", "refrigerator", "toilet", "suitcase")

    private val PRETTY = mapOf("dining table" to "table", "cell phone" to "phone", "refrigerator" to "fridge", "couch" to "sofa", "tv" to "TV", "potted plant" to "plant")

    fun name(label: String) = PRETTY[label] ?: label

    /** A box without android.graphics, so the geometry can be unit-tested on the JVM. Coordinates are 0..1. */
    data class Box(val label: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun relations(boxes: List<DetectedObject>): Map<String, String> =
        relationsOf(boxes.map { Box(it.label, it.box.left, it.box.top, it.box.right, it.box.bottom) })

    fun relationsOf(boxes: List<Box>): Map<String, String> {
        val real = boxes.filter { it.label != "object" }
        val out = LinkedHashMap<String, String>()
        for (b in real) {
            if (out.containsKey(b.label)) continue
            val others = real.filter { it !== b && it.label != b.label }
            val cx = (b.left + b.right) / 2f
            val cy = (b.top + b.bottom) / 2f

            // resting on a surface: centred over it, with its bottom edge near the surface's top
            val on = others.filter { it.label in SURFACES }.firstOrNull { s ->
                cx in s.left..s.right && b.bottom in (s.top - 0.06f)..(s.top + (s.bottom - s.top) * 0.6f) && cy < (s.top + s.bottom) / 2f
            }
            val parts = ArrayList<String>(2)
            if (on != null) parts += "on the ${name(on.label)}"

            val near = others.filter { it !== on }.minByOrNull { dist(cx, cy, it) }?.takeIf { dist(cx, cy, it) < 0.30f }
            if (near != null) parts += "next to the ${name(near.label)}"
            // nothing close: still say what else was in view, so the answer is never just a time
            if (parts.isEmpty()) others.minByOrNull { dist(cx, cy, it) }?.let { parts += "with the ${name(it.label)} in view" }
            if (parts.isNotEmpty()) out[b.label] = parts.joinToString(", ")
        }
        return out
    }

    /** A room-level guess from scene labels first (ML Kit), then from the furniture the detector saw. */
    fun room(labels: List<Label>, boxes: List<DetectedObject>): String = roomOf(labels.map { it.text }, boxes.map { it.label } + labels.map { it.text.lowercase() })

    fun roomOf(labelTexts: List<String>, objectLabels: List<String>): String {
        val l = labelTexts.map { it.lowercase() }
        for (r in listOf("kitchen", "bedroom", "bathroom", "living room", "dining room", "office", "garage", "garden", "classroom", "restaurant", "street", "car")) {
            if (l.any { it == r || it.contains(r) }) return r
        }
        val o = objectLabels.toSet()
        return when {
            "bed" in o -> "bedroom"
            "toilet" in o -> "bathroom"
            o.any { it in setOf("refrigerator", "oven", "microwave", "sink") } -> "kitchen"
            "couch" in o && "tv" in o -> "living room"
            o.any { it in setOf("laptop", "keyboard", "mouse") } -> "workspace"
            "dining table" in o -> "dining area"
            else -> ""
        }
    }

    private fun dist(cx: Float, cy: Float, o: Box): Float {
        val ox = (o.left + o.right) / 2f
        val oy = (o.top + o.bottom) / 2f
        return Math.hypot((cx - ox).toDouble(), (cy - oy).toDouble()).toFloat()
    }

    /** Words printed on medicine strips and bottles; the ML models have no "pills" class, so text is the evidence. */
    private val MEDICINE = Regex("\\b(tablets?|capsules?|medicine|medication|paracetamol|ibuprofen|dosage|syrup|rx|strip)\\b|\\bmg\\b", RegexOption.IGNORE_CASE)
    fun looksLikeMedicine(ocrText: String) = MEDICINE.containsMatchIn(ocrText)
}
