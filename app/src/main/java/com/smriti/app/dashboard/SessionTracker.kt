package com.smriti.app.dashboard

import android.os.Build
import com.smriti.app.BuildConfig
import com.smriti.app.core.SmritiEngine
import com.smriti.app.data.AppSettings
import com.smriti.app.memory.AppDatabase
import com.smriti.app.memory.SessionEntity
import com.smriti.app.pipeline.EventType
import com.smriti.app.pipeline.PipelineBus
import com.smriti.app.pipeline.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records one row per app run (who, when, how much) for the dashboard's Users page.
 * Text only: no frames, no faces, nothing that identifies anyone beyond the name they typed in setup.
 */
class SessionTracker(
    scope: CoroutineScope,
    private val db: AppDatabase,
    private val settings: StateFlow<AppSettings?>,
    private val engine: SmritiEngine
) {
    @Volatile var currentId: Long = 0
        private set
    val startedAt: Long = System.currentTimeMillis()
    private val errors = AtomicInteger()

    init {
        scope.launch {
            PipelineBus.events.collect { if (it.type == EventType.STAGE && it.status == Status.ERROR) errors.incrementAndGet() }
        }
        scope.launch {
            val s = settings.filterNotNull().first()
            currentId = db.sessionDao().insert(
                SessionEntity(
                    startedAt = startedAt, endedAt = startedAt, userName = s.name, language = s.language,
                    device = "${Build.MANUFACTURER} ${Build.MODEL}", appVersion = BuildConfig.VERSION_NAME, consentVersion = s.consentVersion
                )
            )
            while (true) {
                delay(10_000)
                save()
            }
        }
    }

    private suspend fun save() {
        val id = currentId.takeIf { it > 0 } ?: return
        val s = settings.value
        val st = engine.state.value
        db.sessionDao().update(id, System.currentTimeMillis(), st.framesProcessed, st.memories, errors.get(), s?.name.orEmpty(), s?.language.orEmpty())
    }

    suspend fun list(): List<Map<String, Any>> {
        save()
        return db.sessionDao().recent(50).map {
            mapOf(
                "id" to it.id, "startedAt" to it.startedAt, "endedAt" to it.endedAt, "user" to it.userName, "language" to it.language,
                "device" to it.device, "appVersion" to it.appVersion, "consent" to it.consentVersion,
                "frames" to it.framesProcessed, "notes" to it.notesStored, "errors" to it.errors, "live" to (it.id == currentId)
            )
        }
    }
}
