package com.smriti.app.models

import android.os.SystemClock
import com.smriti.app.core.ModelInfo
import com.smriti.app.core.ModelRegistry
import com.smriti.app.pipeline.EventType
import com.smriti.app.pipeline.PipelineBus
import com.smriti.app.pipeline.PipelineEvent

/**
 * Owns the loaded model for each role and swaps them. A swap loads the new model BEFORE the old one
 * is released, so a failed load leaves the pipeline working with the previous model.
 * Callers must hold the inference lock while swapping (see SmritiEngine.selectModel) so no frame is
 * using a model while it is closed.
 */
class ModelHost {

    @Volatile var detector: DetectorModel? = null
        private set
    @Volatile var labeler: LabelerModel? = null
        private set
    @Volatile var ocr: OcrModel? = null
        private set

    private val loadTimes = HashMap<String, Long>()

    init {
        publishCatalog(selected = emptyMap())
    }

    fun selectedIds(): Map<Role, String?> = mapOf(
        Role.DETECTOR to detector?.spec?.id,
        Role.LABELER to labeler?.spec?.id,
        Role.OCR to ocr?.spec?.id
    )

    /** Loads (or replaces) the model for [id]'s role. Returns load time in ms, or throws and keeps the old model. */
    suspend fun select(id: String): Long {
        val spec = ModelCatalog.find(id) ?: error("Unknown model '$id'")
        check(spec.available) { "${spec.name} is not installed yet" }
        val previous = currentFor(spec.role)
        if (previous?.spec?.id == id) return 0

        publishLoading(spec)
        val t0 = SystemClock.elapsedRealtime()
        val fresh = ModelCatalog.create(id)
        try {
            fresh.load()
        } catch (e: Throwable) {
            fresh.close()
            ModelRegistry.put(ModelInfo(spec.name, spec.role.name.lowercase(), spec.backend, "failed", note = e.message.orEmpty()))
            PipelineBus.error("models", "load of ${spec.name} failed: ${e.message}")
            throw e
        }
        install(spec.role, fresh)
        previous?.close()
        val ms = SystemClock.elapsedRealtime() - t0
        loadTimes[id] = ms
        PipelineBus.emit(
            PipelineEvent(
                EventType.MODEL_SWAP, stage = spec.role.name.lowercase(), durMs = ms,
                message = "${previous?.spec?.name ?: "none"} -> ${spec.name}",
                data = mapOf("role" to spec.role.name, "from" to (previous?.spec?.id ?: ""), "to" to id)
            )
        )
        publishCatalog(selected = selectedIds())
        return ms
    }

    fun closeAll() {
        detector?.close(); labeler?.close(); ocr?.close()
        detector = null; labeler = null; ocr = null
    }

    private fun currentFor(role: Role): VisionModel? = when (role) {
        Role.DETECTOR -> detector
        Role.LABELER -> labeler
        Role.OCR -> ocr
        Role.CAPTIONER -> null
    }

    private fun install(role: Role, model: VisionModel) {
        when (role) {
            Role.DETECTOR -> detector = model as DetectorModel
            Role.LABELER -> labeler = model as LabelerModel
            Role.OCR -> ocr = model as OcrModel
            Role.CAPTIONER -> error("No captioner runtime yet")
        }
    }

    private fun publishLoading(spec: ModelSpec) =
        ModelRegistry.put(ModelInfo(spec.name, spec.role.name.lowercase(), spec.backend, "loading", note = spec.note))

    /** Mirrors the catalog into the registry the Sensors tab reads. */
    fun publishCatalog(selected: Map<Role, String?>) {
        val loadMs: Map<String, Long> = loadTimes
        for (s in ModelCatalog.specs) {
            val active = selected[s.role] == s.id
            ModelRegistry.put(
                ModelInfo(
                    name = s.name, role = s.role.name.lowercase(), backend = s.backend,
                    state = when {
                        !s.available -> "not installed"
                        active -> "ready"
                        else -> "available"
                    },
                    loadMs = loadMs[s.id] ?: -1, note = s.note
                )
            )
        }
    }
}
