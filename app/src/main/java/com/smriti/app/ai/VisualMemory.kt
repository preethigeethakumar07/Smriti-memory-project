package com.smriti.app.ai

import android.content.Context
import android.graphics.Bitmap
import com.smriti.app.embed.GemmaEmbedder
import com.smriti.app.memory.VisualDao
import com.smriti.app.memory.VisualEntity
import com.smriti.app.pipeline.PipelineBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The "what did I see" memory. Every few seconds of new scene, the privacy-redacted frame is turned into a meaning vector by
 * EmbeddingGemma (image, on the phone) and kept with a small redacted thumbnail and its caption. A question is turned into
 * a vector the same way and the closest moments are returned, with their pictures.
 * Faces are already blurred before anything gets here; the raw camera frame is never written anywhere.
 */
class VisualMemory(context: Context, private val dao: VisualDao, val embedder: GemmaEmbedder) {

    class Hit(val entity: VisualEntity, val score: Float, val file: File)

    private val dir = File(context.filesDir, "thumbs").also { it.mkdirs() }
    private val lock = Mutex()
    private var cache: MutableList<Pair<VisualEntity, FloatArray>>? = null
    @Volatile private var lastSavedAt = 0L
    @Volatile private var lastTryAt = 0L
    @Volatile private var lastVec: FloatArray? = null

    val ready: Boolean get() = embedder.state is GemmaEmbedder.State.Ready

    /** true when enough time has passed that another moment may be remembered (cheap; checked before any copy is made) */
    /** multiplies the gap between moments (the phone is warm: remember less often) */
    @Volatile var gapScale = 1
    fun due(now: Long = System.currentTimeMillis()) = ready && now - lastTryAt >= MIN_GAP_MS * gapScale

    /** Embeds the redacted frame and keeps its thumbnail. Returns false when it was skipped (too soon, or the same scene). */
    /** set by the app: the last few seconds of sound, or null when quiet or the microphone is not in use */
    @Volatile var heardSource: (() -> ByteArray?)? = null

    /** the camera session being recorded (0 = none); moments saved now belong to it */
    @Volatile var sessionId: Long = 0L

    /** set by the app: looks at a picture and its vector and says whether it is too private to keep (the sensitive-scene filter) */
    @Volatile var screen: (suspend (FloatArray, Bitmap) -> Boolean)? = null
    @Volatile var droppedPrivate = 0; private set

    /** called with every window after it is saved (used by the live look-for watcher) */
    @Volatile var onWindow: ((VisualEntity, FloatArray) -> Unit)? = null

    /**
     * called after a window is saved with its id and a larger (768 px) blurred copy of the frame for the object inventory;
     * the receiver owns that copy and must recycle it
     */
    @Volatile var onSaved: ((Long, Bitmap) -> Unit)? = null

    /** stores the written object list of a window, in its caption, and refreshes the in-memory copy used by search */
    suspend fun annotate(id: Long, objects: List<String>) = withContext(Dispatchers.IO) {
        if (objects.isEmpty()) return@withContext
        var caption: String? = null
        lock.withLock {
            val c = cache ?: return@withLock
            val i = c.indexOfFirst { it.first.id == id }
            if (i >= 0) {
                val (e, v) = c[i]
                caption = Inventory.merge(e.caption, objects)
                c[i] = com.smriti.app.memory.VisualEntity(e.id, e.timestamp, e.source, e.place, caption!!, e.thumb, e.vec, e.kind, e.sessionId, e.endAt, e.heard) to v
            }
        }
        val text = caption ?: Inventory.merge(dao.captionOf(id).orEmpty(), objects)
        dao.setCaption(id, text)
    }

