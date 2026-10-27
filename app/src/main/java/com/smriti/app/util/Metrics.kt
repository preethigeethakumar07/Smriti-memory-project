package com.smriti.app.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-stage latency tracker. Keeps a sliding window so the dashboard and the benchmark can report
 * median and p95. Stage names are the same ids the pipeline events use.
 */
object Metrics {

    const val GATE = "gate"
    const val PRIVACY = "privacy"
    const val DETECT = "detect"
    const val LABEL = "label"
    const val OCR = "ocr"
    const val ZONE = "zone"
    const val COMPOSE = "compose"
    const val STORE = "store"
    const val LEDGER = "ledger"
    const val TOTAL = "total_frame"
    const val ANSWER = "answer"

    private val ORDER = listOf(GATE, PRIVACY, DETECT, LABEL, OCR, ZONE, COMPOSE, STORE, LEDGER, TOTAL, ANSWER)
    private const val WINDOW = 60

    data class Stat(val last: Long, val median: Long, val p95: Long, val n: Int)

    private val samples = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun record(stage: String, ms: Long) {
        val q = samples.getOrPut(stage) { ArrayDeque() }
        synchronized(q) {
            q.addLast(ms)
            if (q.size > WINDOW) q.removeFirst()
        }
    }

    inline fun <T> time(stage: String, block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            record(stage, (System.nanoTime() - t0) / 1_000_000)
        }
    }

    fun snapshot(): List<Pair<String, Stat>> {
        val keys = ORDER.filter { samples.containsKey(it) } + samples.keys.filter { it !in ORDER }.sorted()
        return keys.mapNotNull { k ->
            val q = samples[k] ?: return@mapNotNull null
            val copy = synchronized(q) { q.toList() }
            if (copy.isEmpty()) return@mapNotNull null
            val sorted = copy.sorted()
            k to Stat(
                last = copy.last(),
                median = sorted[sorted.size / 2],
                p95 = sorted[((sorted.size - 1) * 95) / 100],
                n = sorted.size
            )
        }
    }

    fun reset() = samples.clear()
}
