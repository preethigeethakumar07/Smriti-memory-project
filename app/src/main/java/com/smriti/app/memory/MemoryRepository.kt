package com.smriti.app.memory

import com.smriti.app.perception.Perception
import kotlinx.coroutines.flow.Flow

class MemoryRepository(private val db: AppDatabase) {

    private val memories = db.memoryDao()
    private val ledger = db.ledgerDao()

    val count: Flow<Int> = memories.count()

    fun recentFlow(limit: Int = 300): Flow<List<MemoryEntity>> = memories.recentFlow(limit)

    suspend fun delete(id: Long) = memories.deleteById(id)

    suspend fun forgetAll(): Int {
        ledger.deleteAll()
        return memories.deleteAll()
    }

    /** Both steps together (used where separate timing is not needed). */
    suspend fun record(
        p: Perception, people: Int, zone: String, latencyMs: Long,
        source: String = "phone", now: Long = System.currentTimeMillis()
    ) {
        saveNote(p, people, zone, latencyMs, source, now)
        updateLedger(p, zone, now)
    }

    /** Returns false when the note was skipped as a duplicate of the newest one. */
    suspend fun saveNote(
        p: Perception, people: Int, zone: String, latencyMs: Long,
        source: String = "phone", now: Long = System.currentTimeMillis()
    ): Boolean {
        // A static scene would otherwise add the same note every heartbeat. If the newest note says the same
        // thing in the same place, keep that one and only refresh the object ledger.
        val latest = memories.recent(1).firstOrNull()
        val duplicate = latest != null && latest.caption == p.caption && latest.zone == zone &&
            now - latest.timestamp < DEDUPE_WINDOW_MS
        if (duplicate) {
            // same scene still in view: stretch the note so a scene keeps its real start and end
            memories.extend(latest!!.id, now, p.motion, p.density)
            return false
        }
        memories.insert(
            MemoryEntity(
                timestamp = now,
                caption = p.caption,
                objects = p.objects.joinToString(","),
                ocrText = p.ocrText,
                peopleCount = people,
                zone = zone,
                backend = p.backend,
                latencyMs = latencyMs,
                source = source,
                endAt = now,
                motion = p.motion,
                density = p.density
            )
        )
        return true
    }

    suspend fun updateLedger(p: Perception, zone: String, now: Long = System.currentTimeMillis()) {
        for (item in p.objects) {
            val prev = ledger.get(item)
            ledger.upsert(
                LedgerEntity(
                    item = item,
                    lastSeen = now,
                    zone = zone,
                    // where it was in the frame ("on the table, next to the cup"); the caption when no relation was found
                    lastContext = p.where[item] ?: p.caption,
                    seenCount = (prev?.seenCount ?: 0) + 1,
                    // keep the last known position when this frame did not box the object
                    px = p.spatial[item]?.x ?: prev?.px ?: 0f, py = p.spatial[item]?.y ?: prev?.py ?: 0f, pz = p.spatial[item]?.z ?: prev?.pz ?: 0f,
                    dist = p.spatial[item]?.distance ?: prev?.dist ?: 0f,
                    az = p.spatial[item]?.azimuthDeg ?: prev?.az ?: 0f, el = p.spatial[item]?.elevationDeg ?: prev?.el ?: 0f,
                    hasHeading = p.spatial[item]?.let { if (it.headingValid) 1 else 0 } ?: prev?.hasHeading ?: 0
                )
            )
        }
    }

    /**
     * "Where did I last see X". Matches WHOLE WORDS only, so "key" can never match "keyboard".
     * 1) the object ledger, 2) the object lists of recent notes, 3) words read off signs/screens,
     * which is reported differently ("I read the word ...") because that is not seeing the object.
     */
    suspend fun lastSeen(item: String): Sighting? {
        val needle = tokens(item)
        if (needle.isEmpty()) return null
        // the name itself first, then everyday synonyms ("pills" -> a bottle or medicine), so nothing is invented
        for (alt in listOf(needle) + synonymsOf(needle).map { listOf(it) }) {
            val label = if (alt === needle) item.trim() else alt.first()
            ledger.recent(400).firstOrNull { itemMatches(it.item, alt) }?.let {
                return Sighting(
                    item.trim(), it.lastSeen, it.zone, it.lastContext, viaText = false, matched = label,
                    point = if (it.dist > 0f) com.smriti.app.spatial.Point3(it.item, it.px, it.py, it.pz, it.dist, it.az, it.el, 0.6f, it.hasHeading == 1) else null
                )
            }
            val recent = memories.recent(500)
            recent.firstOrNull { itemMatches(it.objects.replace(',', ' '), alt) }?.let {
                return Sighting(item.trim(), it.timestamp, it.zone, it.caption, viaText = false, matched = label)
            }
            if (alt === needle) recent.firstOrNull { itemMatches(it.ocrText, alt) }?.let {
                return Sighting(item.trim(), it.timestamp, it.zone, it.caption, viaText = true, matched = label)
            }
        }
        return null
    }