    suspend fun remember(redacted: Bitmap, caption: String, place: String, source: String, now: Long = System.currentTimeMillis()): Boolean = withContext(Dispatchers.Default) {
        if (!ready || now - lastTryAt < MIN_GAP_MS * gapScale) return@withContext false
        lastTryAt = now
        if (isBlank(redacted)) { blank++; blankStreak++; return@withContext false }     // finger over the lens, lights off: nothing worth remembering
        blankStreak = 0
        val small = shrink(redacted, EMBED_PX)
        val heard = runCatching { heardSource?.invoke() }.getOrNull()
        val v = try { if (heard != null) embedder.embedMoment(small, heard) else embedder.embedImage(small) } finally { if (small !== redacted) small.recycle() }
            ?: return@withContext false.also { PipelineBus.warn("embed", "image embedding failed: ${embedder.lastError}") }
        // a picture that looks undressed is dropped here: no picture, no vector, no caption, no object list, no sound
        if (screen?.invoke(v, redacted) == true) { droppedPrivate++; lastVec = null; return@withContext false }
        val prev = lastVec
        // a picture nearly identical to the last one is skipped, except for a heartbeat every 30 s so a long quiet scene still has timestamps
        val heartbeat = if (sameStreak >= QUIET_AFTER) HEARTBEAT_QUIET_MS else HEARTBEAT_MS      // a long still scene (a home camera pointed at a room) is noted less often
        if (prev != null && GemmaEmbedder.cosine(prev, v) > SAME_SCENE && now - lastSavedAt < heartbeat) { same++; sameStreak++; return@withContext false }
        sameStreak = 0
        val thumbBmp = shrink(redacted, THUMB_PX)
        val name = "t$now.jpg"
        try {
            val jpeg = java.io.ByteArrayOutputStream(24_000)
            thumbBmp.compress(Bitmap.CompressFormat.JPEG, 80, jpeg)
            com.smriti.app.security.SecureFiles.write(File(dir, name), jpeg.toByteArray())          // stored encrypted
        } finally { if (thumbBmp !== redacted) thumbBmp.recycle() }
        val sid = sessionId
        val e = VisualEntity(timestamp = now, source = source, place = place, caption = caption.take(250), thumb = name, vec = pack(v), kind = if (heard != null) "image+sound" else "image",
            sessionId = sid, endAt = if (sid != 0L) now + SessionRules.WINDOW_MS else 0L)
        val id = dao.insert(e)
        lock.withLock { cache?.add(VisualEntity(id, now, source, place, e.caption, name, e.vec, e.kind, sid, e.endAt) to v) }
        lastSavedAt = now; lastVec = v
        saved++
        onWindow?.let { cb -> runCatching { cb(VisualEntity(id, now, source, place, e.caption, name, e.vec, e.kind, sid, e.endAt), v) } }
        onSaved?.let { cb ->
            val big = shrink(redacted, 768).let { if (it === redacted) it.copy(Bitmap.Config.ARGB_8888, false) else it }
            if (big != null) runCatching { cb(id, big) }.onFailure { big.recycle() }
        }
        if (++sinceTrim >= 100) { sinceTrim = 0; trim() }
        true
    }

    /** Closest moments to a question, best first. [score] is the cosine similarity (about 0.1 to 0.4 for a good text-to-image match). */
    suspend fun search(query: String, k: Int = 3, raw: Boolean = false): List<Hit> = withContext(Dispatchers.Default) {
        if (!ready) return@withContext emptyList()
        val q = embedder.embedText(query) ?: return@withContext emptyList()
        val all = load()
        // a moment whose written object list names what was asked ranks first, however weak its picture vector is
        val scored = all.map { (e, v) -> e to GemmaEmbedder.cosine(q, v).let { s -> if (Inventory.matches(query, e.caption)) maxOf(s, 0.7f) else s } }.sortedByDescending { it.second }
        if (scored.isEmpty()) return@withContext emptyList()
        // Text-to-picture scores sit in a narrow band (about 0.3 to 0.55) for everything, so a match must be high in
        // absolute terms AND clearly above what the same question scores against the typical picture.
        val median = scored[scored.size / 2].second
        val floor = if (raw) -1f else maxOf(MATCH_MIN, median + MARGIN)
        scored.take(k)
            .filter { it.second >= floor }
            .map { (e, s) -> Hit(e, s, File(dir, e.thumb)) }
            .filter { it.file.exists() }
    }

    fun fileFor(name: String): File = File(dir, name)

    /** every remembered window with its vector, for searches that filter by session or time */
    suspend fun all(): List<Pair<VisualEntity, FloatArray>> = load()

    suspend fun count(): Int = dao.count()

    /** Up to [n] moments spread evenly across a time window, oldest first: the evidence for "what happened in the last 10 minutes". */
    suspend fun sample(from: Long, to: Long, n: Int): List<VisualEntity> = withContext(Dispatchers.IO) {
        val all = dao.between(from, to).filter { File(dir, it.thumb).exists() }
        if (all.size <= n) all else List(n) { i -> all[(i * (all.size - 1)) / (n - 1).coerceAtLeast(1)] }
    }

    /** newest remembered moments, for the Memory screen */
    fun moments(limit: Int = 200) = dao.recentFlow(limit)

