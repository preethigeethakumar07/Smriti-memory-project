package com.smriti.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.smriti.app.pipeline.PipelineBus
import androidx.core.content.ContextCompat
import com.smriti.app.core.CommandRouter
import com.smriti.app.core.SmritiEngine
import com.smriti.app.data.AppSettings
import com.smriti.app.data.SettingsRepository
import com.smriti.app.dashboard.DashboardController
import com.smriti.app.dashboard.SessionTracker
import com.smriti.app.diag.CrashLogger
import com.smriti.app.memory.AppDatabase
import com.smriti.app.net.InternetBlocker
import com.smriti.app.source.Esp32Source
import com.smriti.app.source.PhoneCameraSource
import com.smriti.app.telemetry.SystemStats
import com.smriti.app.util.Haptics
import com.smriti.app.util.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PermissionState(private val app: Application) {
    val camera = MutableStateFlow(check(Manifest.permission.CAMERA))
    val microphone = MutableStateFlow(check(Manifest.permission.RECORD_AUDIO))
    val notifications = MutableStateFlow(notificationsGranted())

    private fun check(p: String) = ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED
    private fun notificationsGranted() =
        Build.VERSION.SDK_INT < 33 || check(Manifest.permission.POST_NOTIFICATIONS)

    fun refresh() {
        camera.value = check(Manifest.permission.CAMERA)
        microphone.value = check(Manifest.permission.RECORD_AUDIO)
        notifications.value = notificationsGranted()
    }
}

