package com.smriti.app.ai

import com.smriti.app.embed.GemmaEmbedder
import com.smriti.app.memory.VisualEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Merging neighbouring matching windows into one time range. Plain Kotlin, unit-tested. */
object RangeMerge {
    class Win(val id: Long, val sessionId: Long, val start: Long, val end: Long, val score: Float)
    class Range(val sessionId: Long, val start: Long, val end: Long, val score: Float, val bestId: Long, val ids: List<Long>)

    /**
     * Windows of the same session that touch or nearly touch (gap up to [gapMs]) become one range, so five neighbouring matches
     * read as "00:03 - 00:09", not five results. A range keeps the best score and the id of its best window.
     */
    fun merge(hits: List<Win>, gapMs: Long = 3_000): List<Range> {
        val out = ArrayList<Range>()
        for ((sid, list) in hits.groupBy { it.sessionId }) {
            var cur: MutableList<Win>? = null
            for (w in list.sortedBy { it.start }) {
                val c = cur
                if (c != null && w.start - c.maxOf { it.end } <= gapMs) c += w
                else { cur?.let { out += toRange(sid, it) }; cur = mutableListOf(w) }
            }
            cur?.let { out += toRange(sid, it) }
        }
        return out.sortedByDescending { it.score }
    }

    private fun toRange(sid: Long, ws: List<Win>): Range {
        val best = ws.maxByOrNull { it.score }!!
        return Range(sid, ws.minOf { it.start }, ws.maxOf { it.end }, best.score, best.id, ws.map { it.id })
    }
}

/**
 * Natural-language search over remembered camera sessions. The question becomes a vector (EmbeddingGemma), every window of the
 * chosen sessions is scored by cosine similarity, strong matches are merged into time ranges, and Gemma-4 can then say the answer in
 * words while looking at the best picture. One shared index: a single session is just a filter on its id.
 */
class MomentSearch(private val visual: VisualMemory, private val brain: Brain) {

    private companion object {
        /** loosest similarity worth showing to Gemma-4 for a check */
        const val LOOSE_MIN = 0.28f
        /** how many of the best ranges are checked one by one (each check is about a second) */
        const val VERIFY_TOP = 3
        /** score given to a window whose object list names the thing asked for (above any picture-vector score) */
        const val NAMED_SCORE = 0.7f
    }

    class Found(val range: RangeMerge.Range, val best: VisualEntity, val windows: List<VisualEntity>)

    class Answer(val text: String?, val found: List<Found>)

    /** numbers behind the last search, for debugging the threshold */
    @Volatile var lastDebug: String = ""
    @Volatile private var floor = 0f

    /** Ranges that match [query], best first. [sessionId] limits it to one recording, null searches every session. */
    suspend fun find(query: String, sessionId: Long? = null, fromMs: Long? = null, toMs: Long? = null, k: Int = 5): List<Found> = withContext(Dispatchers.Default) {
        if (!visual.ready || query.isBlank()) return@withContext emptyList()
        val q = visual.embedder.embedText(query) ?: return@withContext emptyList()
        val everything = visual.all()
        val rows = everything.filter { (e, _) ->
            (if (sessionId != null) e.sessionId == sessionId else e.sessionId != 0L) &&
                (fromMs == null || e.timestamp >= fromMs) && (toMs == null || e.timestamp <= toMs)
        }
        if (rows.isEmpty()) return@withContext emptyList()
        val scored = rows.map { (e, v) -> e to GemmaEmbedder.cosine(q, v) }
        // Text-to-picture similarity is a weak signal on its own: its scale changes with the wording, and something small in the
        // corner scores barely above a scene without it. So the search has two steps. Step 1 is loose: windows that score above
        // what a typical window of ALL memory scores for this question. Step 2 asks Gemma-4 to look at the best picture of each of
        // the top ranges and say whether it really shows what was asked (when Gemma-4 is not available, a strict score floor is used).
        val everything2 = everything.map { (_, v) -> GemmaEmbedder.cosine(q, v) }.sorted()
        val baseline = everything2[everything2.size / 2]
        val verifying = brain.ready
        floor = if (verifying) maxOf(LOOSE_MIN, baseline) else maxOf(VisualMemory.MATCH_MIN, baseline + VisualMemory.MARGIN)
        lastDebug = "top=%.3f baseline=%.3f floor=%.3f windows=%d verify=%s".format(scored.maxOf { it.second }, baseline, floor, rows.size, verifying)
        // A moment whose written object list names what was asked is a match even when its picture vector is weak (a small or half
        // hidden object); it is trusted without a second look and ranked above vector-only matches.
        val named = scored.filter { Inventory.matches(query, it.first.caption) }.map { it.first.id }.toSet()
        val hits = scored.map { (e, s) -> e to (if (e.id in named) maxOf(s, NAMED_SCORE) else s) }
            .filter { it.second >= floor }.sortedByDescending { it.second }.take(40)
        lastDebug += " named=${named.size}"
        if (hits.isEmpty()) return@withContext emptyList()
        val byId = hits.associate { it.first.id to it.first }
        val ranges = RangeMerge.merge(hits.map { (e, s) -> RangeMerge.Win(e.id, e.sessionId, e.timestamp, if (e.endAt > e.timestamp) e.endAt else e.timestamp + SessionRules.WINDOW_MS, s) })
        var candidates = ranges.mapNotNull { r ->
            val best = byId[r.bestId] ?: return@mapNotNull null
            Found(r, best, r.ids.mapNotNull { byId[it] }.sortedBy { it.timestamp })
        }
        if (verifying) {
            val checked = candidates.take(VERIFY_TOP).filter { c -> c.best.id in named || verify(query, c.best) }
            lastDebug += " checked=${minOf(candidates.size, VERIFY_TOP)} kept=${checked.size}"
            candidates = checked
        }
        candidates.take(k)
    }

