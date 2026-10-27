package com.smriti.app.dashboard

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.smriti.app.AppContainer
import com.smriti.app.BuildConfig
import com.smriti.app.models.DetectedObject
import com.smriti.app.models.ModelCatalog
import com.smriti.app.net.LanGuard
import com.smriti.app.pipeline.PipelineBus
import com.smriti.app.pipeline.Stage
import com.smriti.app.telemetry.DeviceInfo
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The website the judges see is served by the phone itself, so nothing leaves the device.
 *
 *  - USB mode (default): binds 127.0.0.1 only. The laptop reaches it through `adb forward tcp:PORT tcp:PORT`.
 *  - LAN mode: binds all interfaces, accepts only private-network clients, and requires the PIN shown on the phone.
 *
 * Inbound only: this adds no outgoing connections, so the "zero cloud" counters are unaffected.
 * Browser connections must be same-origin (Origin host == Host header host), which blocks other web pages
 * open in the same browser from talking to the phone.
 */
class DashboardServer(
    private val context: Context,
    private val container: AppContainer,
    private val port: Int,
    private val lan: Boolean,
    val pin: String
) : NanoWSD(if (lan) null else "127.0.0.1", port) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = CopyOnWriteArraySet<Client>()
    private var sampler: Job? = null
    private val startedAt = SystemClock.elapsedRealtime()

    val clientCount: Int get() = clients.count { it.ready }

    fun begin() {
        start(SOCKET_READ_TIMEOUT, false)
        container.engine.pipeline.previewSink = { id, bmp, boxes, caption -> sendPreview(id, bmp, boxes, caption) }
        sampler = scope.launch {
            var lastBusy = container.engine.busyNanos.get()
            var lastWall = SystemClock.elapsedRealtimeNanos()
            var tick = 0
            while (true) {
                delay(1000)
                if (clientCount == 0) continue
                val busy = container.engine.busyNanos.get()
                val wall = SystemClock.elapsedRealtimeNanos()
                val pct = ((busy - lastBusy).toFloat() / (wall - lastWall).coerceAtLeast(1) * 100f).coerceIn(0f, 100f)
                lastBusy = busy; lastWall = wall
                broadcast(systemSample(pct))
                broadcast(moveMessage())
                if (++tick % 10 == 0) runCatching { broadcast(savedWalksMessage()) }
            }
        }
        PipelineBus.info("dashboard", "server started on port $port (${if (lan) "LAN + PIN" else "USB / localhost only"})")
    }

    fun end() {
        container.engine.pipeline.previewSink = null
        sampler?.cancel()
        clients.forEach { runCatching { it.close(WebSocketFrame.CloseCode.GoingAway, "server stopping", false) } }
        clients.clear()
        stop()
        scope.cancel()
        PipelineBus.info("dashboard", "server stopped")
    }

    // ---- HTTP: the bundled website --------------------------------------------------------------------------

    override fun serveHttp(session: IHTTPSession): Response {
        if (!originAllowed(session) || !clientAllowed(session.remoteIpAddress)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden")
        }
        var path = session.uri.substringBefore('?').trimStart('/')
        if (path.isEmpty()) path = "index.html"
        if (path.contains("..")) return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden")
        return try {
            serveAsset(path)
        } catch (_: IOException) {
            // single-page app: unknown paths without a file extension fall back to the index
            if ('.' !in path.substringAfterLast('/')) {
                runCatching { serveAsset("index.html") }.getOrElse { notBuilt() }
            } else newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveAsset(path: String): Response {
        val stream = context.assets.open("dashboard/$path")
        val r = newChunkedResponse(Response.Status.OK, mimeOf(path), stream)
        r.addHeader("Cache-Control", "no-cache")
        r.addHeader("X-Content-Type-Options", "nosniff")
        return r
    }

    private fun notBuilt() = newFixedLengthResponse(
        Response.Status.OK, "text/html",
        "<h1>Smriti dashboard not built</h1><p>Run scripts/build-dashboard.ps1 and rebuild the app.</p>"
    )

    private fun mimeOf(path: String) = when (path.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json", "map" -> "application/json"
        "svg" -> "image/svg+xml"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "png" -> "image/png"
        "ico" -> "image/x-icon"
        "txt" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }

    // ---- access control ----------------------------------------------------------------------------------------

    private fun clientAllowed(remote: String?): Boolean {
        if (remote == null) return false
        if (!lan) return remote == "127.0.0.1" || remote == "0:0:0:0:0:0:0:1" || remote == "::1"
        return remote == "0:0:0:0:0:0:0:1" || remote == "::1" || LanGuard.isAllowedHostText(remote)
    }

    /** Same-origin only. Non-browser clients (no Origin header) are allowed; they still need the PIN in LAN mode. */
    private fun originAllowed(session: IHTTPSession): Boolean {
        val origin = session.headers["origin"] ?: return true
        val originHost = runCatching { URI(origin).host }.getOrNull() ?: return false
        val hostHeader = session.headers["host"]?.substringBefore(':') ?: return false
        if (originHost.equals(hostHeader, ignoreCase = true)) return true
        // `npm run dev` serves the site from another port on the same machine; debug builds only
        return BuildConfig.DEBUG && (originHost == "localhost" || originHost == "127.0.0.1")
    }

    // ---- WebSocket ---------------------------------------------------------------------------------------------------

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = Client(handshake)

    private inner class Client(private val handshake: IHTTPSession) : WebSocket(handshake) {
        @Volatile var ready = false
        private var authed = !lan
        private var job: Job? = null
        private val denied = !originAllowed(handshake) || !clientAllowed(handshake.remoteIpAddress)

        override fun onOpen() {
            if (denied) {
                runCatching { close(WebSocketFrame.CloseCode.PolicyViolation, "forbidden", false) }
                return
            }
            clients.add(this)
            if (authed) begin() else sendJson(EventJson.message("authRequired"))
        }

        private fun begin() {
            authed = true
            sendJson(hello())
            sendJson(moveMessage())
            scope.launch { runCatching { sendJson(savedWalksMessage()) } }
            PipelineBus.snapshot().forEach { sendJson(EventJson.event(it)) }
            ready = true
            job = scope.launch { PipelineBus.events.collect { sendJson(EventJson.event(it)) } }
            PipelineBus.info("dashboard", "client connected from ${handshake.remoteIpAddress}")
        }

        override fun onMessage(frame: WebSocketFrame) {
            val msg = runCatching { JSONObject(frame.textPayload) }.getOrNull() ?: return
            when (msg.optString("type")) {
                "auth" -> {
                    if (msg.optString("pin") == pin) begin()
                    else { sendJson(EventJson.message("authFailed")); PipelineBus.warn("dashboard", "wrong PIN from ${handshake.remoteIpAddress}") }
                }
                "ping" -> sendJson(EventJson.message("pong", "t" to msg.optLong("t")))
                "cmd" -> if (authed) handleCommand(msg) else sendJson(EventJson.message("authRequired"))
            }
        }

        private fun handleCommand(msg: JSONObject) {
            val id = msg.optString("id")
            val name = msg.optString("name")
            val args = msg.optJSONObject("args")?.let { a -> a.keys().asSequence().associateWith { a.get(it).toString() } }.orEmpty()
            scope.launch {
                if (name == "moveDemo" || name == "moveStop") {
                    val text = if (name == "moveDemo") { if (args["stop"] == "1") { container.moveDemo.stop(); "stopping the demo" } else container.moveDemo.start(args["speed"]?.toIntOrNull() ?: 2) }
                    else if (container.moveDemo.running.value) { container.moveDemo.stop(); "stopping the demo" }
                    else container.move.stop(SystemClock.elapsedRealtime())
                    sendJson(EventJson.message("cmdResult", "id" to id, "name" to name, "ok" to true, "message" to text))
                } else if (name == "listSessions") {
                    val rows = container.sessions.list()
                    sendJson(EventJson.message("sessions", "rows" to rows))
                    sendJson(EventJson.message("cmdResult", "id" to id, "name" to name, "ok" to true, "message" to "${rows.size} sessions"))
                } else {
                    val r = container.commands.handle(name, args)
                    sendJson(EventJson.message("cmdResult", "id" to id, "name" to name, "ok" to r.ok, "message" to r.message))
                    if (r.ok) sendJson(hello())     // refresh config / model selection after a change
                }
            }
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            job?.cancel()
            clients.remove(this)
            ready = false
        }

        override fun onPong(pong: WebSocketFrame?) {}
        override fun onException(exception: IOException?) { job?.cancel(); clients.remove(this); ready = false }

        @Synchronized fun sendJson(o: JSONObject) {
            try { send(o.toString()) } catch (_: IOException) { onException(null) }
        }

        @Synchronized fun sendBinary(b: ByteArray) {
            try { send(b) } catch (_: IOException) { onException(null) }
        }
    }

    private fun broadcast(o: JSONObject) = clients.forEach { if (it.ready) it.sendJson(o) }

    // ---- messages ---------------------------------------------------------------------------------------------------------

    private fun hello(): JSONObject {
        val s = container.settings.value
        val cfg = container.engine.pipeline.config.value
        val selected = container.engine.models.selectedIds()
        val models = JSONArray().also { arr ->
            ModelCatalog.specs.forEach { m ->
                arr.put(
                    EventJson.toJson(
                        mapOf(
                            "id" to m.id, "role" to m.role.name, "name" to m.name, "backend" to m.backend, "note" to m.note,
                            "available" to m.available, "selected" to (selected[m.role] == m.id)
                        )
                    )
                )
            }
        }
        return EventJson.message(
            "hello",
            "app" to mapOf("name" to "Smriti", "version" to BuildConfig.VERSION_NAME, "debug" to BuildConfig.DEBUG),
            "device" to mapOf("summary" to DeviceInfo.summary, "soc" to DeviceInfo.soc),
            "user" to mapOf("name" to (s?.name ?: ""), "language" to (s?.language ?: ""), "source" to (s?.source ?: "phone")),
            "session" to mapOf("id" to container.sessions.currentId, "startedAt" to container.sessions.startedAt),
            "config" to mapOf(
                "preset" to cfg.preset, "detector" to cfg.detectorId, "labeler" to cfg.labelerId, "ocr" to cfg.ocrId,
                "detectOn" to cfg.detectOn, "labelOn" to cfg.labelOn, "ocrOn" to cfg.ocrOn, "preview" to cfg.preview,
                "intervalSec" to (s?.captureIntervalSec ?: 4), "memoryOn" to (s?.memoryOn ?: true)
            ),
            "tripped" to container.engine.pipeline.guard.trippedStages(),
            "stages" to mapOf("frame" to Stage.FRAME_STAGES, "query" to Stage.QUERY_STAGES),
            "models" to models,
            "transport" to if (lan) "lan" else "usb",
            "uptimeMs" to (SystemClock.elapsedRealtime() - startedAt)
        )
    }

    /** The walk guide, live: its state and the path (metres east and north of where the walk began), thinned for the wire. */
    private fun moveMessage(): JSONObject {
        val st = container.move.state.value
        val tr = container.move.trail.value
        fun line(l: List<com.smriti.app.move.Pt>) = thin(l, 400)
        fun pt(p: com.smriti.app.move.Pt?) = p?.let { listOf(round1(it.x), round1(it.y)) }
        return EventJson.message(
            "move",
            "demo" to container.moveDemo.running.value,
            "active" to container.move.isActive,
            "state" to mapOf(
                "mode" to st.mode.name, "follow" to st.follow.name, "title" to st.title, "cue" to st.cue?.name, "errDeg" to st.errDeg,
                "phrase" to st.phrase, "remainingM" to st.remainingM, "steps" to st.steps, "walkedM" to st.walkedM, "gpsM" to st.gpsM,
                "headingDeg" to st.headingDeg, "arrived" to st.arrived, "note" to st.note, "compassPoor" to st.compassPoor
            ),
            "trail" to mapOf(
                "out" to line(tr.out), "back" to line(tr.back), "route" to line(tr.route),
                "here" to pt(tr.here), "start" to pt(tr.start), "dest" to pt(tr.dest), "destLabel" to tr.destLabel,
                "heading" to tr.headingDeg, "sigma" to round1(tr.sigmaM)
            )
        )
    }

    /** Saved places and walks, each walk with its (thinned) points so the website can draw it. */
    private suspend fun savedWalksMessage(): JSONObject {
        val places = container.moveDao.places()
        val walks = container.moveDao.routesFlow().first().take(20)
        return EventJson.message(
            "moveSaved",
            "places" to places.map { mapOf("name" to it.name, "kind" to it.kind, "radiusM" to it.radiusM) },
            "walks" to walks.map { w ->
                val pts = w.points.split(';').mapNotNull { q -> q.split(',').let { c -> if (c.size == 2) c[0].toDoubleOrNull()?.let { x -> c[1].toDoubleOrNull()?.let { y -> com.smriti.app.move.Pt(x, y) } } else null } }
                mapOf("id" to w.id, "name" to w.name, "lengthM" to round1(w.lengthM), "hasGps" to w.hasGps, "createdAt" to w.createdAt, "points" to thin(pts, 200))
            }
        )
    }

    private fun round1(v: Double) = Math.round(v * 10.0) / 10.0
    private fun thin(l: List<com.smriti.app.move.Pt>, max: Int): List<List<Double>> {
        val step = (l.size + max - 1) / max
        val keep = if (step <= 1) l else l.filterIndexed { i, _ -> i % step == 0 || i == l.lastIndex }
        return keep.map { listOf(round1(it.x), round1(it.y)) }
    }

    private fun systemSample(busyPct: Float): JSONObject {
        val sys = container.stats.sample()
        val st = container.engine.state.value
        return EventJson.message(
            "SYSTEM",
            "t" to System.currentTimeMillis(),
            "data" to mapOf(
                "ramMb" to sys.appRamMb, "sysUsedMb" to sys.sysUsedMb, "sysTotalMb" to sys.sysTotalMb,
                "cpu" to sys.cpuPercentOfDevice, "threads" to sys.threads,
                "battery" to sys.batteryPct, "charging" to sys.charging, "tempC" to sys.batteryTempC, "currentMa" to sys.currentMa,
                "thermal" to sys.thermalStatus, "headroom" to sys.thermalHeadroom,
                "tx" to sys.txBytes, "rx" to sys.rxBytes,
                "blocked" to com.smriti.app.net.InternetBlocker.blockedCount, "lanConn" to com.smriti.app.net.InternetBlocker.lanCount,
                "seen" to st.framesSeen, "gated" to st.framesGated, "processed" to st.framesProcessed, "dropped" to st.framesDroppedBusy,
                "notes" to st.memories, "busyPct" to busyPct, "clients" to clientCount,
                "camera" to container.camera.status.value.state, "ready" to st.ready,
                "poseAz" to (container.engine.pose.snapshot()?.azimuthDeg ?: -1f),
                "posePitch" to (container.engine.pose.snapshot()?.pitchDeg ?: 0f),
                "poseValid" to (container.engine.pose.snapshot()?.headingValid ?: false),
                "poseSensor" to container.engine.pose.capability.name,
                // measured, per engine: backend, load time, calls, last and average ms, share of the last 10 s spent inside the engine
                "ai" to com.smriti.app.ai.AiMeter.snapshot()
            )
        )
    }

    // ---- redacted preview (opt-in, in memory only) ----------------------------------------------------------------------

    @Volatile private var lastPreview = 0L

    private fun sendPreview(frameId: Long, redacted: Bitmap, boxes: List<DetectedObject>, caption: String) {
        if (clientCount == 0) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPreview < PREVIEW_MIN_GAP_MS) return
        lastPreview = now
        // the frame passed in is the ALREADY BLURRED copy; scale it down and JPEG it in memory, never to disk
        val w = 320
        val h = (redacted.height * (w / redacted.width.toFloat())).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(redacted, w, h, true)
        val out = ByteArrayOutputStream(24_000)
        small.compress(Bitmap.CompressFormat.JPEG, 55, out)
        if (small !== redacted) small.recycle()
        val jpeg = out.toByteArray()
        val header = EventJson.message(
            "preview", "frameId" to frameId, "caption" to caption, "w" to w, "h" to h, "bytes" to jpeg.size,
            "boxes" to boxes.map { b -> mapOf("label" to b.label, "c" to b.confidence, "x" to b.box.left, "y" to b.box.top, "w" to b.box.width(), "h" to b.box.height()) }
        )
        val payload = ByteBuffer.allocate(8 + jpeg.size).putLong(frameId).put(jpeg).array()
        clients.forEach { if (it.ready) { it.sendJson(header); it.sendBinary(payload) } }
    }

    private companion object {
        const val PREVIEW_MIN_GAP_MS = 500L
        const val TAG = "DashboardServer"
    }
}