/** Single place that owns the long-lived services and applies settings to them. */
class AppContainer(val app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = AppDatabase.get(app)

    val settingsRepo = SettingsRepository(app)
    val settings: StateFlow<AppSettings?> =
        settingsRepo.flow.stateIn(scope, SharingStarted.Eagerly, null)

    val engine = SmritiEngine(app)
    val camera = PhoneCameraSource(app).also { cam -> com.smriti.app.ai.Actions.torchHook = { on -> cam.setTorch(on) } }
    val commands = CommandRouter(
        engine, settingsRepo,
        startLive = { q -> live.start(cameraOn = false, source = settings.value?.source ?: "phone", firstQuestion = q) },
        onCaption = { text -> live.debugCaption(text) },
        extras = mapOf(
            "storageTest" to { _ -> storage.selfTest() },
            "storage" to { a -> storage.report(a["force"] == "1") },
            "security" to { _ ->
                val dir = java.io.File(app.filesDir, "thumbs")
                val files = dir.listFiles().orEmpty().filter { it.isFile }
                val enc = files.count { com.smriti.app.security.SecureFiles.isEncrypted(it) }
                val head = ByteArray(16)
                runCatching { app.getDatabasePath("smriti.db").inputStream().use { s -> s.read(head) } }
                val dbPlain = String(head, Charsets.ISO_8859_1).startsWith("SQLite format 3")
                val sample = files.firstOrNull()?.let { com.smriti.app.security.SecureFiles.decodeBitmap(it) }?.let { "${it.width}x${it.height}" } ?: "none"
                val st = com.smriti.app.security.SecurityState
                "master key: ${st.keyLevel} (vault created this run: ${st.vaultCreated}, error: ${st.vaultError}); pictures: ${files.size} total, $enc encrypted, ${files.size - enc} plain, converted at start: ${st.picturesMigrated}; " +
                    "sample picture decodes: $sample; database header is plain SQLite: $dbPlain; database encrypted flag: ${st.databaseEncrypted} ${st.databaseNote}"
            },
            "micSurvey" to { a -> if (live.state.value.active) "a Live session is running: close it first" else com.smriti.app.live.VoiceCapture(app).survey(a["seconds"]?.toIntOrNull() ?: 8) },
            "voiceInject" to { a ->
                val f = java.io.File(app.cacheDir, "inject.wav").also { it.delete() }
                val said = a["text"] ?: "what do you see now"
                if (!com.smriti.app.util.TtsManager.synthesizeToFile(said, f)) "could not synthesise the sentence"
                else { live.debugInject(f.readBytes(), 2500); "injected: $said" }
            },
            "sensForce" to { _ -> engine.guard.forceHold(); "holding for ${com.smriti.app.privacy.PrivacyGuard.HOLD_MS / 1000} s" },
            "sensStats" to { _ -> engine.guard.report(engine.visual.all().map { (e, v) -> e.id to v }) },
            "sensThumb" to { a ->
                val all = engine.visual.all().map { it.first }
                val e = a["id"]?.toLongOrNull()?.let { id -> all.firstOrNull { it.id == id } } ?: all.maxByOrNull { it.timestamp } ?: return@to "no moments"
                val bmp = com.smriti.app.security.SecureFiles.decodeBitmap(engine.visual.fileFor(e.thumb)) ?: return@to "thumbnail missing"
                val t0 = android.os.SystemClock.elapsedRealtime()
                val sensitive = engine.guard.screenFrame(bmp)
                "moment #${e.id}: sensitive=$sensitive in ${android.os.SystemClock.elapsedRealtime() - t0} ms, dropped so far=${engine.guard.droppedCount}"
            },
            // how many rows each table holds and the schema version, read through the app's own (encrypted) connection
            "dbCounts" to { _ ->
                val h = db.openHelper.readableDatabase
                val names = ArrayList<String>()
                h.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use { c -> while (c.moveToNext()) names += c.getString(0) }
                names.joinToString(", ") { n -> h.query("SELECT COUNT(*) FROM `$n`").use { c -> c.moveToFirst(); "$n=${c.getLong(0)}" } } + " | user_version=${h.version}"
            },
            "moveSim" to { a -> com.smriti.app.move.MoveDebug.simulate(a["scenario"] ?: "all") },
            "moveState" to { _ -> moveReport() },
            // scripted control of the real walk guide, for testing on the phone (the same calls the buttons and the voice make)
            "moveStart" to { _ -> move.startTrip(android.os.SystemClock.elapsedRealtime()) },
            "moveStop" to { _ -> move.stop(android.os.SystemClock.elapsedRealtime()) },
            "moveGo" to { _ -> move.goHome(android.os.SystemClock.elapsedRealtime()) },
            "moveHere" to { a -> move.saveHere(a["name"] ?: "Home", a["home"] != "false", android.os.SystemClock.elapsedRealtime()) },
            "moveDemo" to { a -> if (a["stop"] == "1") { moveDemo.stop(); "stopping the demo" } else moveDemo.start(a["speed"]?.toIntOrNull() ?: 2) },
            "moveWipe" to { _ -> moveDao.deleteAllPlaces(); moveDao.deleteAllRoutes(); "all walk guide places and walks deleted" },
            // a GPS fix given by hand (lat, lon, acc, optional speed / course), to try the walk guide indoors where there is no satellite signal
            "moveFix" to { a ->
                move.onFix(
                    android.os.SystemClock.elapsedRealtime(),
                    com.smriti.app.move.GpsFix(a["lat"]?.toDoubleOrNull() ?: 12.9716, a["lon"]?.toDoubleOrNull() ?: 77.5946, a["acc"]?.toDoubleOrNull() ?: 5.0, a["speed"]?.toDoubleOrNull(), a["course"]?.toDoubleOrNull(), a["courseAcc"]?.toDoubleOrNull())
                )
                "fix given"
            },
            // a Home and an L-shaped walk, to try the screen; remove them with moveWipe
            "moveSeed" to { a ->
                val lat = a["lat"]?.toDoubleOrNull() ?: 12.9716; val lon = a["lon"]?.toDoubleOrNull() ?: 77.5946
                moveDao.insertPlace(com.smriti.app.memory.MovePlaceEntity(name = "Home", kind = "home", lat = lat, lon = lon, radiusM = 15, wifi = "", createdAt = System.currentTimeMillis()))
                moveDao.insertRoute(com.smriti.app.memory.MoveRouteEntity(name = "Test walk", kind = "trip", originLat = lat, originLon = lon, hasGps = true, points = "0.0,0.0;0.0,60.0;50.0,60.0", lengthM = 110.0, createdAt = System.currentTimeMillis()))
                "seeded a Home at $lat,$lon and a 110 m test walk; remove them with moveWipe"
            },
            "moveProbe" to { a ->
                moveSensors.retain("probe")
                kotlinx.coroutines.delay((a["seconds"]?.toLongOrNull() ?: 6L) * 1000)
                val r = moveReport()
                moveSensors.release("probe")
                r
            },
            "moveVoice" to { a ->
                // says what the guide would say, with the phone's own voice and buzz, to hear and feel the cues
                val say = a["text"] ?: "Turn left"
                val h = when (a["buzz"]) { "right" -> com.smriti.app.move.HapticId.RIGHT; "uturn" -> com.smriti.app.move.HapticId.UTURN; "arrive" -> com.smriti.app.move.HapticId.ARRIVE; "tick" -> com.smriti.app.move.HapticId.TICK; else -> com.smriti.app.move.HapticId.LEFT }
                com.smriti.app.util.TtsManager.speak(say, a["tag"] ?: "en-IN"); haptics.walkCue(h)
                "said \"$say\" with the $h buzz"
            }
        )
    )
    val sessions = SessionTracker(scope, db, settings, engine)
    val dashboard = DashboardController(this)
    val stats = SystemStats(app)
    val permissions = PermissionState(app)
    val haptics = Haptics(app)
    val esp32 = Esp32Source(scope) { bmp -> engine.onBitmap(bmp, "glasses") }

    // CCTV Surveillance AI Suite
    val eventGraph = com.smriti.app.cctv.EventGraph()
    val sceneGuard = com.smriti.app.cctv.SceneGuard()
    val safeFlow = com.smriti.app.cctv.SafeFlow()
    val query2Evidence = com.smriti.app.cctv.Query2Evidence(eventGraph, sceneGuard, safeFlow)
    val voiceOps = com.smriti.app.cctv.VoiceOps(eventGraph, query2Evidence, sceneGuard, safeFlow)
    val cctv: com.smriti.app.source.CctvSource = com.smriti.app.source.CctvSource(scope) { bmp, camName, people, vehicles ->
        engine.onBitmap(bmp, camName)
        val state = engine.state.value
        val objects = state.caption.split(" ").filter { it.length > 2 }
        safeFlow.evaluateSafety(camName, people, objects, vehicleCount = vehicles)
        sceneGuard.evaluateChanges(camName, objects)
    }
    /** true while our UI is on screen; the floating orb only shows when it is false */
    val appVisible = MutableStateFlow(false)
    /** set by the orb or the notification: "voice" starts Live voice, "return" goes back to the running session */
    val pendingLive = MutableStateFlow<String?>(null)
    val chat = com.smriti.app.memory.ChatRepository(db.chatDao())
    val live = com.smriti.app.live.LiveSession(app, engine, chat, settings, haptics).also { l -> engine.visual.heardSource = { l.heardNow() } }

    /** the walk guide: steps, gyroscope, compass and GPS lead a person back home with no camera and no map (see move/) */
    val move = com.smriti.app.move.MoveController(
        com.smriti.app.move.RoomMoveStore(db.moveDao()), com.smriti.app.move.AndroidMoveOutput(haptics),
        prefs = {
            val s = settings.value
            com.smriti.app.move.MovePrefs(s?.moveVoice ?: true, s?.moveBuzz ?: true, s?.moveCompassBuzz ?: false, (s?.moveStrideCm ?: 0) / 100.0, s?.language ?: "en-IN")
        },
        scope = scope
    )
    val moveSensors = com.smriti.app.move.MoveSensors(app, move)
    /** a simulated walk for showing the walk guide without walking (see move/MoveDemo.kt) */
    val moveDemo = com.smriti.app.move.MoveDemo(move, moveSensors, scope) { android.os.SystemClock.elapsedRealtime() }
    /** saved places and walks, for the walk guide screen */
    val moveDao get() = db.moveDao()

    private suspend fun moveReport(): String {
        val s = move.state.value; val d = moveSensors.diag.value
        val places = db.moveDao().places()
        val routes = db.moveDao().routesFlow().first()
        return "mode=${s.mode} follow=${s.follow} title='${s.title}' cue=${s.cue} err=${s.errDeg} remaining=${s.remainingM}m steps=${s.steps} walked=${s.walkedM}m gps=${s.gpsM}m heading=${s.headingDeg} " +
            "arrived=${s.arrived} phrase='${s.phrase}' note='${s.note}' | sensors: steps=${d.steps} gyro=${d.gyroscope} compass=${d.compass} gps=${d.gps} locationOn=${d.locationOn} wifi=${d.wifiNetworks} " +
            "needsLocation=${d.needsLocation} needsActivity=${d.needsActivity} | saved: ${places.size} places (${places.joinToString { it.name }}), ${routes.size} walks"
    }

    /** camera frame rate, analysis tick and moment spacing follow where the user is and how warm the phone is */
    val power = com.smriti.app.core.PowerController(scope, engine, camera, live)

    init { engine.inventoryAllowed = { !live.state.value.active && power.plan.value.allowInventory } }

    /** keeps pictures and search data inside the budget chosen in Memory; video is never stored */
    val storage = com.smriti.app.ai.StorageManager(
        app, engine.visual, db.visualDao(), db.cameraSessionDao(),
        budgetMb = { settings.value?.storageBudgetMb ?: 300 },
        currentSession = { engine.sessions.current.value },
        onChanged = { engine.sessions.reconcile() }
    )

    /** set by Home's "Look for something": the next camera Live session looks for this */
    val pendingTarget = MutableStateFlow<String?>(null)
    /** a moment to open in Memory: from a tapped answer chip, or when a look-for recording finished */
    val pendingOpen = MutableStateFlow<com.smriti.app.ai.MomentRef?>(null)
    val lookAnalysis = com.smriti.app.live.LookAnalysis(engine, chat, settings, haptics) { ref -> pendingOpen.value = ref }.also { la -> engine.sessions.onEnded = { s, w -> la.run(s, w) } }

    /** Bumped when Settings says "reset", so Compose can restart the flow. */
    val resetCount = MutableStateFlow(0)

    init {
        CrashLogger.install(app)
        InternetBlocker.install()
        TtsManager.init(app)

        DebugBroadcast.register(app, commands)

        // the walk guide runs only while there is a walk: sensors on, a foreground service so it survives the screen going off, and a clock for the cues
        live.moveHook = { intent ->
            when {
                // "take me to the kitchen" with no such saved place is an ordinary question, not for the walk guide
                intent is com.smriti.app.move.MoveIntent.ToPlace && move.findPlace(intent.name) == null -> null
                intent !is com.smriti.app.move.MoveIntent.Stop && !moveSensors.hasPermissions() ->
                    "To guide you I need permission for location and physical activity. Please open Walk guide and allow them."
                else -> move.handleIntent(intent, android.os.SystemClock.elapsedRealtime())
            }
        }
        scope.launch {
            move.state.map { it.mode != com.smriti.app.move.MoveMode.IDLE }.distinctUntilChanged().collect { active ->
                if (active) { moveSensors.retain("walk"); com.smriti.app.move.MoveService.start(app) } else moveSensors.release("walk")
            }
        }
        // (a demo walk keeps its own clock, so the real one stands aside while it runs)
        scope.launch { while (true) { if (move.isActive && !moveDemo.running.value) move.tick(android.os.SystemClock.elapsedRealtime()); delay(200) } }

        if (BuildConfig.DEBUG) {
            // Debug builds mirror every pipeline event to logcat (tag SmritiBus) so the backend can be
            // watched and asserted from a terminal before the dashboard is involved.
            scope.launch {
                PipelineBus.events.collect { e ->
                    Log.d(
                        "SmritiBus",
                        "f=${e.frameId} ${e.type} ${e.stage ?: "-"} ${e.status ?: "-"} ${e.durMs}ms ${e.message.take(90)} ${e.data}"
                    )
                }
            }
        }

        scope.launch {
            settingsRepo.flow.distinctUntilChanged().collect { s -> apply(s) }
        }
        // the orientation sensor only runs while the app is on screen or a Live session is active
        scope.launch {
            kotlinx.coroutines.flow.combine(appVisible, live.state) { vis, l -> vis || l.active }.distinctUntilChanged()
                .collect { on -> if (on) engine.pose.start() else engine.pose.stop() }
        }
        // when a recording ends, check the memory budget a few seconds later
        scope.launch {
            var prev: Long? = null
            engine.sessions.current.collect { cur ->
                if (prev != null && cur == null) { delay(5_000L); runCatching { storage.enforce() } }
                prev = cur
            }
        }
        // Retention: trim old text memories on start and then hourly.
        scope.launch {
            while (true) {
                settings.value?.let { engine.applyRetention(it.retentionDays) }
                runCatching { storage.enforce() }
                delay(3_600_000L)
            }
        }
    }

    private var lastSourceKey = ""

    private fun apply(s: AppSettings) {
        engine.setBackground(s.memoryOn)
        engine.setBaseIntervalMs(s.effectiveIntervalMs)
        TtsManager.setLanguage(s.language)
        engine.language = s.language
        engine.sensitiveFilter = s.sensitiveFilter
        engine.brain.shortAnswers = s.answerStyle == "short"
        engine.ttsHasVoice = { tag -> TtsManager.hasVoice(tag) }
        haptics.enabled = s.haptics
        haptics.strength = s.hapticStrength
        dashboard.apply(s.dashboardOn, s.dashboardLan)

        // Glasses link follows the source setting.
        val key = "${s.source}|${s.glassesHost}|${s.glassesPort}|${s.glassesPath}"
        if (key != lastSourceKey) {
            lastSourceKey = key
            if (s.source == "glasses") esp32.start(s.glassesHost, s.glassesPort, s.glassesPath) else esp32.stop()
        }
    }
}
