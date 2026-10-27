package com.smriti.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.smriti.app.gate.SceneChangeGate
import com.smriti.app.memory.AppDatabase
import com.smriti.app.memory.MemoryRepository
import com.smriti.app.memory.ZoneTracker
import com.smriti.app.models.ModelCatalog
import com.smriti.app.models.ModelHost
import com.smriti.app.models.Role
import com.smriti.app.pipeline.EventType
import com.smriti.app.pipeline.Pipeline
import com.smriti.app.pipeline.PipelineBus
import com.smriti.app.pipeline.PipelineConfig
import com.smriti.app.pipeline.PipelineEvent
import com.smriti.app.pipeline.Stage
import com.smriti.app.pipeline.Status
import com.smriti.app.pipeline.WarmupFrames
import com.smriti.app.privacy.Redactor
import com.smriti.app.privacy.TextScrubber
import com.smriti.app.query.Answerer
import com.smriti.app.query.Intent
import com.smriti.app.query.IntentParser
import com.smriti.app.query.Localizer
import com.smriti.app.query.MultiLang
import com.smriti.app.util.Metrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the pipeline and everything around it: frame intake from any source, the gate, scheduling,
 * model selection and the question path. Frames never touch disk; only text rows are persisted.
 *
 *   camera / glasses -> gate -> Pipeline (privacy -> detect|label|ocr -> zone -> compose -> store -> ledger)
 */
class SmritiEngine(context: Context) {

    data class UiState(
        val caption: String = "Warming up models...",
        val zone: String = ZoneTracker.UNKNOWN,
        val memories: Int = 0,
        val framesSeen: Long = 0,
        val framesGated: Long = 0,
        val framesProcessed: Long = 0,
        val framesDroppedBusy: Long = 0,
        val backend: String = "-",
        val ready: Boolean = false,
        val backgroundOn: Boolean = true,
        val source: String = "phone",
        val lastFaces: Int = 0,
        /** what the detector found in the newest analysed frame, boxes normalised 0..1 (never the pixels) */
        val detections: List<com.smriti.app.models.DetectedObject> = emptyList(),
        val detectionsAt: Long = 0,
        /** how many sensitive things of each kind have been caught since the app started (counts only) */
        val sensitiveCounts: Map<String, Int> = emptyMap(),
        val lastAnswerMs: Long = 0,
        val trippedStages: Set<String> = emptySet()
    )

    private val appContext = context.applicationContext