    /** Gemma-4 looks at one picture and says whether it shows what was asked. */
    suspend fun verifyWindow(question: String, window: VisualEntity): Boolean = verify(question, window)

    private suspend fun verify(question: String, window: VisualEntity): Boolean {
        val image = runCatching { com.smriti.app.security.SecureFiles.decodeBitmap(visual.fileFor(window.thumb)) }.getOrNull() ?: return false
        return try {
            val said = brain.ask(
                system = "You check a picture against a question. First scan the WHOLE picture (foreground, background, corners, shelves, surfaces, hands, screens). " +
                    "Small, partly hidden, dark, blurry or distant objects all count, and so does a screen or photo that shows the thing. " +
                    "Reply with exactly one word: YES if the thing the question asks about is visible anywhere in the picture, even partly, otherwise NO.",
                question = "Question: $question\nIs what is asked about visible anywhere in this picture?", image = image, maxChars = 12
            )
            said?.trim()?.startsWith("yes", ignoreCase = true) == true
        } finally { image.recycle() }
    }
    /**
     * The answer in plain words, from the best matching picture and the matching ranges. [relative] gives each range's position
     * text ("00:03 - 00:07"). Returns null text when Gemma-4 is not available; the caller then shows the ranges alone.
     */
    suspend fun answer(question: String, found: List<Found>, describe: (Found) -> String): Answer {
        if (found.isEmpty() || !brain.ready) return Answer(null, found)
        val top = found.first()
        val file = visual.fileFor(top.best.thumb)
        val image = runCatching { com.smriti.app.security.SecureFiles.decodeBitmap(file) }.getOrNull()
        val facts = found.take(3).joinToString("\n") { f ->
            "- ${describe(f)}: notes then: ${f.best.caption.ifBlank { "none" }}" + (f.best.heard.takeIf { it.isNotBlank() }?.let { "; heard: $it" } ?: "")
        }
        val said = try {
            brain.ask(
                system = "You are Smriti, the memory of the user's camera. The user asks about a recording they made. The attached picture is a blurred still from the best matching moment, " +
                    "and each moment below comes with an inventory of every object seen in it (\"Objects:\"). Scan the whole picture and read the whole inventory before you answer, " +
                    "so no object is missed. Answer exactly what was asked, completely: name every object that matches (not only the first), say where in the picture each is, " +
                    "and say when it happens using the time ranges below. Use at most three short plain sentences unless a list was asked for. " +
                    "Say only what the picture and the notes show. If they do not show it, say you did not see it.\nMATCHING MOMENTS:\n$facts",
                question = question, image = image, forUser = true
            )
        } finally { image?.recycle() }
        return Answer(said, found)
    }
}
