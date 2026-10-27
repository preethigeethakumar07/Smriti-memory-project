package com.smriti.app.pipeline

import android.graphics.Bitmap
import com.smriti.app.memory.MemoryRepository
import com.smriti.app.memory.ZoneTracker
import com.smriti.app.models.DetectedObject
import com.smriti.app.perception.Spatial
import com.smriti.app.privacy.SensitiveFinding
import com.smriti.app.privacy.SensitiveScanner
import com.smriti.app.models.Label
import com.smriti.app.models.ModelHost
import com.smriti.app.perception.Perception
import com.smriti.app.privacy.Redactor
import com.smriti.app.privacy.TextScrubber
import com.smriti.app.util.Metrics
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FrameResult(
    val frameId: Long,
    val perception: Perception?,
    val faces: Int,
    val zone: String,
    val boxes: List<DetectedObject>,
    val totalMs: Long,
    val stored: Boolean,
    val errors: Int,
    /** true when the privacy stage failed: nothing was analysed or stored (fail closed) */
    val aborted: Boolean,
    /** sensitive things seen in this frame, masked */
    val sensitive: List<SensitiveFinding> = emptyList()
) {
    val caption: String get() = perception?.caption ?: if (aborted) "Skipped: the privacy step failed, so this frame was discarded." else "Nothing recognised."
}

/**
 * The frame -> text pipeline, one explicit stage at a time.
 *
 *   privacy -> { detect | label | ocr } -> zone -> compose -> store -> ledger
 *
 * Rules:
 *  - PRIVACY FAILS CLOSED. If face blurring fails, the frame is discarded; no model sees it and nothing is stored.
 *  - Every other stage fails open: the frame continues with what succeeded, the error is reported.
 *  - Detect / label / ocr run in parallel on the redacted copy.
 *  - Every stage emits START and OK/ERROR/SKIPPED/DISABLED events with real durations.
 */
