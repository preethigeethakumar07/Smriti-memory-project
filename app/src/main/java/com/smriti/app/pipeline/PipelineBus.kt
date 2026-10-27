package com.smriti.app.pipeline

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * In-process event bus. Producers never block: if a slow consumer (for example the dashboard
 * connection) falls behind, the OLDEST events are dropped. A ring buffer keeps the most recent
 * events so a dashboard that connects late can replay what just happened.
 */
object PipelineBus {

    private const val RING_CAPACITY = 400

    private val flow = MutableSharedFlow<PipelineEvent>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<PipelineEvent> = flow.asSharedFlow()

    private val ring = ArrayDeque<PipelineEvent>()
    private val frameSeq = AtomicLong()

    fun nextFrameId(): Long = frameSeq.incrementAndGet()

    fun emit(e: PipelineEvent) {
        synchronized(ring) {
            ring.addLast(e)
            if (ring.size > RING_CAPACITY) ring.removeFirst()
        }
        flow.tryEmit(e)
    }

    fun snapshot(): List<PipelineEvent> = synchronized(ring) { ring.toList() }

    fun clear() = synchronized(ring) { ring.clear() }

    // ---- small helpers so call sites stay one line --------------------------------------------------------------

    fun log(level: String, tag: String, message: String) =
        emit(PipelineEvent(EventType.LOG, message = message, stage = tag, data = mapOf("level" to level)))

    fun info(tag: String, message: String) = log("info", tag, message)
    fun warn(tag: String, message: String) = log("warn", tag, message)
    fun error(tag: String, message: String) = log("error", tag, message)
}
