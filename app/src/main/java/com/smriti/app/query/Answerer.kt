package com.smriti.app.query

import com.smriti.app.memory.MemoryRepository
import com.smriti.app.scene.Scene
import com.smriti.app.scene.SceneBuilder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Builds every answer ONLY from rows retrieved from memory, so it cannot invent things it never saw.
 * Questions about the past are answered from SCENES (time span, place, what was in it, how much moved and interacted,
 * and an importance score), the way SceneIQ describes a moment, but computed from text notes on the phone.
 */
class Answerer(
    private val repo: MemoryRepository,
    private val lang: () -> String = { "en" },
    private val poseNow: () -> com.smriti.app.spatial.Pose? = { null },
    private val onTarget: (com.smriti.app.spatial.Point3) -> Unit = {}
) {

    private val hindi: Boolean get() = Localizer.isHindi(lang())

    private val clock = SimpleDateFormat("h:mm a", Locale.getDefault())

    suspend fun answer(intent: Intent, nowMs: Long = System.currentTimeMillis()): String = when (intent) {
        is Intent.Live -> "Looking now..."  // handled by the engine, which has the camera frame
        is Intent.Forget -> {
            val n = repo.forget(nowMs - intent.minutes * 60_000L)
            if (hindi) Localizer.forgot(n, intent.minutes)
            else if (n == 0) "There was nothing to forget from the last ${intent.minutes} minutes."
            else "Done. I erased $n memories from the last ${intent.minutes} minutes."
        }
        is Intent.Where -> where(intent.item, nowMs)
        is Intent.Range -> range(intent, nowMs)
        is Intent.Highlight -> highlight(nowMs)
        is Intent.InPlace -> inPlace(intent.place, nowMs)
        is Intent.Help -> if (hindi) Localizer.help() else "Hi, I'm Smriti. I watch through the camera and remember it as text. Ask me what I see, where something is, like where did you see my keys, or what happened in the last 10 minutes."
        is Intent.Search -> search(intent.text, nowMs)
    }

    // ---- "where did you see my mouse" --------------------------------------------------------------------------------

    private suspend fun where(item: String, nowMs: Long): String {
        val s = repo.lastSeen(item) ?: return if (hindi) Localizer.notSeen(item) else "I haven't seen $item yet. I only know what the camera has shown me while Smriti was on."
        if (hindi) return whereHindi(item, s, nowMs)
        val mine = "your ${item.trim()}"
        if (s.viaText) return "I haven't seen $mine itself, but I last read a matching word on something${placeText(s.zone, "")} ${whenText(s.at, nowMs)}."
        val head = if (s.bySynonym) "I haven't seen $mine by name, but I last saw a ${s.matched} ${whenText(s.at, nowMs)}${placeText(s.zone, s.context)}. That may be it."
        else "I last saw $mine ${whenText(s.at, nowMs)}${placeText(s.zone, s.context)}."
        val before = repo.earlierSighting(item, s.at)
        val first0 = if (before == null) head else "$head Before that, ${whenText(before.at, nowMs)}${placeText(before.zone, "")}."
        val first = first0 + guidance(s.point, nowMs - s.at)

        // the scene it was part of
        val needle = MemoryRepository.tokens(item)
        val alts = listOf(needle) + MemoryRepository.synonymsOf(needle).map { listOf(it) }
        val scene = repo.scenes(nowMs - 24 * 3_600_000L, nowMs)
            .lastOrNull { sc -> sc.objects.any { o -> alts.any { a -> MemoryRepository.itemMatches(o, a) } } }
        return if (scene == null) first else first + "\n" + sceneBlock(scene)
    }

    /**
     * Spatial navigation: from where you are looking now to where the thing was ("about 2 m away, 40 degrees to your left").
     * Only said when the sighting is recent enough for the spot to still mean something, and the direction only when the
     * phone has a compass. Also hands the target to the Live screen so it can show an arrow.
     */
    private fun guidance(p: com.smriti.app.spatial.Point3?, ageMs: Long): String {
        if (p == null || ageMs > 30 * 60_000L) return ""
        onTarget(p)
        val pose = poseNow() ?: return ""
        val r = com.smriti.app.spatial.Spatial3D.relative(p, pose)
        if (hindi) {
            val parts = ArrayList<String>()
            parts += "लगभग ${"%.1f".format(r.distance)} मीटर दूर"
            if (r.headingValid) {
                val a = kotlin.math.abs(r.turnDeg).toInt()
                parts += when { a < 15 -> "सामने"; a > 135 -> "आपके पीछे"; r.turnDeg < 0 -> "$a° बाईं ओर"; else -> "$a° दाईं ओर" }
            }
            return " ${parts.joinToString(", ")} (अनुमान)।"
        }
        return " It is ${com.smriti.app.spatial.Spatial3D.describe(r)} (estimate)."
    }

    private suspend fun whereHindi(item: String, s: MemoryRepository.Sighting, nowMs: Long): String {
        val name = MultiLang.nativeName(item.trim(), "hi")
        val place = Localizer.placeText(s.zone, s.context)
        val head = when {
            s.viaText -> "मुझे $name नहीं दिखा, लेकिन एक चीज़ पर मिलता-जुलता शब्द ${Localizer.whenText(s.at, nowMs)} पढ़ा था।"
            s.bySynonym -> "मैंने $name नाम से नहीं देखा, लेकिन ${MultiLang.nativeName(s.matched, "hi")} ${Localizer.whenText(s.at, nowMs)}$place दिखा था। शायद वही हो।"
            else -> "$name आख़िरी बार ${Localizer.whenText(s.at, nowMs)}$place देखा गया।"
        }
        val before = repo.earlierSighting(item, s.at)
        val first0 = if (before == null) head else "$head इससे पहले, ${Localizer.whenText(before.at, nowMs)}${Localizer.placeText(before.zone, "")}।"
        val first = first0 + guidance(s.point, nowMs - s.at)
        val needle = MemoryRepository.tokens(item)
        val alts = listOf(needle) + MemoryRepository.synonymsOf(needle).map { listOf(it) }
        val scene = repo.scenes(nowMs - 24 * 3_600_000L, nowMs)
            .lastOrNull { sc -> sc.objects.any { o -> alts.any { a -> MemoryRepository.itemMatches(o, a) } } }
        return if (scene == null) first else first + "\n" + Localizer.sceneBlock(scene.title, scene.start, scene.end, scene.durationMs / 60_000L, scene.importance, SceneBuilder.level(scene.motion), SceneBuilder.level(scene.interaction), scene.objects)
    }

    /**
     * Anything the router could not place: first look for a thing Smriti has seen that the sentence names (so
     * "my keyboard please" still works), then for matching notes, and only then say what it can answer.
     */
    private suspend fun search(text: String, nowMs: Long): String {
        for (word in MemoryRepository.tokens(text)) {
            if (word.length > 2 && repo.lastSeen(word) != null) return where(word, nowMs)
        }
        val hits = repo.search(text)
        if (hits.isNotEmpty()) return hits.joinToString("\n") { "${clock.format(Date(it.timestamp))}${zoneText(it.zone)}: ${it.caption}" }
        if (hindi) return Localizer.cantAnswer(text)
        return "I heard \"${text.take(80)}\" but I can't answer that from what I've seen. Try: what do you see, where did you see my keys, or what happened in the last 10 minutes."
    }

    // ---- scenes ----------------------------------------------------------------------------------------------------------

    /** Scene, span, importance, motion, interaction, objects: the same fields SceneIQ reports for a moment. */
    private fun sceneBlock(s: Scene): String {
        val things = s.objects.take(6).joinToString(", ")
        return "Scene: ${s.title}\n" +
            "Time: ${clock.format(Date(s.start))} to ${clock.format(Date(s.end))} (${span(s.durationMs)})\n" +
            "Importance: ${"%.2f".format(s.importance)}   Motion: ${SceneBuilder.level(s.motion)}   Interaction: ${SceneBuilder.level(s.interaction)}\n" +
            "Detected objects: $things"
    }

    private fun span(ms: Long) = if (ms < 60_000L) "under a minute" else "${ms / 60_000L} min"

    private suspend fun range(r: Intent.Range, nowMs: Long): String {
        val all = repo.scenes(r.fromMs, r.toMs)
        // a single glance (one note, a few seconds) is not worth calling a scene when there is something longer
        val scenes = all.filter { it.notes >= 2 || it.durationMs >= 15_000L }.ifEmpty { all }
        if (scenes.isEmpty()) return if (hindi) "${r.label} की कोई याद नहीं है।" else "I have no memories from ${r.label}."
        val top = scenes.maxByOrNull { it.importance }!!
        if (hindi) {
            val sbh = StringBuilder("${scenes.size} दृश्य मिले। सबसे महत्वपूर्ण: ${top.title}, ${Localizer.clock(top.start)}।")
            scenes.takeLast(5).forEach { sbh.append("\n${Localizer.clock(it.start)} से ${Localizer.clock(it.end)}  ${it.title}  (महत्व ${"%.2f".format(it.importance)}, गति ${Localizer.level(SceneBuilder.level(it.motion))})") }
            return sbh.toString()
        }
        val sb = StringBuilder("${scenes.size} ${if (scenes.size == 1) "scene" else "scenes"} from ${r.label}. Most important: ${top.title} at ${clock.format(Date(top.start))}.")
        scenes.takeLast(5).forEach {
            sb.append("\n${clock.format(Date(it.start))} to ${clock.format(Date(it.end))}  ${it.title}  (importance ${"%.2f".format(it.importance)}, motion ${SceneBuilder.level(it.motion).lowercase()})")
        }
        return sb.toString()
    }

    private suspend fun highlight(nowMs: Long): String {
        val scenes = repo.scenes(nowMs - 24 * 3_600_000L, nowMs)
        val top = scenes.maxByOrNull { it.importance } ?: return if (hindi) "अभी तक कुछ ख़ास नहीं देखा।" else "I haven't seen anything worth ranking yet."
        if (hindi) return "सबसे महत्वपूर्ण पल ${Localizer.whenText(top.start, nowMs)} था।\n" + Localizer.sceneBlock(top.title, top.start, top.end, top.durationMs / 60_000L, top.importance, SceneBuilder.level(top.motion), SceneBuilder.level(top.interaction), top.objects)
        return "The most important moment I saw was ${whenText(top.start, nowMs)}.\n" + sceneBlock(top)
    }

    private suspend fun inPlace(place: String, nowMs: Long): String {
        val map = repo.homeMap()
        val key = map.keys.firstOrNull { it.contains(place, ignoreCase = true) || place.contains(it, ignoreCase = true) }
            ?: return answer(Intent.Search(place), nowMs)
        val items = map.getValue(key).take(8)
        if (hindi) return Localizer.inPlace(key, items.map { it.item to it.lastSeen }, nowMs)
        return "In the $key I saw: " + items.joinToString(", ") { "${it.item} (${ago(nowMs - it.lastSeen)})" } + "."
    }

    // ---- wording -----------------------------------------------------------------------------------------------------------

    /** ", on the table, next to the cup, in the kitchen" (each part only when known). */
    private fun placeText(zone: String, how: String): String {
        val rel = how.takeIf { it.isNotBlank() && !it.startsWith("I see") && !it.startsWith("Nothing") }
        val room = if (zone == "somewhere" || zone.isBlank()) null else if (zone in ROOMS) "in the $zone" else "near the $zone"
        return listOfNotNull(rel, room).joinToString(", ", prefix = if (rel != null || room != null) ", " else "")
    }

    private fun whenText(at: Long, nowMs: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        val dayDiff = (startOfDay(nowMs) - startOfDay(at)) / 86_400_000L
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val part = when { hour < 5 -> "at night"; hour < 12 -> "morning"; hour < 17 -> "afternoon"; hour < 21 -> "evening"; else -> "night" }
        val clockText = clock.format(Date(at)).lowercase()
        return when {
            nowMs - at < 3_600_000L -> "${ago(nowMs - at)} (at $clockText)"
            dayDiff == 0L -> (if (part == "at night") "tonight" else "this $part") + " at $clockText (${ago(nowMs - at)})"
            dayDiff == 1L -> (if (part == "at night") "last night" else "yesterday $part") + " at $clockText"
            else -> "on ${SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(at))} at $clockText"
        }
    }

    private fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun zoneText(zone: String) = if (zone == "somewhere") "" else " near the $zone"

    companion object {
        private val ROOMS = setOf("kitchen", "bedroom", "bathroom", "living room", "dining room", "dining area", "office", "workspace", "garage", "garden", "classroom", "restaurant", "street", "car")

        fun ago(ms: Long): String {
            val s = ms / 1000
            return when {
                s < 45 -> "just now"
                s < 90 -> "a minute ago"
                s < 3600 -> "${s / 60} minutes ago"
                s < 7200 -> "about an hour ago"
                s < 86_400 -> "${s / 3600} hours ago"
                else -> "${s / 86_400} days ago"
            }
        }
    }
}
