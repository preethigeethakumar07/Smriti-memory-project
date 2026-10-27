package com.smriti.app.ai

import android.os.SystemClock
import com.smriti.app.memory.CameraSessionDao
import com.smriti.app.memory.CameraSessionEntity
import com.smriti.app.memory.VisualDao
import com.smriti.app.pipeline.PipelineBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The plain rules of a session, kept free of Android so they are unit-tested. */
object SessionRules {
    /** a session shorter than this, or with nothing indexed, is not worth keeping */
    const val MIN_KEEP_MS = 15_000L
    /** no camera frames for this long ends the session (the app went to the background, the camera closed) */
    const val IDLE_END_MS = 60_000L
    /** a longer recording is split into chapters so each stays easy to browse */
    const val MAX_SESSION_MS = 30 * 60_000L
    /** length of the window one remembered moment covers */
    const val WINDOW_MS = 4_000L

    fun keep(durationMs: Long, moments: Int) = durationMs >= MIN_KEEP_MS && moments > 0
    fun shouldSplit(startedAt: Long, now: Long) = now - startedAt >= MAX_SESSION_MS
    fun isIdle(lastFrameAt: Long, now: Long) = now - lastFrameAt > IDLE_END_MS

    /** the value that appears most often, ignoring blanks and the "unknown" placeholder */
    fun mostCommon(values: List<String>, ignore: String = "somewhere"): String =
        values.filter { it.isNotBlank() && it != ignore }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()