    /** An earlier, different sighting of the same thing (at least 30 minutes before [before]), for "before that it was...". */
    suspend fun earlierSighting(item: String, before: Long): Sighting? {
        val needle = tokens(item)
        val alts = listOf(needle) + synonymsOf(needle).map { listOf(it) }
        return memories.recent(1500)
            .firstOrNull { m -> m.timestamp < before - 30 * 60_000L && alts.any { itemMatches(m.objects.replace(',', ' '), it) } }
            ?.let { Sighting(item.trim(), it.timestamp, it.zone, "", viaText = false) }
    }

    /** Every thing Smriti has ever seen, newest first, for the Memory tab's "Where was it" list. */
    suspend fun seenItems(limit: Int = 60): List<LedgerEntity> = ledger.recent(limit)
    suspend fun range(from: Long, to: Long): List<MemoryEntity> = memories.between(from, to)

    /** Notes between two times grouped into scenes (oldest first). */
    suspend fun scenes(from: Long, to: Long): List<com.smriti.app.scene.Scene> =
        com.smriti.app.scene.SceneBuilder.build(memories.between(from, to).map(::toRow))

    private fun toRow(m: MemoryEntity) = com.smriti.app.scene.NoteRow(
        start = m.timestamp, end = maxOf(m.endAt, m.timestamp),
        objects = m.objects.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        zone = m.zone, motion = m.motion, density = m.density, people = m.peopleCount, hasText = m.ocrText.isNotBlank()
    )

    /** Everything seen per place, for the home map: place -> (object, lastSeen), newest first. */
    suspend fun homeMap(): Map<String, List<LedgerEntity>> =
        ledger.recent(500).filter { it.zone != "somewhere" && it.zone.isNotBlank() }.groupBy { it.zone }

    /** Token-overlap search with a recency tie-break. Embedding search plugs in here later. */
    suspend fun search(query: String, limit: Int = 3): List<MemoryEntity> {
        val tokens = tokens(query)
        if (tokens.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        return memories.recent(1000)
            .map { m ->
                val words = tokens(m.caption + " " + m.objects.replace(',', ' ') + " " + m.ocrText + " " + m.zone).toSet()
                val hits = tokens.count { it in words }
                val recency = 1f / (1f + (now - m.timestamp) / 3_600_000f)
                m to (hits + 0.2f * recency)
            }
            .filter { it.second >= 1f }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    suspend fun forget(sinceMs: Long): Int {
        ledger.deleteSince(sinceMs)
        return memories.deleteSince(sinceMs)
    }

    /** Memory decay: raw moments older than [before] are dropped (a digest can be kept later). */
    suspend fun decay(before: Long): Int {
        ledger.deleteBefore(before)
        return memories.deleteBefore(before)
    }

    suspend fun textBytes(): Long = memories.textBytes()

    data class Sighting(val item: String, val at: Long, val zone: String, val context: String, val viaText: Boolean = false, val matched: String = item, val point: com.smriti.app.spatial.Point3? = null) {
        val bySynonym: Boolean get() = !matched.equals(item, ignoreCase = true)
    }

    companion object {
        private const val DEDUPE_WINDOW_MS = 10 * 60_000L

        private val stop = setOf(
            "the", "a", "an", "my", "our", "is", "are", "was", "were", "did", "do", "i", "me", "you",
            "what", "where", "when", "who", "have", "has", "see", "saw", "seen", "of", "in", "on", "at", "to", "it"
        )

        /** Everyday groups. Words are stemmed like [tokens], and a group only widens a search, never a note. */
        private val SYNONYMS = listOf(
            listOf("pill", "tablet", "capsule", "medicine", "medication", "drug", "bottle"),
            listOf("key", "keychain", "keyring"),
            listOf("phone", "cellphone", "mobile", "smartphone", "cell"),
            listOf("glass", "eyeglass", "spectacle", "sunglass"),
            listOf("wallet", "purse", "handbag"),
            listOf("remote", "controller"),
            listOf("laptop", "computer", "notebook"),
            listOf("cup", "mug"),
            listOf("bag", "backpack", "handbag", "suitcase"),
            listOf("book", "notebook", "diary")
        )

        fun synonymsOf(needle: List<String>): List<String> =
            if (needle.size != 1) emptyList() else SYNONYMS.firstOrNull { needle[0] in it }?.filter { it != needle[0] }.orEmpty()
        fun tokens(q: String): List<String> =
            q.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 && it !in stop }.map(::stem)

        fun normalise(s: String): String = tokens(s).joinToString(" ")

        /** True when every word of [needle] appears as a whole word in [text]. */
        fun itemMatches(text: String, needle: List<String>): Boolean {
            if (needle.isEmpty()) return false
            val words = tokens(text).toSet()
            return needle.all { it in words }
        }

        private fun stem(w: String) = if (w.length > 3 && w.endsWith("s")) w.dropLast(1) else w
    }
}
