package com.smriti.app.scene

import kotlin.math.max
import kotlin.math.min

/**
 * Temporal scene intelligence, in the spirit of SceneIQ, but running on the phone and over text notes only:
 * consecutive notes that describe the same surroundings are merged into a SCENE with a time span, the things in it,
 * how much moved, how much interacted, and an importance score used to rank scenes.
 *
 * No images are involved: the input is the notes Smriti already keeps.
 */
data class NoteRow(
    val start: Long,
    val end: Long,
    val objects: Set<String>,
    val zone: String,
    val motion: Float,
    val density: Float,
    val people: Int,
    val hasText: Boolean
)

data class Scene(
    val start: Long,
    val end: Long,
    val place: String,
    val title: String,
    /** most frequent first */
    val objects: List<String>,
    val motion: Float,
    val interaction: Float,
    val importance: Float,
    val notes: Int
) {
    val durationMs: Long get() = max(0L, end - start)
    fun contains(item: Set<String>) = objects.any { it in item }
}

object SceneBuilder {

    const val GAP_MS = 120_000L
    private const val SIMILARITY = 0.25f
    const val UNKNOWN = "somewhere"

    fun build(rows: List<NoteRow>): List<Scene> {
        if (rows.isEmpty()) return emptyList()
        val sorted = rows.sortedBy { it.start }
        val groups = ArrayList<MutableList<NoteRow>>()
        var union = HashSet<String>()
        for (r in sorted) {
            val current = groups.lastOrNull()
            val startNew = when {
                current == null -> true
                r.start - current.maxOf { it.end } > GAP_MS -> true
                zoneChanged(current.last().zone, r.zone) -> true
                core(r).isNotEmpty() && union.isNotEmpty() && jaccard(union, core(r)) < SIMILARITY -> true
                else -> false
            }
            if (startNew) { groups += mutableListOf(r); union = HashSet(core(r)) } else { current!!.add(r); union.addAll(core(r)) }
        }
        return groups.map(::summarise)
    }

    /** Flickering generic labels ("metal", "wheel") must not decide whether two moments are the same scene. */
    private fun core(r: NoteRow): Set<String> = r.objects - VAGUE

    private fun summarise(g: List<NoteRow>): Scene {
        val start = g.minOf { it.start }
        val end = g.maxOf { max(it.end, it.start) }
        val freq = g.flatMap { it.objects }.groupingBy { it }.eachCount()
        val objects = freq.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }
        val place = g.map { it.zone }.filter { it != UNKNOWN && it.isNotBlank() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: UNKNOWN

        val motion = g.map { it.motion }.average().toFloat().coerceIn(0f, 1f)
        val peopleBoost = if (g.any { it.people > 0 }) 0.25f else 0f
        val interaction = (g.map { it.density }.average().toFloat() + peopleBoost).coerceIn(0f, 1f)
        val novelty = min(1f, freq.size / 8f)
        val duration = min(1f, (end - start) / 180_000f)
        val text = if (g.any { it.hasText }) 1f else 0f
        val importance = (0.35f * motion + 0.30f * interaction + 0.20f * novelty + 0.10f * duration + 0.05f * text).coerceIn(0f, 1f)

        return Scene(start, end, place, title(place, objects), objects, motion, interaction, round2(importance), g.size)
    }

    private fun title(place: String, objects: List<String>): String {
        val things = objects.filter { it !in VAGUE }.take(2)
        val what = when (things.size) { 0 -> "" ; 1 -> things[0]; else -> "${things[0]} and ${things[1]}" }
        return when {
            place != UNKNOWN && what.isNotEmpty() -> "${place.replaceFirstChar { it.uppercase() }}: $what"
            place != UNKNOWN -> "${place.replaceFirstChar { it.uppercase() }} scene"
            what.isNotEmpty() -> "Scene with $what"
            else -> "Quiet scene"
        }
    }

    fun level(x: Float): String = when { x < 0.34f -> "Low"; x < 0.67f -> "Medium"; else -> "High" }

    private fun zoneChanged(a: String, b: String) = a != UNKNOWN && b != UNKNOWN && a != b

    private fun jaccard(a: Set<String>, b: Set<String>): Float {
        val inter = a.count { it in b }
        val uni = a.size + b.size - inter
        return if (uni == 0) 1f else inter.toFloat() / uni
    }

    private fun round2(x: Float) = Math.round(x * 100f) / 100f

    /** abstract labels that say nothing useful about a moment (also hidden from the Memory chips) */
    val VAGUE = setOf("textile", "needlework", "space", "paper", "toilet", "monochrome photography", "darkness", "sky", "cloud","metal", "monochrome", "black-and-white", "material property", "font", "rectangle", "circle", "line", "tints and shades", "electric blue", "azure", "gas", "technology", "pattern", "wheel", "tire", "auto part", "room", "floor", "wall")
}