    /** "14 min", "45 s", "1 h 05 min" */
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min"
            else -> "${s / 3600} h ${"%02d".format((s % 3600) / 60)} min"
        }
    }

    /** position inside a session, "00:03" or "1:02:05" */
    fun clock(offsetMs: Long): String {
        val s = (offsetMs / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    /** "Today 3:20 PM - 3:34 PM", "Yesterday ...", "9 Oct ..." */
    fun label(start: Long, end: Long, now: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): String {
        val time = SimpleDateFormat("h:mm a", locale)
        val day = dayWord(start, now, locale)
        val a = time.format(Date(start)); val b = time.format(Date(end))
        return if (a == b) "$day $a" else "$day $a - $b"      // a minute-long session reads "Today 4:50 AM", not "4:50 AM - 4:50 AM"
    }

    fun dayWord(ts: Long, now: Long, locale: Locale = Locale.getDefault()): String {
        fun key(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }
        val d = key(now) - key(ts)
        return when {
            d == 0 -> "Today"
            d == 1 || (d < 0 && d > -366 && key(ts) - key(now) == 364) -> "Yesterday"
            else -> SimpleDateFormat("d MMM", locale).format(Date(ts))
        }
    }
}

/**
 * Starts a session when the camera opens in Live and ends it when the camera closes, the user leaves, or no frames arrive for a
 * minute. While a session is open, every remembered moment is tagged with its id (see [VisualMemory.sessionId]); closing it
 * measures the session, picks a cover, and removes it if it was too short to matter. Ambient moments taken outside a session are untouched.
 */
class SessionRecorder(
    private val dao: CameraSessionDao,
    private val visualDao: VisualDao,
    private val visual: VisualMemory,
    private val search: MomentSearch,
    private val scope: CoroutineScope
) {
    private val _current = MutableStateFlow<Long?>(null)
    /** id of the session being recorded, or null */
    val current: StateFlow<Long?> = _current.asStateFlow()
    val active: Boolean get() = _current.value != null

    /** what this recording was started to find (null for an ordinary session) and what has been confirmed so far */
    private val _lookTarget = MutableStateFlow<String?>(null)
    val lookTarget: StateFlow<String?> = _lookTarget.asStateFlow()
    private val _lookFinds = MutableStateFlow<List<TargetWatcher.Find>>(emptyList())
    val lookFinds: StateFlow<List<TargetWatcher.Find>> = _lookFinds.asStateFlow()
    private var watcher: TargetWatcher? = null
    private var watchJob: kotlinx.coroutines.Job? = null

    /** called after a recording that had a target is closed and kept: it gets the session and the windows confirmed live */
    @Volatile var onEnded: (suspend (CameraSessionEntity, List<com.smriti.app.memory.VisualEntity>) -> Unit)? = null

    private val lock = Mutex()
    @Volatile private var lastFrameAt = 0L
    @Volatile private var startedAt = 0L
    @Volatile private var source = "phone"

    init {
        scope.launch {
            closeOrphans()
            while (true) {
                delay(10_000)
                if (active && SessionRules.isIdle(lastFrameAt, SystemClock.elapsedRealtime())) end("no camera frames for a minute")
            }
        }
    }

    fun start(source: String, target: String = "") {
        scope.launch {
            lock.withLock {
                if (_current.value != null) return@withLock
                val now = System.currentTimeMillis()
                val id = dao.insert(CameraSessionEntity(startedAt = now, endedAt = now, source = source, target = target.trim()))
                this@SessionRecorder.source = source
                startedAt = now
                lastFrameAt = SystemClock.elapsedRealtime()
                visual.sessionId = id
                _current.value = id
                if (target.isNotBlank()) {
                    val w = TargetWatcher(target.trim(), now, visual, search, scope)
                    watcher = w
                    _lookTarget.value = target.trim(); _lookFinds.value = emptyList()
                    visual.onWindow = { e, v -> w.offer(e, v) }
                    watchJob = scope.launch { w.finds.collect { _lookFinds.value = it } }
                }
                PipelineBus.info("session", "recording started (#$id, $source)")
            }
        }
    }

    /** Called for every camera frame while a session is open: keeps it alive and splits very long recordings. */
    fun touch() {
        lastFrameAt = SystemClock.elapsedRealtime()
        if (active && SessionRules.shouldSplit(startedAt, System.currentTimeMillis())) {
            val src = source
            scope.launch { end("chapter limit"); start(src) }
        }
    }

    fun end(reason: String) {
        scope.launch {
            lock.withLock {
                val id = _current.value ?: return@withLock
                visual.sessionId = 0
                _current.value = null
                val entity = dao.byId(id) ?: return@withLock
                val w = watcher
                val confirmed = w?.foundWindows().orEmpty()
                visual.onWindow = null; watchJob?.cancel(); w?.close(); watcher = null
                _lookTarget.value = null
                val kept = finalize(entity, System.currentTimeMillis())
                if (kept && entity.target.isNotBlank()) {
                    val cb = onEnded
                    if (cb != null) scope.launch { runCatching { dao.byId(id)?.let { cb(it, confirmed) } } }
                }
                PipelineBus.info("session", "recording ended (#$id): $reason")
            }
        }
    }

    /** Closes a session: measures it and keeps it, or removes it when it is too short or empty. */
    private suspend fun finalize(entity: CameraSessionEntity, endAt: Long): Boolean {
        val rows = visualDao.forSession(entity.id)
        val last = rows.lastOrNull()?.timestamp ?: entity.startedAt
        val end = minOf(endAt, last + SessionRules.WINDOW_MS).coerceAtLeast(entity.startedAt)
        if (!SessionRules.keep(end - entity.startedAt, rows.size)) {
            visual.forgetSession(entity.id)
            dao.delete(entity.id)
            return false
        }
        dao.update(
            CameraSessionEntity(
                id = entity.id, startedAt = entity.startedAt, endedAt = end, source = entity.source,
                place = SessionRules.mostCommon(rows.map { it.place }),
                cover = rows[rows.size / 2].thumb,
                segments = rows.size,
                hasSound = rows.any { it.kind.contains("sound") },
                open = false,
                target = entity.target,
                result = entity.result
            )
        )
        return true
    }

    /** A session still marked open at start-up means the app was stopped mid-recording: close it with what was indexed. */
    private suspend fun closeOrphans() {
        for (s in dao.openOnes()) {
            if (s.id == _current.value) continue
            val rows = visualDao.forSession(s.id)
            finalize(s, (rows.lastOrNull()?.timestamp ?: s.startedAt) + SessionRules.WINDOW_MS)
        }
    }

    /** After memories were forgotten: drop sessions with nothing left and refresh the counts of the rest. */
    suspend fun reconcile() {
        lock.withLock {
            for (s in dao.list()) {
                if (s.id == _current.value) continue
                val n = visualDao.countOfSession(s.id)
                if (n == 0) dao.delete(s.id)
                else if (n != s.segments) dao.update(CameraSessionEntity(s.id, s.startedAt, s.endedAt, s.source, s.place, s.cover, n, s.hasSound, s.open, s.target, s.result))
            }
        }
    }

    suspend fun delete(id: Long) {
        lock.withLock {
            if (_current.value == id) { visual.sessionId = 0; _current.value = null }
            visual.forgetSession(id)
            dao.delete(id)
        }
    }

    suspend fun deleteAll() {
        lock.withLock {
            visual.sessionId = 0; _current.value = null
            dao.deleteAll()
        }
    }
}
