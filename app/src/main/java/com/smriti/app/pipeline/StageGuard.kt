package com.smriti.app.pipeline

import com.smriti.app.BuildConfig
import com.smriti.app.util.Metrics
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/** Debug-only switch used by tests and the dashboard to prove the pipeline survives a failing stage. */
object FaultInjector {
    private val stages = ConcurrentHashMap.newKeySet<String>()

    fun set(stage: String, on: Boolean) {
        if (!BuildConfig.DEBUG) return
        if (on) stages.add(stage) else stages.remove(stage)
        PipelineBus.warn("fault", "fault injection ${if (on) "ON" else "OFF"} for $stage")
    }

    fun clear() = stages.clear()
    fun active(): Set<String> = stages.toSet()

    fun check(stage: String) {
        if (BuildConfig.DEBUG && stage in stages) throw IllegalStateException("Injected fault in $stage")
    }
}

data class StageResult<T>(val value: T, val ok: Boolean, val ran: Boolean, val durMs: Long)

/**
 * Wraps every stage so that:
 *  - a failure is caught, reported as an ERROR event, and the frame continues with what succeeded;
 *  - after [limit] consecutive failures the stage trips (DISABLED) instead of failing on every frame;
 *  - every run produces START and OK/ERROR events with the real duration.
 * A tripped stage is re-armed by the user (dashboard or Settings), never silently.
 */
class StageGuard(private val limit: Int = 3) {

    private val failures = ConcurrentHashMap<String, Int>()
    private val tripped = ConcurrentHashMap.newKeySet<String>()

    fun isTripped(stage: String) = stage in tripped
    fun trippedStages(): Set<String> = tripped.toSet()

    fun rearm(stage: String) {
        failures.remove(stage)
        if (tripped.remove(stage)) {
            PipelineBus.emit(PipelineEvent(EventType.STAGE, stage = stage, status = Status.OK, message = "re-armed"))
        }
    }

    fun rearmAll() = trippedStages().forEach(::rearm)

    suspend fun <T> run(
        frameId: Long,
        stage: String,
        fallback: T,
        enabled: Boolean = true,
        summarise: (T) -> Pair<String, Map<String, Any>> = { "" to emptyMap() },
        block: suspend () -> T
    ): StageResult<T> {
        if (!enabled) {
            PipelineBus.emit(PipelineEvent(EventType.STAGE, frameId = frameId, stage = stage, status = Status.SKIPPED, message = "turned off"))
            return StageResult(fallback, ok = true, ran = false, durMs = 0)
        }
        if (stage in tripped) {
            PipelineBus.emit(PipelineEvent(EventType.STAGE, frameId = frameId, stage = stage, status = Status.DISABLED, message = "tripped, waiting for re-arm"))
            return StageResult(fallback, ok = false, ran = false, durMs = 0)
        }
        PipelineBus.emit(PipelineEvent(EventType.STAGE, frameId = frameId, stage = stage, status = Status.START))
        val t0 = System.nanoTime()
        return try {
            FaultInjector.check(stage)
            val v = block()
            val ms = (System.nanoTime() - t0) / 1_000_000
            failures.remove(stage)
            Metrics.record(stage, ms)
            val (msg, data) = summarise(v)
            PipelineBus.emit(PipelineEvent(EventType.STAGE, frameId = frameId, stage = stage, status = Status.OK, durMs = ms, message = msg, data = data))
            StageResult(v, ok = true, ran = true, durMs = ms)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Throwable) {
            val ms = (System.nanoTime() - t0) / 1_000_000
            val n = (failures[stage] ?: 0) + 1
            failures[stage] = n
            PipelineBus.emit(
                PipelineEvent(
                    EventType.STAGE, frameId = frameId, stage = stage, status = Status.ERROR, durMs = ms,
                    message = e.message ?: e.javaClass.simpleName, data = mapOf("consecutive" to n, "limit" to limit)
                )
            )
            if (n >= limit && tripped.add(stage)) {
                PipelineBus.emit(
                    PipelineEvent(EventType.STAGE, frameId = frameId, stage = stage, status = Status.DISABLED, message = "tripped after $n failures")
                )
            }
            StageResult(fallback, ok = false, ran = true, durMs = ms)
        }
    }
}