    /** A crash in any background job must never take the app down: it becomes a logged error instead. */
    private val handler = CoroutineExceptionHandler { _, e ->
        PipelineBus.error("engine", "uncaught in background job: ${e.javaClass.simpleName}: ${e.message}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
    private val db = AppDatabase.get(appContext)

    val repo = MemoryRepository(db)
    val governor = ThermalGovernor(appContext)
    val models = ModelHost()
    val zones = ZoneTracker(db.zoneDao())
    private val scheduler = InferenceScheduler(scope)
    private val gate = SceneChangeGate()
    private val redactor = Redactor()
    val pipeline = Pipeline(redactor, models, zones, repo)

    // ---- on-device generative + embedding models (optional: the app works without their files) ----------------------
    val embedder = com.smriti.app.embed.GemmaEmbedder(appContext)
    val visual = com.smriti.app.ai.VisualMemory(appContext, db.visualDao(), embedder)
    val brain = com.smriti.app.ai.Brain(appContext)
    /** why recording is slow right now, or null */
    fun thermalNote(): String? = when {
        governor.sessionPaused -> "The phone is critically hot, so recording is paused until it cools down."
        governor.paused -> "The phone is hot, so Smriti records less often to cool down."
        else -> null
    }

    /** camera sessions: start when the camera opens in Live, end when it closes */
    val momentSearch = com.smriti.app.ai.MomentSearch(visual, brain)
    /** shows the user where one named object is in the live camera view */
    /** the sensitive-scene filter; switched by the setting of the same name */
    @Volatile var sensitiveFilter = true
    val guard = com.smriti.app.privacy.PrivacyGuard(embedder, brain) { sensitiveFilter }
    val guide = com.smriti.app.ai.Guide(scope, { guideFrame() }, embedder, brain, { governor.paused }) { pic -> guard.screenFrame(pic) }

    /** an upright copy of the newest camera picture, for the guide to cut up; the caller recycles it */
    private fun guideFrame(): Bitmap? {
        val (f, _) = latestRotated() ?: return null
        return runCatching { f.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
    }
    val sessions = com.smriti.app.ai.SessionRecorder(db.cameraSessionDao(), db.visualDao(), visual, momentSearch, scope)
    val cameraSessions: kotlinx.coroutines.flow.Flow<List<com.smriti.app.memory.CameraSessionEntity>> get() = db.cameraSessionDao().all()
    suspend fun saveSessionResult(id: Long, text: String) {
        val s = db.cameraSessionDao().byId(id) ?: return
        db.cameraSessionDao().update(com.smriti.app.memory.CameraSessionEntity(s.id, s.startedAt, s.endedAt, s.source, s.place, s.cover, s.segments, s.hasSound, s.open, s.target, text))
    }
    suspend fun sessionStart(id: Long): Long = db.cameraSessionDao().byId(id)?.startedAt ?: 0L
    fun sessionMoments(id: Long) = db.visualDao().forSessionFlow(id)
    val actions = com.smriti.app.ai.Actions(appContext)
    /** a remembered moment's picture to show with the answer just returned by [ask] (app-private file path) */
    @Volatile var lastAnswerImage: String? = null
    private val visualBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    /** the language chosen in Settings (BCP-47); answers are written in it when a voice and wording exist for it */
    @Volatile var language: String = "en-IN"
    @Volatile var ttsHasVoice: (String) -> Boolean = { true }

    /** "hi-IN" or "en-IN": what the next answer will be written and spoken in */
    fun replyTag(): String = if (brain.ready) "en-IN" else Localizer.replyTag(language, ttsHasVoice(language))   // with Gemma loaded, answers are built in English and translated

    /** orientation sensor; started while the app is on screen or a Live session runs */
    val pose = com.smriti.app.spatial.PoseSensor(context.applicationContext)
    private val fov by lazy { com.smriti.app.spatial.CameraOptics.fov(appContext) }

    /** the object the user just asked about, so the Live screen can point at it */
    private val _nav = kotlinx.coroutines.flow.MutableStateFlow<NavTarget?>(null)
    val nav: kotlinx.coroutines.flow.StateFlow<NavTarget?> = _nav
    data class NavTarget(val point: com.smriti.app.spatial.Point3, val at: Long)
    fun clearNav() { _nav.value = null }

    private val answerer = Answerer(repo, { replyTag() }, { pose.snapshot() }) { p -> _nav.value = NavTarget(p, SystemClock.elapsedRealtime()) }

    val config: StateFlow<PipelineConfig> get() = pipeline.config

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private class Latest(val bitmap: Bitmap, val rotation: Int, val source: String, val pose: com.smriti.app.spatial.Pose?)
    @Volatile private var latest: Latest? = null

    private val lastTick = AtomicLong(0)
    /** least time between two camera frames taken for analysis; set by the power policy (Live is quicker than Home, a hot phone is slower) */
    @Volatile var tickMs = 500L
    /** multiplies the gap between remembered moments outside a recording; set by the power policy */
    @Volatile var ambientGapScale = 1
    private val seen = AtomicLong()
    private val gated = AtomicLong()
    private val processed = AtomicLong()
    private val dropped = AtomicLong()

    /** Total time the pipeline has been working, for the "accelerator busy (est.)" figure. */
    val busyNanos = AtomicLong()

    /** Background capture interval at normal temperature (set from Settings). */
    @Volatile private var baseIntervalMs = 4_000L

    // gate decisions are summarised once a second so the event stream stays readable
    private val gateReasons = ConcurrentHashMap<String, Int>()
    @Volatile private var gateWindowStart = SystemClock.elapsedRealtime()
    @Volatile private var lastGateScore = 0f

    init {
        visual.screen = { v, pic -> guard.isSensitive(v, pic) }
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val cfg = pipeline.config.value
            for (id in listOf(cfg.detectorId, cfg.labelerId, cfg.ocrId)) {
                runCatching { models.select(id) }.onFailure {
                    PipelineBus.error("models", "could not load $id: ${it.message}")
                    // The default detector must never leave the pipeline blind: fall back to the ML Kit one.
                    if (id == "mp-efficientdet") runCatching { models.select("mlkit-objects") }
                }
            }
            zones.load()
            warmUp()
            val ms = SystemClock.elapsedRealtime() - t0
            PipelineBus.info("engine", "ready in $ms ms")
            _state.update { it.copy(ready = true, backend = "ML Kit (${pipeline.config.value.preset})", caption = "Ready. Point the camera at something.") }
        }
        scope.launch { repo.count.collect { n -> _state.update { it.copy(memories = n) } } }
        startInventory()

        // heavy models load after the camera pipeline is up, one after the other so start-up memory stays flat
        scope.launch {
            val e = embedder.load()
            PipelineBus.info("embed", "EmbeddingGemma: $e")
            val b = brain.load()
            PipelineBus.info("brain", "Gemma-4: $b")
            PipelineBus.info("actions", "FunctionGemma: ${actions.load()}")
        }
    }

    /** Runs realistic synthetic frames through every stage so the first real frame is not a cold start. */
    private suspend fun warmUp() {
        runCatching {
            repeat(2) {
                val frame = WarmupFrames.scene()
                pipeline.run(frame, frameId = 0, source = "warmup", store = false)
                frame.recycle()
            }
        }.onFailure { PipelineBus.warn("engine", "warm-up failed: ${it.message}") }
        Metrics.reset()          // warm-up timings are not real measurements
        busyNanos.set(0)
    }

    // ---- frame input ---------------------------------------------------------------------------------------------

    /** Phone camera path. Always closes the proxy and never throws into the camera thread. */
    fun onFrame(proxy: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            val prev = lastTick.get()
            if (now - prev < tickMs || !lastTick.compareAndSet(prev, now)) return
            ingest(proxy.toBitmap(), proxy.imageInfo.rotationDegrees, "phone", now)
        } catch (e: Throwable) {
            PipelineBus.error("camera", "frame intake failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            proxy.close()
        }
    }

    /** Any other source (glasses). The bitmap is kept in RAM only. */
    fun onBitmap(bmp: Bitmap, source: String) {
        try {
            val now = SystemClock.elapsedRealtime()
            val prev = lastTick.get()
            if (now - prev < tickMs || !lastTick.compareAndSet(prev, now)) return
            ingest(bmp, 0, source, now)
        } catch (e: Throwable) {
            PipelineBus.error("camera", "frame intake failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun ingest(bmp: Bitmap, rotation: Int, source: String, now: Long) {
        seen.incrementAndGet()
        val framePose = pose.snapshot()      // the phone orientation at the moment of THIS frame
        latest = Latest(bmp, rotation, source, framePose)
        if (sessions.active) sessions.touch()
        if (guard.blocked) return skip("privacy", source)           // after a sensitive picture everything pauses for a few seconds
        captureMoment(bmp, rotation, source)

        val s = _state.value
        when {
            !s.ready -> return skip("warming-up", source)
            !s.backgroundOn -> return skip("memory-off", source)
            governor.paused -> return skip("thermal", source)
            scheduler.isBusy -> { dropped.incrementAndGet(); return skip("busy", source) }
        }

        val interval = baseIntervalMs * governor.intervalMultiplier
        val d = Metrics.time(Metrics.GATE) { gate.evaluate(bmp, now, interval) }
        lastGateScore = d.score
        if (!d.accepted) {
            gated.incrementAndGet()
            return skip(d.reason, source)
        }

        val frameId = PipelineBus.nextFrameId()
        flushGate()
        PipelineBus.emit(PipelineEvent(EventType.FRAME_START, frameId = frameId, stage = Stage.CAMERA, status = Status.OK, message = source, data = mapOf("source" to source, "w" to bmp.width, "h" to bmp.height)))
        PipelineBus.emit(PipelineEvent(EventType.GATE, frameId = frameId, stage = Stage.GATE, status = Status.OK, message = d.reason, data = mapOf("score" to d.score, "threshold" to gate.threshold, "reason" to d.reason)))
        val motion = (d.score / 0.25f).coerceIn(0f, 1f)   // 0.25 mean pixel change is a very busy scene
        scheduler.tryRunBackground { processFrame(frameId, bmp, rotation, store = true, source = source, motion = motion, poseAt = framePose) }
    }

    /**
     * Every couple of seconds while the camera is on, the frame is blurred (faces) and kept as a picture memory, whether or not
     * the scene changed, so "what happened at 9:28" can always be answered with a picture. This path is separate from the
     * detector pipeline: it only needs the privacy blur and the embedding model, so it keeps going while detection is busy.
     */
    private fun captureMoment(bmp: Bitmap, rotation: Int, source: String) {
        val s = _state.value
        val inSession = sessions.active
        visual.gapScale = if (inSession) governor.sessionGapScale else ambientGapScale
        val heldBack = if (inSession) governor.sessionPaused else governor.paused
        if (!s.ready || (!s.backgroundOn && !inSession) || heldBack || !visual.due() || !visualBusy.compareAndSet(false, true)) return
        val copy = try { bmp.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Throwable) { visualBusy.set(false); return }
        val caption = s.caption
        val place = s.zone
        scope.launch {
            var upright: Bitmap? = null
            var red: Redactor.Result? = null
            try {
                upright = if (rotation == 0) copy else rotated(copy, rotation)
                red = redactor.redact(upright)
                visual.remember(red.bitmap, caption, place, source)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                PipelineBus.warn("embed", "moment capture failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                if (red != null && red.bitmap !== upright) red.bitmap.recycle()
                if (upright != null && upright !== copy) upright.recycle()
                copy.recycle()
                visualBusy.set(false)
            }
        }
    }

    private fun skip(reason: String, source: String) {
        gateReasons.merge(reason, 1, Int::plus)
        publishCounters(source)
        val now = SystemClock.elapsedRealtime()
        if (now - gateWindowStart >= 1000) flushGate()
    }

    private fun flushGate() {
        val snapshot = HashMap(gateReasons)
        gateReasons.clear()
        gateWindowStart = SystemClock.elapsedRealtime()
        if (snapshot.isEmpty()) return
        PipelineBus.emit(
            PipelineEvent(
                EventType.GATE, stage = Stage.GATE, status = Status.SKIPPED,
                message = snapshot.entries.joinToString { "${it.key} x${it.value}" },
                data = mapOf("counts" to snapshot, "total" to snapshot.values.sum(), "score" to lastGateScore, "threshold" to gate.threshold)
            )
        )
    }

    private fun publishCounters(source: String) {
        _state.update {
            it.copy(
                framesSeen = seen.get(), framesGated = gated.get(),
                framesProcessed = processed.get(), framesDroppedBusy = dropped.get(), source = source,
                trippedStages = pipeline.guard.trippedStages()
            )
        }
    }

    // ---- processing ------------------------------------------------------------------------------------------------

    private suspend fun processFrame(frameId: Long, bmp: Bitmap, rotation: Int, store: Boolean, source: String, motion: Float = 0f, poseAt: com.smriti.app.spatial.Pose? = null) {
        val t0 = System.nanoTime()
        val frame = if (rotation == 0) bmp else rotated(bmp, rotation)   // rotate only frames the gate accepted
        try {
            val r = pipeline.run(frame, frameId, source, store, motion, poseAt, fov)
            busyNanos.addAndGet(System.nanoTime() - t0)
            com.smriti.app.ai.AiMeter.record("ML Kit + EfficientDet", "camera frames: faces, objects, text", "CPU / GPU", (System.nanoTime() - t0) / 1_000_000)
            processed.incrementAndGet()
            _state.update { it.copy(caption = r.caption, zone = r.zone, lastFaces = r.faces, detections = r.boxes, detectionsAt = SystemClock.elapsedRealtime(), sensitiveCounts = countSensitive(it.sensitiveCounts, r.sensitive)) }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Throwable) {
            PipelineBus.error("engine", "frame $frameId failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            if (frame !== bmp) frame.recycle()
            publishCounters(source)
        }
    }

    private fun countSensitive(cur: Map<String, Int>, found: List<com.smriti.app.privacy.SensitiveFinding>): Map<String, Int> {
        if (found.isEmpty()) return cur
        val next = HashMap(cur)
        for (f in found) next.merge(f.kind.name, 1, Int::plus)
        return next
    }

    // ---- user actions -----------------------------------------------------------------------------------------------

    private fun latestRotated(): Pair<Bitmap, String>? {
        val l = latest ?: return null
        return (if (l.rotation == 0) l.bitmap else rotated(l.bitmap, l.rotation)) to l.source
    }

    /**
     * Writes the object list of every remembered moment, one after another and only when Gemma-4 is free and the phone is not hot,
     * so a question asked right now is never kept waiting for it. Only the newest waiting moment is kept when the camera is faster
     * than the model; the others keep their detector caption and stay findable by their picture vector.
     */
    private fun startInventory() {
        val queue = kotlinx.coroutines.channels.Channel<Pair<Long, Bitmap>>(kotlinx.coroutines.channels.Channel.CONFLATED) { it.second.recycle() }
        // while the user is in a Live session Gemma-4 belongs to their voice and questions: nothing is inventoried then
        visual.onSaved = { id, bmp -> if (!inventoryAllowed() || queue.trySend(id to bmp).isFailure) bmp.recycle() }
        // when a camera session ends, its windows are inventoried in the background (a spread of them, one at a time)
        scope.launch {
            var prev: Long? = null
            sessions.current.collect { cur ->
                val ended = prev; prev = cur
                if (ended != null && cur == null) scope.launch { backfillInventory(ended) }
            }
        }
        scope.launch {
            var lastAt = 0L
            for ((id, bmp) in queue) {
                try {
                    while (!brain.ready || brain.busy || !inventoryAllowed()) kotlinx.coroutines.delay(700)
                    val wait = INVENTORY_GAP_MS - (SystemClock.elapsedRealtime() - lastAt)
                    if (wait > 0) kotlinx.coroutines.delay(wait)
                    if (governor.paused || governor.sessionPaused) continue
                    val t = SystemClock.elapsedRealtime()
                    val raw = brain.ask(com.smriti.app.ai.Inventory.SYSTEM, com.smriti.app.ai.Inventory.QUESTION, bmp, maxChars = 500, background = true)
                    lastAt = SystemClock.elapsedRealtime()
                    val objects = com.smriti.app.ai.Inventory.parse(raw)
                    if (objects.isNotEmpty()) visual.annotate(id, objects)
                    PipelineBus.info("inventory", "moment $id: ${objects.size} objects in ${lastAt - t} ms")
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Throwable) {
                    PipelineBus.warn("inventory", "failed: ${e.javaClass.simpleName}: ${e.message}")
                } finally { bmp.recycle() }
            }
        }
    }

    /** set by the app: false while a Live session is open, so the inventory never competes with the user's voice */
    @Volatile var inventoryAllowed: () -> Boolean = { true }

    /** writes the object list for up to 10 windows of a finished session that have none, spread across the session */
    private suspend fun backfillInventory(sessionId: Long) {
        val rows = visual.all().map { it.first }.filter { it.sessionId == sessionId && !it.caption.contains(com.smriti.app.ai.Inventory.MARK) }
        if (rows.isEmpty()) return
        val pick = if (rows.size <= 10) rows else List(10) { rows[it * (rows.size - 1) / 9] }
        for (e in pick) {
            while (!brain.ready || brain.busy || !inventoryAllowed()) kotlinx.coroutines.delay(1000)     // wait until the user's Live session is over
            if (governor.paused || governor.sessionPaused) return      // too hot: the rest stays picture-only
            val bmp = runCatching { com.smriti.app.security.SecureFiles.decodeBitmap(visual.fileFor(e.thumb)) }.getOrNull() ?: continue
            try {
                val raw = brain.ask(com.smriti.app.ai.Inventory.SYSTEM, com.smriti.app.ai.Inventory.QUESTION, bmp, maxChars = 500, background = true)
                val objects = com.smriti.app.ai.Inventory.parse(raw)
                if (objects.isNotEmpty()) visual.annotate(e.id, objects)
            } catch (c: CancellationException) { throw c } catch (_: Throwable) { } finally { bmp.recycle() }
        }
        PipelineBus.info("inventory", "session #$sessionId: object lists written for ${pick.size} windows")
    }

    /** "What do you see": foreground priority, cancels background indexing. */
    suspend fun describeNow(question: String = "What do you see?"): String {
        val (frame, src) = latestRotated() ?: return if (Localizer.isHindi(replyTag())) Localizer.cameraNotReady() else "The camera isn't ready yet."
        if (guard.screenFrame(frame)) return "I can't describe this view because it may show something private."
        val id = PipelineBus.nextFrameId()
        PipelineBus.emit(PipelineEvent(EventType.FRAME_START, frameId = id, stage = Stage.CAMERA, status = Status.OK, message = "ask", data = mapOf("source" to src, "forced" to true)))
        val t0 = System.nanoTime()
        val r = scheduler.runForeground { pipeline.run(frame, id, src, store = true, pose = latest?.pose, fov = fov) }
        busyNanos.addAndGet(System.nanoTime() - t0)
        processed.incrementAndGet()
        _state.update { it.copy(caption = r.caption, zone = r.zone, lastFaces = r.faces, detections = r.boxes, detectionsAt = SystemClock.elapsedRealtime(), sensitiveCounts = countSensitive(it.sensitiveCounts, r.sensitive)) }
        publishCounters(src)
        if (Localizer.isHindi(replyTag())) return Localizer.looking(r.perception?.objects.orEmpty())
        // Gemma-4 looks at the privacy-blurred frame and answers in words; the template caption is the fallback
        if (brain.ready) {
            val t1 = SystemClock.elapsedRealtime()
            val seen = runCatching { redactor.redact(frame) }.getOrNull()
            if (com.smriti.app.BuildConfig.DEBUG) {
                // debug builds keep the picture Gemma-4 was just shown, to check what it actually saw (never in release builds)
                runCatching { java.io.File(appContext.cacheDir, "describe-last.png").outputStream().use { o -> (seen?.bitmap ?: frame).compress(Bitmap.CompressFormat.PNG, 90, o) } }
                PipelineBus.info("brain", "describe frame ${frame.width}x${frame.height} config=${frame.config} sent=${seen?.bitmap?.let { "${it.width}x${it.height}" }} faces=${seen?.faces}")
            }
            val said = try {
                val list = com.smriti.app.ai.Inventory.wantsList(question)
                brain.ask(
                    system = if (list) com.smriti.app.ai.Inventory.LIVE_LIST_SYSTEM else com.smriti.app.ai.Inventory.LIVE_ASK_SYSTEM,
                    question = question,
                    image = seen?.bitmap,
                    maxChars = if (list) 1200 else 600,
                    forUser = !list
                )
            } finally { if (seen != null && seen.bitmap !== frame) seen.bitmap.recycle() }
            PipelineBus.info("brain", "live description ${SystemClock.elapsedRealtime() - t1} ms")
            if (!said.isNullOrBlank()) return said
        }
        return r.caption
    }

    /**
     * One question in, one answer out, in the language chosen in Settings. The work in between is done in English (that is
     * what the memory notes and rules are written in); Gemma-4 translates the question in and the answer out, so every
     * answer, including "flashlight on" and "I last saw your laptop at 9:28 PM", comes back in the chosen language.
     */
    suspend fun ask(raw: String): String {
        val orig = TextScrubber.scrub(raw)
        val q = if (brain.ready && !mostlyLatin(orig)) (brain.translate(orig, "English") ?: orig) else orig
        return localize(askEnglish(orig, q))
    }

    /** Language for replies: the chosen one when it is not English, Gemma is loaded and the phone has a voice for it. */
    private fun targetLanguage(): Pair<String, String>? {
        if (!brain.ready) return null
        val tag = language
        if (tag.startsWith("en", true) || !ttsHasVoice(tag)) return null
        val name = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH)
        return if (name.isBlank()) null else tag to name
    }

    /** The voice to speak the answer with: the chosen language when answers are written in it, English otherwise. */
    fun speakTag(): String = targetLanguage()?.first ?: replyTag()

    suspend fun localize(answer: String): String {
        val t = targetLanguage() ?: return answer
        if (!mostlyLatin(answer)) return answer              // Gemma already wrote it in the chosen language
        return brain.translate(answer, t.second) ?: answer
    }

    private fun mostlyLatin(s: String): Boolean {
        val letters = s.filter { it.isLetter() }
        return letters.isEmpty() || letters.count { it.code < 0x250 }.toFloat() / letters.length >= 0.7f
    }

    /** a spoken "forget" waits for a spoken "yes" so a misheard word can never erase memories */
    private var pendingForget: Pair<Int, Long>? = null

    private suspend fun askEnglish(orig: String, q: String): String {
        pendingForget?.let { (minutes, at) ->
            pendingForget = null
            if (SystemClock.elapsedRealtime() - at < 30_000 && Regex("^\\W*(yes|yeah|yep|confirm|do it|go ahead|sure|forget it|yes forget it)\\b", RegexOption.IGNORE_CASE).containsMatchIn(q)) {
                return answerer.answer(Intent.Forget(minutes)).also { visual.forgetSince(System.currentTimeMillis() - minutes * 60_000L); sessions.reconcile() }
            }
        }
        val t0 = SystemClock.elapsedRealtime()
        PipelineBus.emit(PipelineEvent(EventType.QUERY, stage = Stage.INTENT, status = Status.START, message = q))
        // spoken Hindi, Tamil, Telugu... is read by meaning first; anything it does not recognise goes to the English router
        val intent = MultiLang.parse(orig, language) ?: IntentParser.parse(q)
        PipelineBus.emit(
            PipelineEvent(EventType.QUERY, stage = Stage.INTENT, status = Status.OK, durMs = SystemClock.elapsedRealtime() - t0, message = intent::class.simpleName.orEmpty(), data = mapOf("intent" to intent.toString().take(120)))
        )
        val tr = SystemClock.elapsedRealtime()
        PipelineBus.emit(PipelineEvent(EventType.QUERY, stage = Stage.RETRIEVE, status = Status.START))
        lastAnswerImage = null
        // "turn on the flashlight", "set a timer": the phone does it (FunctionGemma picks the function)
        if (actions.looksLikeAction(q)) {
            val did = runCatching { actions.run(q) }.getOrNull()
            if (did != null) {
                val now2 = SystemClock.elapsedRealtime()
                PipelineBus.emit(PipelineEvent(EventType.QUERY, stage = Stage.ANSWER, status = Status.OK, durMs = now2 - t0, message = did.take(140)))
                _state.update { it.copy(lastAnswerMs = now2 - t0) }
                return did
            }
        }
        // questions about what is in front of the camera RIGHT NOW ("how many people", "read this", "what colour is it")
        val present = intent is Intent.Live || (brain.ready && isPresentVisual(q))
        val answer = Metrics.time(Metrics.ANSWER) {
            when {
                present -> describeNow(q)
                intent is Intent.Forget -> {
                    pendingForget = intent.minutes to SystemClock.elapsedRealtime()
                    "This will erase the last ${if (intent.minutes >= 60 && intent.minutes % 60 == 0) "${intent.minutes / 60} hour${if (intent.minutes > 60) "s" else ""}" else "${intent.minutes} minutes"} of notes and pictures. Say yes to confirm."
                }
                intent is Intent.Where || intent is Intent.Search || intent is Intent.Range -> memoryOrBrain(q, intent)
                else -> answerer.answer(intent)
            }
        }
        val now = SystemClock.elapsedRealtime()
        PipelineBus.emit(PipelineEvent(EventType.QUERY, stage = Stage.RETRIEVE, status = Status.OK, durMs = now - tr, message = if (present) "live frame" else "memory"))
        PipelineBus.emit(PipelineEvent(EventType.QUERY, stage = Stage.ANSWER, status = Status.OK, durMs = now - t0, message = answer.lineSequence().first().take(140)))
        _state.update { it.copy(lastAnswerMs = now - t0) }
        return answer
    }

    private fun isPresentVisual(q: String): Boolean {
        val t = q.lowercase()
        val past = Regex("\\b(did|was|were|last|earlier|ago|yesterday|before|today|minutes?|hours?|morning|evening|night|remember|where (is|are|did|do)|forget)\\b|\\bat \\d").containsMatchIn(t)
        if (past) return false
        // only questions that clearly point at the camera view; ordinary questions ("how many days in a year") go to the general path
        val here = "(here|in front|visible|in (the|this) (room|picture|frame|image|view|scene)|on (the|my) (table|desk|screen|wall|shelf|floor)|you see|do you see)"
        if (Regex("\\b(seeing|looking at|see now|on (the |my |your )?screen|in front of (me|you|the camera)|through the camera|camera view|in the (frame|picture|view)|opened the camera|right now)\\b").containsMatchIn(t) &&
            !Regex("\\b(did|was|were|saw|seen)\\b").containsMatchIn(t)) return true
        return Regex("\\bhow many (people|persons?|men|women|children|kids|faces|chairs|objects|things|books|bottles|phones|laptops|windows|doors)\\b").containsMatchIn(t) ||
            Regex("\\bhow many .*\\b$here").containsMatchIn(t) ||
            Regex("\\bwhat colou?r (is|are) (this|that|these|those|it|the (shirt|wall|car|bag|bottle|door|cup|chair|table|screen))\\b").containsMatchIn(t) ||
            Regex("\\bwhat (is|are) (written|that|this|these|those|in front of me|on (the|my) (screen|table|desk|wall|paper|board))\\b").containsMatchIn(t) ||
            Regex("\\bread (the|this|that|it|what)\\b").containsMatchIn(t) ||
            Regex("\\b(is|are) there (a|an|any|someone|anyone|anything)\\b.*\\b$here").containsMatchIn(t) ||
            Regex("\\b(can|do) you see\\b").containsMatchIn(t) ||
            Regex("\\bwhat (brand|kind|type|model|breed) (is|of)\\b.*\\b(this|that|it|here)\\b").containsMatchIn(t)
    }

    /**
     * "Where did you see my mouse", "my pills", or anything else: the written memory answers first; the picture memory
     * (EmbeddingGemma, by meaning) adds the matching moment with its thumbnail; and only when Smriti has neither does
     * Gemma-4 answer as a general assistant, so it never invents something about the user's own past.
     */
    private suspend fun memoryOrBrain(q: String, intent: Intent): String {
        val subject = when (intent) { is Intent.Where -> intent.item; is Intent.Search -> intent.text; else -> q }
        if (intent is Intent.Search && brain.ready && looksGeneral(q)) generalAnswer(q)?.let { return it }

        val template = answerer.answer(intent)                       // grounded facts: times, places, scenes
        val miss = template.startsWith("I heard") || template.contains("I only know what the camera has shown me") || template.startsWith("I have no memories")
        val clock = SimpleDateFormat("h:mm a", Locale.getDefault())

        // pictures: by meaning for "where is my X", by time window for "what happened in the last 10 minutes"
        val pics: List<com.smriti.app.memory.VisualEntity> = runCatching {
            if (intent is Intent.Range) visual.sample(intent.fromMs, intent.toMs, 5)
            else visual.search(subject, 3).also { hits ->
                if (hits.isNotEmpty()) PipelineBus.info("embed", "search '${subject.take(40)}': " + hits.joinToString { "%.3f".format(it.score) + "@" + it.entity.place })
            }.map { it.entity }
        }.getOrDefault(emptyList())
        // for a time window the picture shown is the middle one; for "where is my X" it is the closest match
        val best = if (intent is Intent.Range) pics.getOrNull(pics.size / 2) else pics.firstOrNull()
        if (best != null) lastAnswerImage = visual.fileFor(best.thumb).absolutePath
        val whenBest = best?.let { clock.format(Date(it.timestamp)) }

        // Gemma-4 writes the answer from the facts and looks at the remembered picture itself
        if (brain.ready && (!miss || best != null)) {
            val facts = buildString {
                if (!miss) append(template)
                for (p in pics) append("\nA picture was taken at ${clock.format(Date(p.timestamp))}${p.place.takeIf { it != ZoneTracker.UNKNOWN }?.let { ", place: $it" }.orEmpty()}; notes then: ${p.caption.ifBlank { "none" }}")
            }
            val image = best?.let { runCatching { com.smriti.app.security.SecureFiles.decodeBitmap(visual.fileFor(it.thumb)) }.getOrNull() }
            val said = try {
                brain.ask(
                    system = "You are Smriti, the memory of the user's camera. The user is asking about the PAST. Answer from the FACTS and the attached picture (a blurred still from one remembered moment). " +
                        "Scan the whole picture and read every note (\"Objects:\" lists everything seen at that moment) so no object is missed. " +
                        "Describe what was happening or where each thing the user asks about was, naming every object that matches, and say when (the time). Use the picture: if it shows what the user asks about, say so plainly. " +
                        "At most three short plain sentences unless a list was asked for, no mention of 'facts'. Only if nothing at all relates to the question, say you do not remember seeing it. " +
                        "The time now is ${clock.format(Date())}.\nFACTS:\n$facts",
                    question = q, image = image, forUser = true
                )
            } finally { image?.recycle() }
            if (!said.isNullOrBlank()) return said
        }

        return when {
            !miss && best != null -> "$template\nHere is the moment I remember, around $whenBest."
            !miss -> template
            best != null -> "The closest thing I remember is around $whenBest${best.place.takeIf { it != ZoneTracker.UNKNOWN }?.let { " ($it)" }.orEmpty()}: ${best.caption.ifBlank { "a scene" }}."
            intent is Intent.Search && brain.ready -> generalAnswer(q) ?: template
            else -> template
        }
    }

    /** A question with no sign of being about the user's own camera memory is an ordinary question: Gemma answers it directly. */
    private fun looksGeneral(q: String) =
        !Regex("\\b(you see|did you see|saw|seen|my |i left|i kept|i put|i had|earlier|ago|last (night|time|week|hour|minute)|yesterday|today|this morning|tonight|remember|happened|was i|were we|memory|memories|moment|camera|scene)\\b", RegexOption.IGNORE_CASE).containsMatchIn(q)

    private suspend fun generalAnswer(q: String): String? = brain.ask(
        system = "You are Smriti, a private assistant that runs fully on the user's phone. Answer in at most three short, plain sentences. If you are not sure, say so. Do not claim to have seen anything unless the user told you.",
        question = q, forUser = true
    )

    /**
     * DEBUG HOOK: places a pretend detection (normalised box) with the phone's REAL orientation right now, through the real
     * maths, tracker, event and ledger code. Lets the whole spatial chain be checked without a particular object in view.
     */
    suspend fun debugPlace(label: String, l: Float, t: Float, r: Float, b: Float): String {
        val p = pose.snapshot() ?: return "no orientation reading yet (sensor ${pose.capability})"
        val pt = com.smriti.app.spatial.Spatial3D.place(label, l, t, r, b, p, fov)
        val tracks = pipeline.tracker.update(listOf(pt), System.currentTimeMillis())
        PipelineBus.emit(
            PipelineEvent(
                EventType.SPATIAL, stage = Stage.ZONE, status = Status.OK, message = "1 object(s) placed in the room",
                data = mapOf(
                    "objects" to tracks.map { tr -> mapOf("id" to tr.id, "name" to tr.name, "label" to tr.label, "x" to pt.x, "y" to pt.y, "z" to pt.z, "dist" to pt.distance, "az" to pt.azimuthDeg, "el" to pt.elevationDeg, "conf" to pt.confidence) },
                    "pose" to mapOf("az" to p.azimuthDeg, "pitch" to p.pitchDeg, "valid" to p.headingValid), "source" to "debug"
                )
            )
        )
        repo.updateLedger(com.smriti.app.perception.Perception("debug", listOf(label), "", "debug", spatial = mapOf(label to pt)), "workspace")
        return "placed ${tracks.first().name}: x=%.2f y=%.2f z=%.2f dist=%.2f az=%.0f el=%.0f heading=%s".format(pt.x, pt.y, pt.z, pt.distance, pt.azimuthDeg, pt.elevationDeg, p.headingValid)
    }

    suspend fun forgetLast(minutes: Int): String =
        answerer.answer(Intent.Forget(minutes)).also { visual.forgetSince(System.currentTimeMillis() - minutes * 60_000L); sessions.reconcile() }

    suspend fun forgetAll(): String { val pics = visual.forgetAll(); sessions.deleteAll(); return "Done. I erased ${repo.forgetAll()} memories and $pics pictures." }

    suspend fun teachZone(name: String): String {
        val (frame, _) = latestRotated() ?: return "The camera isn't ready yet."
        zones.learn(name, frame)
        return "Learned \"${name.trim().lowercase()}\". Memories taken here will be tagged with it."
    }

    suspend fun applyRetention(days: Int) {
        if (days > 0) {
            val before = System.currentTimeMillis() - days * 86_400_000L
            repo.decay(before)
            visual.forgetBefore(before)
            sessions.reconcile()
        }
    }

    fun setBackground(on: Boolean) {
        if (_state.value.backgroundOn == on) return
        _state.update { it.copy(backgroundOn = on) }
        if (on) gate.reset()
    }

    fun setBaseIntervalMs(ms: Long) {
        baseIntervalMs = ms.coerceAtLeast(1_000L)
    }

    // ---- runtime control (Settings and the dashboard) -------------------------------------------------------------

    /** Loads another model for its role. Waits for the in-flight frame, keeps the old model if loading fails. */
    suspend fun selectModel(id: String): Result<Long> = runCatching {
        val spec = ModelCatalog.find(id) ?: error("Unknown model '$id'")
        val ms = scheduler.runForeground { models.select(id) }
        pipeline.updateConfig {
            when (spec.role) {
                Role.DETECTOR -> it.copy(detectorId = id, preset = "custom")
                Role.LABELER -> it.copy(labelerId = id, preset = "custom")
                Role.OCR -> it.copy(ocrId = id, preset = "custom")
                Role.CAPTIONER -> it
            }
        }
        guardRearmFor(spec.role)
        ms
    }

    private fun guardRearmFor(role: Role) {
        val stage = when (role) { Role.DETECTOR -> Stage.DETECT; Role.LABELER -> Stage.LABEL; Role.OCR -> Stage.OCR; else -> return }
        pipeline.guard.rearm(stage)
    }

    fun setPreset(name: String) = pipeline.updateConfig { PipelineConfig.preset(name, it) }
    fun toggleStage(stage: String, on: Boolean) = pipeline.updateConfig { it.withStage(stage, on) }
    fun setPreview(on: Boolean) = pipeline.updateConfig { it.copy(preview = on) }
    fun rearm(stage: String) = pipeline.guard.rearm(stage).also { publishCounters(_state.value.source) }

    // ---- diagnostics ------------------------------------------------------------------------------------------------------

    /**
     * Runs the whole pipeline [n] times on the current frame without storing anything, and reports
     * median / p95 per stage plus temperature and battery.
     */
    suspend fun benchmark(n: Int = 20): String {
        val (frame, src) = latestRotated() ?: return "The camera isn't ready yet."
        Metrics.reset()
        val t0 = governor.batteryTempC()
        val b0 = governor.batteryPercent()
        scheduler.runForeground { repeat(n) { pipeline.run(frame, PipelineBus.nextFrameId(), "bench", store = false) } }
        return "Benchmark x$n on ${_state.value.backend} (source $src)\n" + dashboard() +
            "\ntemp ${t0}C -> ${governor.batteryTempC()}C, battery $b0% -> ${governor.batteryPercent()}%"
    }

    fun dashboard(): String {
        val sb = StringBuilder()
        sb.append("stage            last  median   p95    n\n")
        for ((k, s) in Metrics.snapshot()) {
            sb.append(String.format("%-14s %5d %7d %6d %4d\n", k, s.last, s.median, s.p95, s.n))
        }
        val s = _state.value
        sb.append("\nbackend: ${s.backend}")
        sb.append("\nthermal: ${governor.label}  batt ${governor.batteryPercent()}%  ${governor.batteryTempC()}C")
        sb.append("\nframes seen ${s.framesSeen}  gated-out ${s.framesGated}  processed ${s.framesProcessed}  dropped-busy ${s.framesDroppedBusy}")
        return sb.toString()
    }

    suspend fun textBytes(): Long = repo.textBytes()

    fun close() {
        models.closeAll()
        redactor.close()
    }

    private fun rotated(b: Bitmap, deg: Int): Bitmap {
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    private companion object {
        /** lowest text-to-picture similarity that counts as "this is the moment" (tuned on the device) */
        const val VISUAL_MIN = 0.12f
    }
}

/** least time between two object inventories (each is one Gemma-4 look at a picture) */
private const val INVENTORY_GAP_MS = 5_000L
