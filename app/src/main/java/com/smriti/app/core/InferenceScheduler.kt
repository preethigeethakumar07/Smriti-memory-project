package com.smriti.app.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One accelerator, one job at a time.
 *
 *  - Foreground work (a question, "what do you see") cancels any running background
 *    indexing job and goes next, so the user never waits behind housekeeping.
 *  - Background work is only started when the accelerator is idle; otherwise the
 *    frame is dropped, never queued. This is what prevents pile-ups and UI hangs.
 */
class InferenceScheduler(private val scope: CoroutineScope) {

    private val lock = Mutex()

    @Volatile
    private var backgroundJob: Job? = null

    val isBusy: Boolean get() = lock.isLocked

    suspend fun <T> runForeground(block: suspend () -> T): T {
        backgroundJob?.cancel()
        return lock.withLock { block() }
    }

    /** Returns false (and does nothing) if the accelerator is already in use. */
    fun tryRunBackground(block: suspend () -> Unit): Boolean {
        if (lock.isLocked) return false
        backgroundJob = scope.launch {
            if (lock.tryLock()) {
                try {
                    block()
                } finally {
                    lock.unlock()
                }
            }
        }
        return true
    }
}
