package com.smriti.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.smriti.app.memory.CameraSessionDao
import com.smriti.app.memory.VisualDao
import com.smriti.app.pipeline.PipelineBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Keeps the memory inside its budget by applying [StoragePolicy]: old pictures are recompressed to a smaller size, old moments are
 * thinned, and only then the oldest are removed. The recording in progress, each recording's cover picture and everything from the
 * last two hours are never touched. Video is never stored, so this is about stills and vectors.
 */
class StorageManager(
    private val context: Context,
    private val visual: VisualMemory,
    private val visualDao: VisualDao,
    private val sessionDao: CameraSessionDao,
    private val budgetMb: () -> Int,
    private val currentSession: () -> Long?,
    private val onChanged: suspend () -> Unit
) {
    class Usage(val moments: Int, val thumbBytes: Long, val dbBytes: Long, val oldestMs: Long?) {
        val total: Long get() = thumbBytes + dbBytes
    }

    class Summary(val before: Long, val after: Long, val recompressed: Int, val deleted: Int) {
        override fun toString() = if (recompressed == 0 && deleted == 0) "Nothing to free." else
            "Freed ${StoragePolicy.size((before - after).coerceAtLeast(0))}: $recompressed pictures made smaller, $deleted moments removed."
    }

    private val mutex = Mutex()

    /** what the memory takes now: pictures, plus the database file(s) with their write-ahead log */
    suspend fun usage(): Usage = withContext(Dispatchers.IO) {
        val dbFile = context.getDatabasePath("smriti.db")
        val db = dbFile.parentFile?.listFiles { f -> f.name.startsWith("smriti.db") }?.sumOf { it.length() } ?: 0L
        Usage(visualDao.count(), visual.thumbBytes(), db, visualDao.firstTimestamp())
    }

    fun budgetBytes(): Long = budgetMb() * 1_000_000L

    /**
     * Checks the budget and frees space when it is over 85 % (or over the row cap). [force] is the "Free up space" button: it acts from
     * 30 % of the budget and aims for 25 %. Returns null when there was nothing to do.
     */
    suspend fun enforce(force: Boolean = false): Summary? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val metas = visualDao.meta()
            val covers = sessionDao.list().mapNotNull { it.cover.takeIf { c -> c.isNotBlank() } }.toSet()
            val live = currentSession() ?: 0L
            val items = metas.map { m ->
                val len = visual.fileFor(m.thumb).length()
                StoragePolicy.Item(
                    id = m.id, timestamp = m.timestamp, sessionId = m.sessionId, thumbBytes = len,
                    otherBytes = (m.vecBytes + m.capBytes + ROW_OVERHEAD).toLong(),
                    protected = m.thumb in covers || (live != 0L && m.sessionId == live),
                    small = len <= (StoragePolicy.SMALL_BYTES * 1.3).toLong()
                )
            }
            val plan = if (force) StoragePolicy.plan(items, budgetBytes(), VisualMemory.MAX_ROWS, now, startAt = 0.30, target = 0.25)
            else StoragePolicy.plan(items, budgetBytes(), VisualMemory.MAX_ROWS, now)
            if (plan.isEmpty) return@withContext null
            val s = apply(plan)
            PipelineBus.info("storage", s.toString())
            if (s.deleted > 0) onChanged()
            s
        }
    }

    /** applies a plan: smaller pictures first, then the removals */
    suspend fun apply(plan: StoragePolicy.Plan): Summary = withContext(Dispatchers.IO) {
        var smaller = 0
        if (plan.recompress.isNotEmpty()) {
            val names = plan.recompress.chunked(400).flatMap { visualDao.thumbsOf(it) }
            for (name in names) if (recompress(visual.fileFor(name))) smaller++
        }
        val removed = if (plan.delete.isNotEmpty()) visual.deleteMany(plan.delete) else 0
        Summary(plan.bytesBefore, plan.bytesAfter, smaller, removed)
    }

    /** rewrites a picture at most 240 px on its long side, JPEG quality 65 (about a third of the size) */
    private fun recompress(file: File): Boolean {
        if (!file.exists()) return false
        val bmp = com.smriti.app.security.SecureFiles.decodeBitmap(file) ?: return false
        val scale = 240f / maxOf(bmp.width, bmp.height)
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
        return try {
            val jpeg = java.io.ByteArrayOutputStream(8_000)
            out.compress(Bitmap.CompressFormat.JPEG, 65, jpeg)
            com.smriti.app.security.SecureFiles.write(file, jpeg.toByteArray())
            true
        } catch (_: Throwable) {
            false
        } finally {
            if (out !== bmp) out.recycle()
            bmp.recycle()
        }
    }

    /** debug builds: what the policy would do right now, without doing it (or, with [force], without the budget check) */
    suspend fun report(force: Boolean): String = withContext(Dispatchers.IO) {
        val metas = visualDao.meta()
        val now = System.currentTimeMillis()
        val items = metas.map { m -> val len = visual.fileFor(m.thumb).length(); StoragePolicy.Item(m.id, m.timestamp, m.sessionId, len, (m.vecBytes + m.capBytes + ROW_OVERHEAD).toLong()) }
        val plan = if (force) StoragePolicy.plan(items, budgetBytes(), VisualMemory.MAX_ROWS, now, 0.30, 0.25) else StoragePolicy.plan(items, budgetBytes(), VisualMemory.MAX_ROWS, now)
        val u = usage()
        "moments=${u.moments} pictures=${StoragePolicy.size(u.thumbBytes)} db=${StoragePolicy.size(u.dbBytes)} budget=${budgetMb()} MB -> would recompress ${plan.recompress.size}, delete ${plan.delete.size}, ${StoragePolicy.size(plan.bytesBefore)} -> ${StoragePolicy.size(plan.bytesAfter)}"
    }

    /**
     * Debug builds: makes 120 synthetic moments of a made-up recording, 10 days old, runs a plan on those only (never on real memories),
     * checks that pictures got smaller and moments were removed, then removes everything it made.
     */
    suspend fun selfTest(): String = withContext(Dispatchers.IO) {
        val real = visualDao.meta().firstOrNull { visual.fileFor(it.thumb).exists() } ?: return@withContext "no picture to copy"
        val bytes = com.smriti.app.security.SecureFiles.read(visual.fileFor(real.thumb)) ?: return@withContext "picture could not be read"
        val sid = 987_654L
        val start = System.currentTimeMillis() - 10 * 86_400_000L
        val made = ArrayList<Long>()
        try {
            for (i in 0 until 120) {
                val name = "selftest_$i.jpg"
                com.smriti.app.security.SecureFiles.write(visual.fileFor(name), bytes)
                made += visualDao.insert(com.smriti.app.memory.VisualEntity(timestamp = start + i * 4_000L, source = "selftest", place = "", caption = "selftest", thumb = name, vec = ByteArray(3072), sessionId = sid, endAt = start + i * 4_000L + 4_000L))
            }
            suspend fun mine() = visualDao.meta().filter { it.sessionId == sid }
            suspend fun items() = mine().map { m -> val len = visual.fileFor(m.thumb).length(); StoragePolicy.Item(m.id, m.timestamp, m.sessionId, len, (m.vecBytes + m.capBytes + ROW_OVERHEAD).toLong(), small = len <= (StoragePolicy.SMALL_BYTES * 1.3).toLong()) }
            val before = items()
            val bytesBefore = before.sumOf { it.thumbBytes }
            // a budget of 60 % of what these moments take, and a row cap that forces thinning as well
            val plan = StoragePolicy.plan(before, budgetBytes = (before.sumOf { it.thumbBytes + it.otherBytes } * 0.6).toLong(), maxRows = 100, now = System.currentTimeMillis())
            val s = apply(plan)
            val after = items()
            val bytesAfter = after.sumOf { it.thumbBytes }
            val okSmaller = bytesAfter < bytesBefore
            val okRows = after.size == before.size - s.deleted
            val okEnds = after.any { it.id == before.first().id } && after.any { it.id == before.last().id }
            "synthetic: rows ${before.size} -> ${after.size} (removed ${s.deleted}), recompressed ${s.recompressed}, pictures ${StoragePolicy.size(bytesBefore)} -> ${StoragePolicy.size(bytesAfter)}; " +
                "smaller=$okSmaller rowsMatch=$okRows firstAndLastKept=$okEnds"
        } finally {
            visual.deleteMany(visualDao.meta().filter { it.sessionId == sid }.map { it.id })
            visual.fileFor("x").parentFile?.listFiles { f -> f.name.startsWith("selftest_") }?.forEach { it.delete() }
        }
    }

    private companion object {
        const val ROW_OVERHEAD = 200
    }
}