    private var sinceTrim = 0
    @Volatile var saved = 0; private set
    @Volatile var blank = 0; private set
    @Volatile private var sameStreak = 0
    /** consecutive blank frames (lens covered, lights off); the app looks at a covered lens only now and then */
    @Volatile var blankStreak = 0; private set
    @Volatile var same = 0; private set
    fun stats() = "saved=$saved blankSkipped=$blank sameSkipped=$same"

    /** keeps the newest [MAX_ROWS] moments; older ones (and their pictures) are removed so storage stays bounded */
    private suspend fun trim() {
        val extra = dao.count() - MAX_ROWS
        if (extra <= 0) return
        val old = dao.oldest(extra + 200)
        old.forEach { File(dir, it.thumb).delete() }
        dao.deleteByIds(old.map { it.id })
        lock.withLock { cache = null }
    }

    /** total size of the kept thumbnails, in bytes */
    fun thumbBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** removes many moments and their pictures (the storage policy); returns how many were removed */
    suspend fun deleteMany(ids: List<Long>): Int = withContext(Dispatchers.IO) {
        var n = 0
        for (chunk in ids.chunked(400)) {
            val names = dao.thumbsOf(chunk)
            dao.deleteByIds(chunk)
            names.forEach { File(dir, it).delete() }
            n += chunk.size
        }
        lock.withLock { cache = null }; lastVec = null
        n
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        dao.thumbOf(id)?.let { File(dir, it).delete() }
        dao.deleteById(id)
        lock.withLock { cache = null }
    }

    suspend fun forgetSince(since: Long): Int = withContext(Dispatchers.IO) {
        val names = dao.thumbsSince(since); val n = dao.deleteSince(since)
        names.forEach { File(dir, it).delete() }
        lock.withLock { cache = null }; lastVec = null
        n
    }

    suspend fun forgetBefore(before: Long): Int = withContext(Dispatchers.IO) {
        val names = dao.thumbsBefore(before); val n = dao.deleteBefore(before)
        names.forEach { File(dir, it).delete() }
        lock.withLock { cache = null }
        n
    }

    /** removes one camera session's moments and pictures (the session row itself is removed by the recorder) */
    suspend fun forgetSession(id: Long): Int = withContext(Dispatchers.IO) {
        val names = dao.thumbsOfSession(id); val n = dao.deleteSession(id)
        names.forEach { File(dir, it).delete() }
        lock.withLock { cache = null }; lastVec = null
        n
    }

    suspend fun forgetAll(): Int = withContext(Dispatchers.IO) {
        val n = dao.deleteAll()
        dir.listFiles()?.forEach { it.delete() }
        lock.withLock { cache = null }; lastVec = null
        n
    }

    private suspend fun load(): List<Pair<VisualEntity, FloatArray>> = lock.withLock {
        cache ?: dao.recent(MAX_ROWS).map { it to unpack(it.vec) }.toMutableList().also { cache = it }
    }

    /** true for a nearly black, nearly white or flat-coloured frame (checked on a 16x16 copy) */
    private fun isBlank(b: Bitmap): Boolean {
        val t = Bitmap.createScaledBitmap(b, 16, 16, true)
        var sum = 0.0; var sq = 0.0
        for (y in 0 until 16) for (x in 0 until 16) {
            val p = t.getPixel(x, y)
            val l = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
            sum += l; sq += l * l
        }
        if (t !== b) t.recycle()
        val mean = sum / 256.0
        val sd = Math.sqrt((sq / 256.0 - mean * mean).coerceAtLeast(0.0))
        return mean < 28.0 || mean > 242.0 || sd < 6.0
    }

    private fun thumbSizeKb(name: String) = File(dir, name).length() / 1024

    private fun shrink(b: Bitmap, longest: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= longest) return b
        val s = longest.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    companion object {
        /** lowest text-to-picture similarity that counts as a match (tuned on the device) */
        const val MATCH_MIN = 0.40f
        const val MARGIN = 0.05f
        const val MIN_GAP_MS = 1_500L
        const val HEARTBEAT_MS = 8_000L
        /** after this many skipped look-alike frames in a row, the heartbeat stretches */
        const val QUIET_AFTER = 8
        const val HEARTBEAT_QUIET_MS = 30_000L
        const val SAME_SCENE = 0.99f
        const val EMBED_PX = 512
        const val THUMB_PX = 360
        const val MAX_ROWS = 6000

        fun pack(v: FloatArray): ByteArray {
            val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            b.asFloatBuffer().put(v)
            return b.array()
        }

        fun unpack(bytes: ByteArray): FloatArray {
            val f = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(f)
            return f
        }
    }
}
