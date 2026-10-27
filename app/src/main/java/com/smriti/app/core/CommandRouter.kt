package com.smriti.app.core

import com.smriti.app.pipeline.FaultInjector
import com.smriti.app.pipeline.PipelineBus
import com.smriti.app.pipeline.PipelineConfig
import kotlinx.coroutines.flow.first

/**
 * One place that turns a named command into an engine action. The admin page of the dashboard and the
 * debug `adb` broadcast both call this, so what you can do from the website is exactly what the tests do.
 */
class CommandRouter(
    private val engine: SmritiEngine,
    private val settings: com.smriti.app.data.SettingsRepository? = null,
    private val startLive: ((String?) -> Unit)? = null,
    private val onCaption: ((String) -> Unit)? = null,
    /** debug-only commands that belong to other parts of the app (storage, security, movement) and are registered by the container */
    private val extras: Map<String, suspend (Map<String, String>) -> String> = emptyMap()
) {

    data class Outcome(val ok: Boolean, val message: String)

    suspend fun handle(name: String, args: Map<String, String> = emptyMap()): Outcome {
        PipelineBus.info("admin", "command $name ${args.filterKeys { it != "token" }}")
        return try {
            when (name) {
                "setPreset" -> {
                    val p = args["preset"].orEmpty()
                    require(p in PipelineConfig.PRESETS) { "unknown preset '$p'" }
                    engine.setPreset(p); Outcome(true, "preset $p")
                }
                "setModel" -> {
                    val id = args["id"].orEmpty()
                    val r = engine.selectModel(id)
                    r.fold({ Outcome(true, "loaded $id in $it ms") }, { Outcome(false, it.message ?: "failed") })
                }
                "toggleStage" -> {
                    engine.toggleStage(args["stage"].orEmpty(), args["on"] == "true"); Outcome(true, "ok")
                }
                "setPreview" -> { engine.setPreview(args["on"] == "true"); Outcome(true, "preview ${args["on"]}") }
                "setInterval" -> {
                    engine.setBaseIntervalMs((args["ms"]?.toLongOrNull() ?: error("ms required"))); Outcome(true, "interval set")
                }
                "setMemory" -> { engine.setBackground(args["on"] == "true"); Outcome(true, "memory ${args["on"]}") }
                "rearm" -> { engine.rearm(args["stage"].orEmpty()); Outcome(true, "re-armed") }
                "describe" -> Outcome(true, engine.describeNow())
                "ask" -> Outcome(true, engine.ask(args["text"].orEmpty()))
                "forget" -> Outcome(true, engine.forgetLast(args["minutes"]?.toIntOrNull() ?: 5))
                "dashboard" -> {
                    val repo = settings ?: error("settings unavailable")
                    val on = args["on"] != "false"
                    val lan = args["lan"] == "true"
                    repo.update { it.copy(dashboardOn = on, dashboardLan = lan) }
                    Outcome(true, "dashboard ${if (on) "on" else "off"}${if (on && lan) " (LAN)" else ""}")
                }
                // test hooks, debug builds only: the website must never be able to flip privacy or microphone settings
                "setting" -> {
                    require(com.smriti.app.BuildConfig.DEBUG) { "debug builds only" }
                    val repo = settings ?: error("settings unavailable")
                    val on = args["on"] == "true"
                    repo.update {
                        when (args["key"]) {
                            "wakeWord" -> it.copy(wakeWord = on)
                            "floatingButton" -> it.copy(floatingButton = on)
                            "liveCaptions" -> it.copy(liveCaptions = on)
                            "strictOfflineVoice" -> it.copy(strictOfflineVoice = on)
                            "language" -> it.copy(language = args["value"] ?: "en-IN")
                            "theme" -> it.copy(theme = args["value"] ?: "system")
                            else -> error("unknown setting")
                        }
                    }
                    Outcome(true, "${args["key"]} = $on")
                }
                "sensitiveTest" -> {
                    require(com.smriti.app.BuildConfig.DEBUG) { "debug builds only" }
                    // feeds a sentence through the real scanner and the real event path, so the dashboard page can be checked without a card in view
                    val found = com.smriti.app.privacy.SensitiveScanner.scan(args["text"].orEmpty(), 1, listOf("laptop"))
                    for (f in found) PipelineBus.emit(com.smriti.app.pipeline.PipelineEvent(com.smriti.app.pipeline.EventType.SENSITIVE, stage = com.smriti.app.pipeline.Stage.PRIVACY, status = com.smriti.app.pipeline.Status.OK, message = "${f.kind.label} detected", data = mapOf("kind" to f.kind.name, "label" to f.kind.label, "masked" to f.masked, "action" to f.kind.action, "source" to "debug test")))
                    Outcome(true, "${found.size} finding(s): " + found.joinToString { "${it.kind.label} ${it.masked}" })
                }
                "placeTest" -> {
                    require(com.smriti.app.BuildConfig.DEBUG) { "debug builds only" }
                    Outcome(true, engine.debugPlace(args["label"] ?: "cup", 0.45f, 0.40f, 0.55f, 0.60f))
                }
                "momentSearch" -> {
                    val sid = args["session"]?.toLongOrNull()
                    val f = engine.momentSearch.find(args["q"].orEmpty(), sessionId = sid)
                    val starts = f.map { it.range.sessionId }.distinct().associateWith { engine.sessionStart(it) }
                    Outcome(true, engine.momentSearch.lastDebug + " -> " + f.joinToString(" | ") { val st = starts[it.range.sessionId] ?: 0L; "#${it.range.sessionId} " + com.smriti.app.ai.SessionRules.clock(it.range.start - st) + "-" + com.smriti.app.ai.SessionRules.clock(it.range.end - st) + " %.3f".format(it.range.score) })
                }
                "session" -> {
                    require(com.smriti.app.BuildConfig.DEBUG) { "debug builds only" }
                    when (args["op"]) {
                        "start" -> engine.sessions.start(args["source"] ?: "phone")
                        "end" -> engine.sessions.end("debug command")
                    }
                    kotlinx.coroutines.delay(1500)
                    val all = engine.cameraSessions.first()
                    Outcome(true, "recording=${engine.sessions.active} sessions=${all.size}: " + all.take(5).joinToString(" | ") { "#${it.id} ${com.smriti.app.ai.SessionRules.label(it.startedAt, it.endedAt)} ${it.segments} windows ${if (it.open) "OPEN" else com.smriti.app.ai.SessionRules.duration(it.durationMs)} place='${it.place}'" })
                }
                "say" -> {
                    require(com.smriti.app.BuildConfig.DEBUG) { "debug builds only" }
                    com.smriti.app.util.TtsManager.speak(args["text"].orEmpty(), args["tag"] ?: "en-IN"); Outcome(true, "speaking")
                }
                "asrTest" -> {
                    require(com.smriti.app.BuildConfig.DEBUG) { "debug builds only" }
                    // Android's own voice speaks a sentence into a WAV file, then Gemma-4 listens to it: checks the on-device recogniser end to end
                    val ctx = com.smriti.app.SmritiApp.appContext
                    val text = args["text"] ?: "where did I keep my laptop"
                    val wavFile = java.io.File(ctx.cacheDir, "asr-test.wav").also { it.delete() }
                    val ok = com.smriti.app.util.TtsManager.synthesizeToFile(text, wavFile)
                    if (!ok || !wavFile.exists()) return Outcome(false, "could not synthesise test audio")
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    val heard = engine.brain.transcribe(wavFile.readBytes())
                    Outcome(true, "said='$text' heard='$heard' ${android.os.SystemClock.elapsedRealtime() - t0} ms wav=${wavFile.length()} B err=${engine.brain.lastError}")
                }
                "bargeProbe" -> {
                    require(com.smriti.app.BuildConfig.DEBUG) { "debug builds only" }
                    // Smriti talks for a while and listens for an interruption: with nobody speaking it must NOT trigger on its own voice
                    val b = com.smriti.app.live.BargeIn(com.smriti.app.SmritiApp.appContext)
                    if (args["talk"] != "false") com.smriti.app.util.TtsManager.speak("This is a long test sentence so that the speaker keeps talking for several seconds while the microphone listens for an interruption, and it should not stop by itself.", "en-IN")
                    val hit = kotlinx.coroutines.withTimeoutOrNull(9_000) { b.awaitSpeech(speakerOn = args["talk"] != "false") }
                    com.smriti.app.util.TtsManager.stop()
                    Outcome(true, "triggered=${hit == true} peakLevel=${b.peak}")
                }
                "visualSearch" -> {
                    val hits = engine.visual.search(args["q"].orEmpty(), 5, raw = args["raw"] == "true")
                    Outcome(true, hits.joinToString(" | ") { "%.3f".format(it.score) + " " + java.text.SimpleDateFormat("h:mm:ss", java.util.Locale.US).format(java.util.Date(it.entity.timestamp)) + " " + it.entity.caption.take(40) })
                }
                "brainTest" -> {
                    require(com.smriti.app.BuildConfig.DEBUG) { "debug builds only" }
                    // describes the newest remembered thumbnail, to check Gemma's vision apart from the camera
                    val dir = java.io.File(com.smriti.app.SmritiApp.appContext.filesDir, "thumbs")
                    val f = dir.listFiles()?.maxByOrNull { it.lastModified() } ?: return Outcome(false, "no thumbnails yet")
                    val bmp = com.smriti.app.security.SecureFiles.decodeBitmap(f)
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    val said = engine.brain.ask("You are Smriti. Describe the picture in one or two plain sentences.", "What do you see?", bmp)
                    Outcome(true, "${f.name} -> $said (${android.os.SystemClock.elapsedRealtime() - t0} ms, err=${engine.brain.lastError})")
                }
                "threads" -> {
                    require(com.smriti.app.BuildConfig.DEBUG) { "debug builds only" }
                    // logs the stack of every thread that is inside Smriti code or waiting on a lock, to see what a stuck request waits for
                    var n = 0
                    for ((t, st) in Thread.getAllStackTraces()) {
                        val mine = st.any { it.className.startsWith("com.smriti") } || t.name == "main"
                        if (!mine) continue
                        n++
                        android.util.Log.i("SmritiThreads", "== ${t.name} state=${t.state}\n" + st.take(14).joinToString("\n") { "   at $it" })
                    }
                    Outcome(true, "$n threads in Smriti code")
                }
                "guide" -> {
                    require(com.smriti.app.BuildConfig.DEBUG) { "debug builds only" }
                    if (args["op"] == "stop") { engine.guide.stop(); Outcome(true, "stopped") }
                    else { engine.guide.start(args["q"].orEmpty()); kotlinx.coroutines.delay((args["wait"]?.toLongOrNull() ?: 12) * 1000); Outcome(true, "guide=${engine.guide.state.value}") }
                }
                "caption" -> {
                    require(com.smriti.app.BuildConfig.DEBUG) { "debug builds only" }
                    val sample = args["text"] ?: """I am looking through a phone camera. Here is what I see:

**Foreground:**
*   A white piece of paper with handwritten text on it.
*   A black headset with a black cable.
*   A black cable (possibly a charging or audio cable) lying on the table.
*   A black rectangular object, possibly a small device or a part of a laptop, near the bottom left.

**Background:**
*   A wooden surface (tabletop) filling most of the picture.
*   A white power adapter in the top left corner.
*   A printed schedule card with coloured blocks.

Text I can read: Check-In & Device Handover, Hacking Begins, Evaluation Round 2."""
                    onCaption?.invoke(sample); Outcome(true, "caption shown")
                }
                "tileTest" -> {
                    require(com.smriti.app.BuildConfig.DEBUG) { "debug builds only" }
                    // scores a 3x3 grid of overlapping tiles of a stored picture against the text q: where does the picture say it is?
                    val all = engine.visual.all().map { it.first }
                    val e = args["id"]?.toLongOrNull()?.let { id -> all.firstOrNull { it.id == id } } ?: all.maxByOrNull { it.timestamp } ?: return Outcome(false, "no moments yet")
                    val src = com.smriti.app.security.SecureFiles.decodeBitmap(engine.visual.fileFor(e.thumb)) ?: return Outcome(false, "thumbnail missing")
                    val q = args["q"].orEmpty()
                    val emb = engine.embedder
                    val qv = emb.embedText(q) ?: return Outcome(false, "no text vector")
                    val sb = StringBuilder()
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    val tw = (src.width * 0.5f).toInt(); val th = (src.height * 0.5f).toInt()
                    for (r in 0..2) { for (c in 0..2) {
                        val x = ((src.width - tw) * c / 2f).toInt(); val y = ((src.height - th) * r / 2f).toInt()
                        val tile = android.graphics.Bitmap.createBitmap(src, x, y, tw, th)
                        val v = emb.embedImage(tile)
                        sb.append("%5.3f ".format(if (v == null) -1f else com.smriti.app.embed.GemmaEmbedder.cosine(qv, v)))
                        tile.recycle()
                    }; sb.append("| ") }
                    val whole = emb.embedImage(src)?.let { com.smriti.app.embed.GemmaEmbedder.cosine(qv, it) } ?: -1f
                    Outcome(true, "id=${e.id} q='$q' tiles(rows)= $sb whole=%.3f (${android.os.SystemClock.elapsedRealtime() - t0} ms for 10 embeds)".format(whole))
                }
                "inventoryTest" -> {
                    require(com.smriti.app.BuildConfig.DEBUG) { "debug builds only" }
                    // runs the object inventory (and optionally a question) on a remembered moment's thumbnail; id defaults to the newest
                    val all = engine.visual.all().map { it.first }
                    val e = args["id"]?.toLongOrNull()?.let { id -> all.firstOrNull { it.id == id } } ?: all.maxByOrNull { it.timestamp } ?: return Outcome(false, "no moments yet")
                    val bmp = com.smriti.app.security.SecureFiles.decodeBitmap(engine.visual.fileFor(e.thumb)) ?: return Outcome(false, "thumbnail missing")
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    val raw = engine.brain.ask(com.smriti.app.ai.Inventory.SYSTEM, com.smriti.app.ai.Inventory.QUESTION, bmp, maxChars = 500)
                    val objects = com.smriti.app.ai.Inventory.parse(raw)
                    if (args["save"] == "1") engine.visual.annotate(e.id, objects)
                    val q = args["q"]
                    val said = q?.let { engine.brain.ask(if (com.smriti.app.ai.Inventory.wantsList(it)) com.smriti.app.ai.Inventory.LIVE_LIST_SYSTEM else com.smriti.app.ai.Inventory.LIVE_ASK_SYSTEM, it, bmp, maxChars = 1200) }
                    Outcome(true, "id=${e.id} before='${e.caption.take(80)}' raw=[$raw] parsed=${objects.size} ${objects} (${android.os.SystemClock.elapsedRealtime() - t0} ms)" + (q?.let { " | Q='$it' -> $said" } ?: ""))
                }
                "aiStatus" ->Outcome(true, "embedder=${engine.embedder.state} brain=${engine.brain.state} brainErr=${engine.brain.lastError} actions=${engine.actions.state} actionsErr=${engine.actions.lastError} moments=${engine.visual.count()} ${engine.visual.stats()} seen=${engine.state.value.framesSeen}")
                "embedTest" -> {
                    require(com.smriti.app.BuildConfig.DEBUG) { "debug builds only" }
                    val g = com.smriti.app.embed.GemmaEmbedder(com.smriti.app.SmritiApp.appContext)
                    val st = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { g.load() }
                    PipelineBus.info("embed", "load result: $st")
                    if (st !is com.smriti.app.embed.GemmaEmbedder.State.Ready) return Outcome(false, st.toString())
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    val q = g.embedText(args["q"] ?: "where are my pills")
                    val tText = android.os.SystemClock.elapsedRealtime() - t0
                    val docs = listOf("a bottle of medicine tablets on the table", "a red car on the road", "a laptop and a mouse on a desk")
                    val sims = docs.map { d -> g.embedText(d)?.let { v -> q?.let { com.smriti.app.embed.GemmaEmbedder.cosine(it, v) } } }
                    val bmp = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888).also { c ->
                        c.eraseColor(android.graphics.Color.rgb(235, 235, 235))
                        android.graphics.Canvas(c).drawRect(60f, 60f, 190f, 190f, android.graphics.Paint().apply { color = android.graphics.Color.rgb(200, 30, 30) })
                    }
                    val t1 = android.os.SystemClock.elapsedRealtime()
                    val iv = g.embedImage(bmp)
                    val tImg = android.os.SystemClock.elapsedRealtime() - t1
                    g.close()
                    Outcome(true, "$st textMs=$tText imageMs=$tImg imageDim=${iv?.size} err=${g.lastError} imgVs=${listOf("a red square on a white background", "a green forest", "a laptop").joinToString { t -> "'$t'=" + (iv?.let { v -> g.embedText(t)?.let { tv -> "%.3f".format(com.smriti.app.embed.GemmaEmbedder.cosine(v, tv)) } }) }} sims=${docs.zip(sims).joinToString { (d, s) -> "'${d.take(18)}'=${"%.3f".format(s ?: 0f)}" }}").also { PipelineBus.info("embed", "RESULT " + it.message) }
                }
                "live" -> {
                    require(com.smriti.app.BuildConfig.DEBUG) { "debug builds only" }
                    (startLive ?: error("live unavailable"))(args["text"]); Outcome(true, "live started")
                }
                "fault" -> { FaultInjector.set(args["stage"].orEmpty(), args["on"] != "false"); Outcome(true, "fault ${args["stage"]}") }
                "clearFaults" -> { FaultInjector.clear(); Outcome(true, "faults cleared") }
                else -> {
                    val extra = extras[name]
                    if (extra == null) Outcome(false, "unknown command '$name'")
                    else { require(com.smriti.app.BuildConfig.DEBUG) { "debug builds only" }; Outcome(true, extra(args)) }
                }
            }
        } catch (e: Throwable) {
            PipelineBus.error("admin", "command $name failed: ${e.message}")
            Outcome(false, e.message ?: e.javaClass.simpleName)
        }
    }
}