class Pipeline(
    private val redactor: Redactor,
    private val models: ModelHost,
    private val zones: ZoneTracker,
    private val repo: MemoryRepository,
    val guard: StageGuard = StageGuard()
) {
    private val _config = MutableStateFlow(PipelineConfig())
    val config: StateFlow<PipelineConfig> = _config.asStateFlow()

    fun updateConfig(transform: (PipelineConfig) -> PipelineConfig) {
        val next = transform(_config.value)
        if (next == _config.value) return
        _config.value = next
        PipelineBus.emit(
            PipelineEvent(
                EventType.CONFIG, message = "config changed", data = mapOf(
                    "preset" to next.preset, "detector" to next.detectorId, "labeler" to next.labelerId, "ocr" to next.ocrId,
                    "detectOn" to next.detectOn, "labelOn" to next.labelOn, "ocrOn" to next.ocrOn, "preview" to next.preview
                )
            )
        )
    }

    /** Called with the redacted frame while preview is on. Consumer must not keep or modify the bitmap. */
    /** keeps a stable identity for each object across frames, using where it is in the room */
    val tracker = com.smriti.app.spatial.ObjectTracker()

    /** The visual memory. [wants] is cheap and says whether a copy of this frame is worth making; [submit] takes ownership of the bitmap. */
    interface VisualSink {
        fun wants(): Boolean
        fun submit(redacted: Bitmap, caption: String, place: String, source: String)
    }
    @Volatile var visualSink: VisualSink? = null

    @Volatile var previewSink:((frameId: Long, redacted: Bitmap, boxes: List<DetectedObject>, caption: String) -> Unit)? = null

    suspend fun run(frame: Bitmap, frameId: Long, source: String, store: Boolean, motion: Float = 0f, pose: com.smriti.app.spatial.Pose? = null, fov: com.smriti.app.spatial.Fov = com.smriti.app.spatial.Fov.TYPICAL): FrameResult {
        val cfg = _config.value
        val t0 = System.nanoTime()
        var errors = 0

        // ---- privacy (cannot be turned off; failure aborts the frame) ------------------------------------------
        val priv = guard.run(
            frameId, Stage.PRIVACY, fallback = null,
            summarise = { r: Redactor.Result? -> "${r?.faces ?: 0} face(s) blurred" to mapOf("faces" to (r?.faces ?: 0)) }
        ) { redactor.redact(frame) }
        val red = priv.value
        if (red == null) {
            val total = (System.nanoTime() - t0) / 1_000_000
            PipelineBus.emit(
                PipelineEvent(
                    EventType.FRAME_END, frameId = frameId, durMs = total, status = Status.ERROR,
                    message = "privacy stage failed, frame discarded", data = mapOf("aborted" to true, "stored" to false)
                )
            )
            return FrameResult(frameId, null, 0, ZoneTracker.UNKNOWN, emptyList(), total, stored = false, errors = 1, aborted = true)
        }

        // ---- the three model stages, in parallel, on the redacted copy -------------------------------------------
        val analysed: Triple<StageResult<List<DetectedObject>>, StageResult<List<Label>>, StageResult<String>> = coroutineScope {
            val d = async {
                guard.run(
                    frameId, Stage.DETECT, emptyList<DetectedObject>(), enabled = cfg.detectOn,
                    summarise = { l -> "${l.size} object(s)" to mapOf("count" to l.size, "labels" to l.map { it.label }.distinct().take(6)) }
                ) { models.detector?.detect(red.bitmap) ?: error("no detector loaded") }
            }
            val l = async {
                guard.run(
                    frameId, Stage.LABEL, emptyList<Label>(), enabled = cfg.labelOn,
                    summarise = { x -> "${x.size} label(s)" to mapOf("count" to x.size, "labels" to x.take(6).map { it.text }) }
                ) { models.labeler?.label(red.bitmap) ?: error("no labeler loaded") }
            }
            val o = async {
                guard.run(
                    frameId, Stage.OCR, "", enabled = cfg.ocrOn,
                    summarise = { s -> "${s.length} chars" to mapOf("chars" to s.length) }
                ) { models.ocr?.read(red.bitmap) ?: error("no text reader loaded") }
            }
            Triple(d.await(), l.await(), o.await())
        }
        val (det, lab, ocr) = analysed
        errors += listOf(det, lab, ocr).count { it.ran && !it.ok }

        // ---- sensitive content: name the KIND and show only a masked preview (never the real value) ---------------------
        val findings = if (source == "warmup") emptyList() else runCatching {
            SensitiveScanner.scan(ocr.value, red.faces, det.value.map { it.label } + lab.value.map { it.text })
        }.getOrDefault(emptyList())
        for (f in findings) {
            PipelineBus.emit(
                PipelineEvent(
                    EventType.SENSITIVE, frameId = frameId, stage = Stage.PRIVACY, status = Status.OK, message = "${f.kind.label} detected",
                    data = mapOf("kind" to f.kind.name, "label" to f.kind.label, "masked" to f.masked, "action" to f.kind.action, "source" to source)
                )
            )
        }

        // ---- zone -----------------------------------------------------------------------------------------------------
        val zone = guard.run(
            frameId, Stage.ZONE, ZoneTracker.UNKNOWN,
            summarise = { z: String -> z to mapOf("zone" to z) }
        ) { zones.classify(frame) }
        if (zone.ran && !zone.ok) errors++

        // ---- compose --------------------------------------------------------------------------------------------------
        val composed = guard.run(
            frameId, Stage.COMPOSE, null,
            summarise = { p: Perception? -> (p?.caption ?: "") to mapOf("objects" to (p?.objects?.size ?: 0)) }
        ) { compose(det.value, lab.value, TextScrubber.scrub(ocr.value), red.faces, cfg) }
        // where in the room each boxed object is (needs the phone orientation at the moment of the frame)
        val placed = if (pose != null && source != "warmup") det.value.filter { it.label != "object" }.distinctBy { it.label }.map {
            com.smriti.app.spatial.Spatial3D.place(it.label, it.box.left, it.box.top, it.box.right, it.box.bottom, pose, fov)
        } else emptyList()
        val perception = composed.value?.copy(motion = motion.coerceIn(0f, 1f), spatial = placed.associateBy { it.label })
        if (placed.isNotEmpty()) {
            val tracks = tracker.update(placed, System.currentTimeMillis())
            PipelineBus.emit(
                PipelineEvent(
                    EventType.SPATIAL, frameId = frameId, stage = Stage.ZONE, status = Status.OK,
                    message = "${tracks.size} object(s) placed in the room",
                    data = mapOf(
                        "objects" to tracks.map { t ->
                            val p = t.point
                            mapOf("id" to t.id, "name" to t.name, "label" to t.label, "x" to p.x, "y" to p.y, "z" to p.z, "dist" to p.distance, "az" to p.azimuthDeg, "el" to p.elevationDeg, "conf" to p.confidence)
                        },
                        "pose" to mapOf("az" to pose!!.azimuthDeg, "pitch" to pose.pitchDeg, "valid" to pose.headingValid),
                        "source" to source
                    )
                )
            )
        }
        if (composed.ran && !composed.ok) errors++
        // the place we remember: a zone the user taught, otherwise the room we recognised, otherwise unknown
        val place = if (zone.value != ZoneTracker.UNKNOWN) zone.value else perception?.room?.ifBlank { null } ?: ZoneTracker.UNKNOWN

        // ---- optional preview (redacted frame only, never stored) -----------------------------------------------------
        if (cfg.preview && perception != null) previewSink?.invoke(frameId, red.bitmap, det.value, perception.caption)
        // A small copy of the REDACTED frame goes to the visual memory (meaning vector + blurred thumbnail); the raw frame never does.
        val sink = visualSink
        val keep = if (store && perception != null && source != "warmup" && sink != null && sink.wants()) red.bitmap.copy(Bitmap.Config.ARGB_8888, false) else null
        // The redacted copy was only ever in RAM and is no longer needed.
        if (red.bitmap !== frame) red.bitmap.recycle()

        // ---- store + ledger ------------------------------------------------------------------------------------------
        val totalSoFar = (System.nanoTime() - t0) / 1_000_000
        var stored = false
        if (store && perception != null) {
            val s = guard.run(
                frameId, Stage.STORE, false,
                summarise = { saved: Boolean -> (if (saved) "note saved" else "same as last note, not duplicated") to mapOf("saved" to saved) }
            ) { repo.saveNote(perception, red.faces, place, totalSoFar, source) }
            stored = s.value
            if (s.ran && !s.ok) errors++
            val led = guard.run(
                frameId, Stage.LEDGER, Unit,
                summarise = { _: Unit -> "${perception.objects.size} item(s) updated" to mapOf("items" to perception.objects.size) }
            ) { repo.updateLedger(perception, place) }
            if (led.ran && !led.ok) errors++
        } else {
            PipelineBus.emit(PipelineEvent(EventType.STAGE, frameId = frameId, stage = Stage.STORE, status = Status.SKIPPED, message = if (store) "nothing to store" else "not stored (warm-up or benchmark)"))
        }
        if (keep != null) {
            if (sink != null) sink.submit(keep, perception?.caption.orEmpty(), place, source) else keep.recycle()
        }

        val total = (System.nanoTime() - t0) / 1_000_000
        Metrics.record(Metrics.TOTAL, total)
        PipelineBus.emit(
            PipelineEvent(
                EventType.FRAME_END, frameId = frameId, durMs = total, status = if (errors == 0) Status.OK else Status.ERROR,
                message = perception?.caption.orEmpty(),
                data = mapOf("stored" to stored, "errors" to errors, "faces" to red.faces, "zone" to zone.value, "source" to source)
            )
        )
        return FrameResult(frameId, perception, red.faces, zone.value, det.value, total, stored, errors, aborted = false, sensitive = findings)
    }

    // ---- caption composition (template today; the NPU captioner will replace this one function) -----------------------

    private fun compose(
        boxes: List<DetectedObject>, labels: List<Label>, text: String, people: Int, cfg: PipelineConfig
    ): Perception {
        val generic = setOf("room", "interior design", "floor", "flooring", "wall", "ceiling", "building", "design", "pattern", "darkness", "light", "sky", "shelf")
        val fromLabels = labels.map { it.text }.filter { it !in generic }
        val fromBoxes = boxes.map { it.label }.filter { it != "object" }
        // pills are rarely a model class; medicine words on a bottle or strip are the evidence
        val medicine = if (Spatial.looksLikeMedicine(text)) listOf("medicine") else emptyList()
        val objects = (fromLabels + fromBoxes + medicine).distinct()

        val seen = if (objects.isEmpty()) "Nothing distinctive in view." else "I see ${natural(objects.take(5))}."
        val ppl = when (people) {
            0 -> ""
            1 -> " 1 person is nearby."
            else -> " $people people are nearby."
        }
        val txt = if (text.isNotBlank()) " Visible text: \"${text.take(80)}\"." else ""
        val backend = "${cfg.detectorId} + ML Kit (${cfg.preset})"
        // relations from the boxes; for anything without one, name what else was around so the answer is never only a time
        val vague = setOf("metal", "monochrome", "black-and-white", "material property", "font", "rectangle", "circle", "line", "tints and shades", "electric blue", "azure", "gas", "technology", "pattern", "wheel", "tire", "auto part")
        val rel = Spatial.relations(boxes).toMutableMap()
        for (o in objects) if (o !in rel) {
            val around = objects.filter { it != o && it !in vague }.take(2)
            if (around.isNotEmpty()) rel[o] = "with ${around.joinToString(" and ")} around"
        }
        return Perception(seen + ppl + txt, objects, text, backend, where = rel, room = Spatial.room(labels, boxes), density = (Spatial.relations(boxes).size / 4f + people * 0.25f).coerceIn(0f, 1f))
    }

    private fun natural(items: List<String>): String = when (items.size) {
        1 -> "a ${items[0]}"
        2 -> "a ${items[0]} and a ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") { "a $it" } + " and a ${items.last()}"
    }
}
