package com.smriti.app.ai

import android.os.SystemClock
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Live status of every on-device AI engine, measured by Smriti itself.
 *
 * Android gives apps no hardware counter for the NPU, so "NPU busy" is not read from the chip. It is the real wall time spent
 * inside inference calls that run on the NPU (EmbeddingGemma's text encoder on the Hexagon NPU), as a share of the last ten
 * seconds. That is measured, not guessed, and it is labelled that way wherever it is shown.
 */
object AiMeter {

    class Unit(val name: String, val role: String) {
        @Volatile var backend: String = "-"
        @Volatile var state: String = "not loaded"
        @Volatile var loadMs: Long = 0
        val calls = AtomicLong()
        @Volatile var lastMs: Long = 0
        val totalMs = AtomicLong()
        /** (finished at elapsedRealtime ms, duration ms) for the last window */
        val window = ConcurrentLinkedDeque<Pair<Long, Long>>()
    }

    private val units = LinkedHashMap<String, Unit>()
    private const val WINDOW_MS = 10_000L

    private fun unit(name: String, role: String): Unit = synchronized(units) { units.getOrPut(name) { Unit(name, role) } }

    fun loaded(name: String, role: String, backend: String, loadMs: Long) {
        unit(name, role).apply { this.backend = backend; this.loadMs = loadMs; state = "ready" }
    }

    fun missing(name: String, role: String) { unit(name, role).state = "model file missing" }

    fun failed(name: String, role: String, why: String) { unit(name, role).state = "failed: ${why.take(60)}" }

    fun record(name: String, role: String, backend: String, ms: Long) {
        val u = unit(name, role)
        if (u.state != "ready") u.state = "ready"
        u.backend = backend
        u.calls.incrementAndGet(); u.lastMs = ms; u.totalMs.addAndGet(ms)
        val now = SystemClock.elapsedRealtime()
        u.window.addLast(now to ms)
        while (u.window.peekFirst()?.let { now - it.first > WINDOW_MS } == true) u.window.pollFirst()
    }

    /** Runs [block] and records how long it took. */
    inline fun <T> time(name: String, role: String, backend: String, block: () -> T): T {
        val t0 = SystemClock.elapsedRealtime()
        try { return block() } finally { record(name, role, backend, SystemClock.elapsedRealtime() - t0) }
    }

    class Row(val name: String, val role: String, val backend: String, val state: String, val loadMs: Long, val calls: Long, val lastMs: Long, val avgMs: Long, val busyPct: Float)

    fun rows(): List<Row> {
        val now = SystemClock.elapsedRealtime()
        return synchronized(units) { units.values.toList() }.map { u ->
            while (u.window.peekFirst()?.let { now - it.first > WINDOW_MS } == true) u.window.pollFirst()
            val busy = u.window.sumOf { it.second }.toFloat() / WINDOW_MS * 100f
            val calls = u.calls.get()
            Row(u.name, u.role, u.backend, u.state, u.loadMs, calls, u.lastMs, if (calls == 0L) 0 else u.totalMs.get() / calls, busy.coerceIn(0f, 100f))
        }
    }

    /** the engine whose work runs on the Hexagon NPU, if it is loaded */
    fun npu(): Row? = rows().firstOrNull { it.backend.startsWith("NPU") }

    /** for the website's system sample */
    fun snapshot(): List<Map<String, Any>> = rows().map {
        mapOf("name" to it.name, "role" to it.role, "backend" to it.backend, "state" to it.state, "loadMs" to it.loadMs, "calls" to it.calls, "lastMs" to it.lastMs, "avgMs" to it.avgMs, "busyPct" to it.busyPct)
    }
}
