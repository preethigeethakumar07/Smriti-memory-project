package com.smriti.app.move

import com.smriti.app.memory.MovePlaceEntity
import com.smriti.app.memory.MoveRouteEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

enum class MoveMode { IDLE, RECORDING, GUIDING }

/** how a guided walk finds its way: back along a walk that was recorded, along a saved walk, or in a straight line to a place */
enum class Follow { NONE, RETRACE, ROUTE, BEARING }

/** What the screen shows. Numbers are rounded so that the screen is not redrawn for changes nobody can see. */
data class MoveState(
    val mode: MoveMode = MoveMode.IDLE,
    val follow: Follow = Follow.NONE,
    /** what is being followed: "Home", "Chemist", "Walk 10 Oct 14:20" */
    val title: String = "",
    val cue: CueType? = null,
    /** how far to turn, in degrees, positive to the right */
    val errDeg: Int = 0,
    /** the last instruction, in the user's language */
    val phrase: String = "",
    val remainingM: Int = 0,
    val steps: Int = 0,
    val walkedM: Int = 0,
    /** GPS accuracy in metres, or null when there is no recent fix */
    val gpsM: Int? = null,
    val headingDeg: Int? = null,
    val arrived: Boolean = false,
    /** one short line about what the sensors are doing, or what the user can do about it */
    val note: String = "",
    val compassPoor: Boolean = false
)

/** Where the words, the buzz and the compass pulses go. */
interface MoveOutput {
    /** [languageTag] is the language the text is written in, so the right voice reads it */
    fun speak(text: String, languageTag: String)
    fun buzz(h: HapticId)
    fun pulse(onMs: Int, amplitude: Int)
}

/** Saved places and walks. */
interface MoveStore {
    suspend fun home(): MovePlaceEntity?
    suspend fun places(): List<MovePlaceEntity>
    /** saving a place called Home replaces the old one; returns the new id */
    suspend fun savePlace(p: MovePlaceEntity): Long
    suspend fun route(id: Long): MoveRouteEntity?
    suspend fun lastTrip(): MoveRouteEntity?
    /** inserts when [MoveRouteEntity.id] is 0, otherwise updates; returns the id */
    suspend fun saveRoute(r: MoveRouteEntity): Long
}

data class MovePrefs(
    val voice: Boolean = true,
    val buzz: Boolean = true,
    /** a steady buzz that gets firmer the closer the phone points to the right way */
    val compassBuzz: Boolean = false,
    /** the user's stride in metres; 0 uses a typical one that is then learned from GPS */
    val strideM: Double = 0.0,
    val language: String = "en-IN"
)

data class GpsFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val speedMps: Double? = null,
    val courseDeg: Double? = null,
    val courseAccDeg: Double? = null
)

/**
 * The walk guide: turns what the phone senses (steps, the direction it points, GPS) into where the person is, remembers the way they
 * walked, and tells them how to get back ("turn left", "you have arrived") with a short voice cue and a buzz. It has no maps: it
 * retraces the walked path, follows a saved walk, or points straight at a saved place.
 *
 * Everything the sensors report comes in through [onOrientation], [onStep], [onFix] and [onWifi]; [tick] is called a few times a
 * second. There is nothing Android-specific in here, so the whole walk can be simulated on a computer (see [MoveSim]).
 */
class MoveController(
    private val store: MoveStore,
    private val out: MoveOutput,
    private val prefs: () -> MovePrefs,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val wallClock: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private val _state = MutableStateFlow(MoveState())
    val state: StateFlow<MoveState> = _state.asStateFlow()

    // ---- what the phone senses
    private val heading = HeadingFilter()
    private var lastHeading: Double? = null
    private var lastHeadingAt = 0L
    private var compassPoor = false
    private var lastFix: GpsFix? = null
    private var lastFixAt = Long.MIN_VALUE / 2
    private val recentFixes = ArrayDeque<Pair<Long, GpsFix>>()
    private var wifiNow = ""
    private var now = 0L
    private var lastEvalAt = 0L

    // ---- where the person is
    private var pdr = PdrTracker()
    private var origin: LatLon? = null
    private var skippedSteps = 0

    // ---- what the walk guide is doing
    private var mode = MoveMode.IDLE
    private var follow = Follow.NONE
    private var title = ""
    private var arrived = false
    private var lastCue: Cue? = null
    private var lastPhrase = ""
    private var lastAnnounceAt = Long.MIN_VALUE / 2
    private var lastHoldAt = Long.MIN_VALUE / 2
    private var nextPulseAt = 0L

    private var guidance: Guidance? = null
    private var straight: StraightGuide? = null
    private var pendingBearing: MovePlaceEntity? = null
    private var confirmHome: MovePlaceEntity? = null
    private val policy = CuePolicy()
    private val calls = DistanceCalls()

    // ---- recording a walk
    private var recorder: RouteBuilder? = null
    private var recordOrigin: LatLon? = null
    private var recordStartedAt = 0L
    private var recordId = 0L
    private var lastSavedAt = 0L
    private val saveLock = Mutex()

    // ---- what the path drawing shows (see MapMath.kt); kept after a walk ends, so the finished walk can still be looked at
    private var shownOut: List<Pt> = emptyList()
    private var back = RouteBuilder(2.0)
    private var routeShown: List<Pt> = emptyList()
    private var destShown: Pt? = null
    private var destLabel = ""
    private var startShown: Pt? = null
    private var lastTrailAt = Long.MIN_VALUE / 2
    private val _trail = MutableStateFlow(Trail())

    /** the walk as a drawing: where the person went, the way they are being led, where they are now */
    val trail: StateFlow<Trail> = _trail.asStateFlow()

    val isActive: Boolean get() = synchronized(lock) { mode != MoveMode.IDLE }

    /** false while a demo walk runs (see [MoveDemo]): a walk made up for showing is not kept in the saved walks */
    @Volatile var saving = true

    // =============================================================================================================
    //  what the sensors report
    // =============================================================================================================

    /**
     * The phone's direction. [game] is the heading from the gyroscope-based rotation (any north), [compass] the one from the compass
     * (null when it is not to be trusted), [fieldUt] the magnetic field strength, [poseChanged] true when the phone was picked up or put
     * away, [compassPoor] true when the compass says it needs calibrating.
     */
    fun onOrientation(nowMs: Long, game: Double?, compass: Double?, fieldUt: Double?, poseChanged: Boolean = false, compassPoor: Boolean = false) {
        synchronized(lock) {
            now = nowMs
            if (poseChanged) heading.poseChanged()
            this.compassPoor = compassPoor
            if (game != null) {
                val h = heading.update(nowMs, game, compass, fieldUt)
                if (h != null) { lastHeading = h; lastHeadingAt = nowMs }
            }
            if (nowMs - lastEvalAt >= EVAL_EVERY_MS) { lastEvalAt = nowMs; evaluate(nowMs) }
        }
    }

    /** one step was taken */
    fun onStep(nowMs: Long) {
        synchronized(lock) {
            now = nowMs
            if (mode == MoveMode.IDLE) return
            val h = heading.heading ?: lastHeading?.takeIf { nowMs - lastHeadingAt < 10_000 }
            if (h == null) { skippedSteps++; evaluate(nowMs); return }
            pdr.step(h)
            recorder?.add(pdr.position())
            if (mode == MoveMode.GUIDING) back.add(pdr.position())
            lastEvalAt = nowMs
            evaluate(nowMs)
            publishTrail()
        }
    }

    /** a GPS fix */
    fun onFix(nowMs: Long, fix: GpsFix) {
        synchronized(lock) {
            now = nowMs
            lastFix = fix; lastFixAt = nowMs
            recentFixes.addLast(nowMs to fix)
            while (recentFixes.isNotEmpty() && nowMs - recentFixes.first().first > FIX_KEEP_MS) recentFixes.removeFirst()
            val c = fix.courseDeg; val s = fix.speedMps
            if (c != null && s != null) heading.onGpsCourse(nowMs, c, s, fix.courseAccDeg)
            if (mode == MoveMode.IDLE || fix.accuracyM > PdrTracker.MAX_ACCURACY_M) { publish(); return }

            val ll = LatLon(fix.lat, fix.lon)
            val o = origin
            if (o == null) {
                // no map frame yet: this first good fix says where the position is on the map
                origin = Geo.fromLocal(ll, -pdr.x, -pdr.y)
                if (mode == MoveMode.RECORDING) recordOrigin = origin
                buildStraightIfPending()
            } else {
                val p = Geo.toLocal(o, ll)
                pdr.fuse(p.x, p.y, fix.accuracyM)
                recorder?.add(pdr.position())
                if (mode == MoveMode.GUIDING) back.add(pdr.position())
            }
            lastEvalAt = nowMs
            evaluate(nowMs)
            publishTrail()
        }
    }

    /** the strongest Wi-Fi networks heard right now, already encoded by [WifiPrint] */
    fun onWifi(print: String) { synchronized(lock) { wifiNow = print } }

    /** called a few times a second while the walk guide runs */
    fun tick(nowMs: Long) {
        synchronized(lock) {
            now = nowMs
            val stride = prefs().strideM
            if (stride > 0.0 && kotlin.math.abs(stride - pdr.stride) > 0.005) pdr.setStride(stride)
            if (mode == MoveMode.RECORDING && nowMs - lastSavedAt >= SAVE_EVERY_MS) { lastSavedAt = nowMs; saveRecording() }
            lastEvalAt = nowMs
            evaluate(nowMs)
            publishTrail()
        }
    }

    // =============================================================================================================
    //  what the user asks for
    // =============================================================================================================

    /** Starts remembering the way the user walks, so it can be walked back. */
    fun startTrip(nowMs: Long): String {
        synchronized(lock) {
            now = nowMs
            if (mode == MoveMode.RECORDING) return "I am already remembering your walk."
            beginTracking(nowMs)
            mode = MoveMode.RECORDING; follow = Follow.NONE; title = "Walk"
            recorder = RouteBuilder().also { it.add(pdr.position()) }
            startShown = pdr.position()
            recordOrigin = origin
            recordStartedAt = wallClock(); recordId = 0L; lastSavedAt = nowMs
            publish()
            publishTrail(force = true)
            return "Okay. I will remember the way you walk, so I can bring you back."
        }
    }

    /** Stops whatever is going on: a recording is saved, a guided walk ends. */
    fun stop(nowMs: Long): String {
        synchronized(lock) {
            now = nowMs
            val was = mode
            if (was == MoveMode.IDLE) return "Nothing is running."
            val kept = was == MoveMode.RECORDING && saveRecording()
            clearJob()
            publish()
            return when {
                was == MoveMode.GUIDING -> "Okay. I stopped guiding."
                kept -> "Okay. I saved your walk."
                else -> "Okay. I stopped. That walk was too short to keep."
            }
        }
    }

    /**
     * "Take me home". While a walk is being recorded the way back is the way the user came; otherwise a saved Home is reached in a straight
     * line from where the user is; otherwise the last recorded walk is followed back to where it began.
     */
    suspend fun goHome(nowMs: Long): String {
        val home = store.home()
        synchronized(lock) {
            now = nowMs
            val rec = recorder
            if (mode == MoveMode.RECORDING && rec != null && rec.size >= 2) {
                startRetrace(home)
                publish()
                return "Okay. I will bring you back the way you came. Hold the phone in your hand and start walking."
            }
            if (home != null && (home.lat != 0.0 || home.lon != 0.0)) return beginBearing(home, nowMs)
        }
        val trip = store.lastTrip()
        if (trip != null) {
            val r = followRoute(trip, reverse = true, nowMs = nowMs, title = home?.name ?: "Start of your walk", assumeAtEnd = true)
            if (r != null) return r
        }
        return "I do not know where home is yet. Please open Walk guide and save your home first."
    }

    /** Guides to a saved place in a straight line. */
    suspend fun guidePlace(place: MovePlaceEntity, nowMs: Long): String {
        synchronized(lock) {
            now = nowMs
            return beginBearing(place, nowMs)
        }
    }

    /** Guides along a saved walk, forward or back. */
    suspend fun guideRoute(routeId: Long, reverse: Boolean, nowMs: Long): String {
        val route = store.route(routeId) ?: return "I cannot find that walk."
        return followRoute(route, reverse, nowMs, title = route.name, assumeAtEnd = false) ?: "That walk is too short to follow."
    }

    /** Saves the spot the user is standing on, with the GPS position and the Wi-Fi networks heard here. */
    suspend fun saveHere(name: String, asHome: Boolean, nowMs: Long): String {
        val spot: Triple<Double, Double, Double>
        val wifi: String
        synchronized(lock) {
            now = nowMs
            spot = bestPosition(nowMs) ?: return "I cannot see the sky yet, so I do not know where this is. Go near a window or outside and try again."
            wifi = wifiNow
        }
        val (lat, lon, acc) = spot
        val label = name.trim().ifEmpty { if (asHome) "Home" else "Place" }.take(40)
        val radius = (acc * 1.5).coerceIn(12.0, 40.0).roundToInt()
        store.savePlace(MovePlaceEntity(name = label, kind = if (asHome) "home" else "other", lat = lat, lon = lon, radiusM = radius, wifi = wifi, createdAt = wallClock()))
        val weak = if (acc > 20) " The GPS is weak here (about ${acc.roundToInt()} metres), so arrival will be approximate." else " The GPS is accurate to about ${acc.roundToInt()} metres."
        return "Saved $label.$weak"
    }

    /** What the voice assistant does with "take me home", "I am going out", "stop guiding"; null when it is not for the walk guide. */
    suspend fun handleIntent(intent: MoveIntent, nowMs: Long): String? = when (intent) {
        MoveIntent.GoHome -> goHome(nowMs)
        MoveIntent.StartTrip -> startTrip(nowMs)
        MoveIntent.Stop -> if (isActive) stop(nowMs) else null
        is MoveIntent.ToPlace -> {
            val p = findPlace(intent.name)
            if (p == null) null else guidePlace(p, nowMs)
        }
    }

    /** a saved place called [name] (exactly, or part of it), or null; "home" finds the saved Home */
    suspend fun findPlace(name: String): MovePlaceEntity? {
        val n = name.trim().lowercase().removePrefix("my ").removePrefix("the ")
        val all = store.places()
        if (n == "home") return all.firstOrNull { it.kind == "home" }
        return all.firstOrNull { it.name.lowercase() == n } ?: all.firstOrNull { it.name.lowercase().contains(n) || n.contains(it.name.lowercase()) }
    }

    // =============================================================================================================
    //  starting a guided walk
    // =============================================================================================================

    private fun beginTracking(nowMs: Long) {
        val p = prefs()
        pdr = PdrTracker(if (p.strideM > 0.0) p.strideM else PdrTracker.DEFAULT_STRIDE_M)
        val f = lastFix
        if (f != null && nowMs - lastFixAt <= 10_000 && f.accuracyM <= PdrTracker.MAX_ACCURACY_M) {
            origin = LatLon(f.lat, f.lon); pdr.reset(0.0, 0.0, f.accuracyM)
        } else { origin = null; pdr.reset() }
        policy.reset(); calls.reset()
        guidance = null; straight = null; pendingBearing = null; confirmHome = null
        recorder = null; recordOrigin = null
        lastCue = null; lastPhrase = ""; arrived = false; skippedSteps = 0; nextPulseAt = 0L
        // a new job starts with a clean drawing
        shownOut = emptyList(); back = RouteBuilder(2.0); routeShown = emptyList(); destShown = null; destLabel = ""; startShown = null
    }

    private fun startRetrace(home: MovePlaceEntity?) {
        val rec = recorder ?: return
        val here = pdr.position()
        val path = ArrayList<Pt>().apply { add(here); addAll(rec.simplified(2.0).asReversed()) }
        saveRecording()
        shownOut = rec.points()
        recorder = null
        policy.reset(); calls.reset()
        guidance = Guidance(path, arriveRadiusM = arriveRadius(home), offRouteM = 20.0, lookAheadM = 8.0)
        straight = null; pendingBearing = null
        follow = Follow.RETRACE; mode = MoveMode.GUIDING
        title = home?.name ?: "Start of your walk"
        confirmHome = home
        arrived = false; lastCue = null; lastPhrase = ""; nextPulseAt = 0L
        // the drawing: the walk out stays, the way back is drawn as it is walked, the way to follow ends where the walk began
        routeShown = path; destShown = path.last(); destLabel = home?.name ?: "Start"; startShown = null
        back = RouteBuilder(2.0).also { it.add(here) }
        publishTrail(force = true)
    }

    private fun beginBearing(place: MovePlaceEntity, nowMs: Long): String {
        if (mode == MoveMode.RECORDING) saveRecording()
        beginTracking(nowMs)
        mode = MoveMode.GUIDING; follow = Follow.BEARING; title = place.name
        pendingBearing = place; confirmHome = place
        buildStraightIfPending()
        back.add(pdr.position())
        publish()
        publishTrail(force = true)
        val wait = if (straight == null) " I am looking for the GPS signal; please stay outside in the open." else ""
        return "Okay. I will guide you to ${place.name}. Hold the phone in your hand and start walking.$wait"
    }

    private fun buildStraightIfPending() {
        val p = pendingBearing ?: return
        val o = origin ?: return
        val dest = Geo.toLocal(o, LatLon(p.lat, p.lon))
        straight = StraightGuide(dest, arriveRadius(p))
        destShown = dest; destLabel = p.name
        pendingBearing = null
    }

    private suspend fun followRoute(route: MoveRouteEntity, reverse: Boolean, nowMs: Long, title: String, assumeAtEnd: Boolean): String? {
        val pts = RouteText.decode(route.points)
        if (pts.size < 2) return null
        val path = if (reverse) pts.asReversed() else pts
        synchronized(lock) {
            now = nowMs
            if (mode == MoveMode.RECORDING) saveRecording()
            beginTracking(nowMs)
            val f = lastFix
            val fresh = f != null && nowMs - lastFixAt <= 15_000 && f.accuracyM <= PdrTracker.MAX_ACCURACY_M
            if (f != null && route.hasGps && fresh) {
                // with a GPS fix the user can be put on the walk's own map wherever they stand along it
                val o = LatLon(route.originLat, route.originLon)
                val p = Geo.toLocal(o, LatLon(f.lat, f.lon))
                val off = distanceToPath(p, path)
                if (off > MAX_FAR_FROM_ROUTE_M) {
                    clearJob()
                    return "You are about ${off.roundToInt()} metres from that walk. Go to where it begins first."
                }
                origin = o; pdr.reset(p.x, p.y, f.accuracyM)
            } else {
                origin = null
                // without GPS the walk is followed from its own start (or, going back, from where it ended)
                pdr.reset(path.first().x, path.first().y)
            }
            guidance = Guidance(path, arriveRadiusM = 6.0, offRouteM = 20.0, lookAheadM = 8.0)
            follow = if (reverse && route.kind == "trip") Follow.RETRACE else Follow.ROUTE
            mode = MoveMode.GUIDING; this.title = title
            routeShown = path; destShown = path.last(); destLabel = if (reverse) "Start" else "End"
            back.add(pdr.position())
            publish()
            publishTrail(force = true)
            val where = if (route.hasGps && fresh) "" else if (assumeAtEnd) " I am assuming you are where that walk ended." else " Stand at the start of the walk."
            return "Okay. I will guide you ${if (reverse) "back along" else "along"} $title. Hold the phone in your hand and start walking.$where"
        }
    }

    private fun clearJob() {
        recorder?.let { shownOut = it.points() }                 // the finished walk stays on the drawing
        mode = MoveMode.IDLE; follow = Follow.NONE
        guidance = null; straight = null; pendingBearing = null; confirmHome = null; recorder = null
        lastCue = null
        publishTrail(force = true)
    }

    /** the drawing of the walk: shared as a new snapshot, at most a few times a second unless [force]d */
    private fun publishTrail(force: Boolean = false) {
        if (!force && now - lastTrailAt < TRAIL_EVERY_MS) return
        lastTrailAt = now
        val active = mode != MoveMode.IDLE
        _trail.value = Trail(
            out = recorder?.points() ?: shownOut,
            back = back.points(),
            route = routeShown,
            here = if (active) pdr.position() else null,
            headingDeg = if (active) heading.heading else null,
            start = startShown, dest = destShown, destLabel = destLabel,
            sigmaM = if (active) pdr.sigma else 0.0
        )
    }

    // =============================================================================================================
    //  following the guidance
    // =============================================================================================================

    private fun evaluate(nowMs: Long) {
        if (mode != MoveMode.GUIDING) { publish(); return }
        val h = heading.heading ?: lastHeading?.takeIf { nowMs - lastHeadingAt < 10_000 }
        if (h == null) { noHeading(nowMs); publish(); return }
        val here = pdr.position()
        var cue: Cue = when (follow) {
            Follow.BEARING -> straight?.update(here.x, here.y, h) ?: run { publish(); return }
            else -> guidance?.update(here.x, here.y, h) ?: run { publish(); return }
        }
        if (cue.type != CueType.ARRIVED && arrivedAtHome(nowMs, cue)) cue = Cue(CueType.ARRIVED, 0.0, 0.0, 0.0)
        lastCue = cue
        announce(cue, nowMs)
        if (cue.type == CueType.ARRIVED) finishArrival() else compassBuzz(cue, nowMs)
        publish()
    }

    /** the GPS or the Wi-Fi networks say the user is at the place being walked to, even when the position estimate has not got there */
    private fun arrivedAtHome(nowMs: Long, cue: Cue): Boolean {
        val home = confirmHome ?: return false
        val f = lastFix
        val radius = maxOf(home.radiusM.toDouble(), 8.0)
        // (a straight walk to the place already uses the GPS-corrected position, so only the other ways need this second opinion)
        if (follow != Follow.BEARING && f != null && nowMs - lastFixAt <= 5_000 && f.accuracyM <= 25.0 && (home.lat != 0.0 || home.lon != 0.0) && cue.remainingM <= radius * 2.5 + 10.0) {
            if (Geo.distance(LatLon(f.lat, f.lon), LatLon(home.lat, home.lon)) <= radius) return true
        }
        if (cue.remainingM < 60.0 && home.wifi.isNotEmpty() && WifiPrint.isHere(home.wifi, wifiNow)) return true
        return false
    }

    private fun noHeading(nowMs: Long) {
        if (nowMs - lastHoldAt < HOLD_PROMPT_MS) return
        lastHoldAt = nowMs
        say(MovePhrases.holdPhone(), nowMs)
    }

    private fun announce(cue: Cue, nowMs: Long) {
        var a = policy.next(cue, nowMs)
        if (a == null && cue.type != CueType.ARRIVED && nowMs - lastAnnounceAt >= 5_000) {
            calls.next(cue.remainingM)?.let { a = MovePhrases.toGo(it, prefs().language) }
        }
        a?.let { say(it, nowMs) }
    }

    private fun say(a: Announcement, nowMs: Long) {
        val p = prefs()
        lastPhrase = MovePhrases.text(a, p.language)
        lastAnnounceAt = nowMs
        if (p.voice) out.speak(lastPhrase, MovePhrases.tagFor(p.language))
        if (p.buzz) out.buzz(a.haptic)
    }

    private fun compassBuzz(cue: Cue, nowMs: Long) {
        if (!prefs().compassBuzz || nowMs < nextPulseAt) return
        val p = CompassBuzz.pulse(cue.errDeg)
        if (p == null) { nextPulseAt = nowMs + 700; return }
        out.pulse(p.onMs, p.amplitude)
        nextPulseAt = nowMs + p.onMs + p.offMs
    }

    private fun finishArrival() {
        clearJob()
        arrived = true
    }

    /** how close counts as arrived: a saved place says so; the start of a walk is as close as GPS can tell (10 m), or, with steps only, as close as steps can (5 m) */
    private fun arriveRadius(p: MovePlaceEntity?): Double = when {
        p != null -> p.radiusM.toDouble().coerceIn(8.0, 40.0)
        origin != null -> 10.0
        else -> 5.0
    }

    private fun distanceToPath(p: Pt, path: List<Pt>): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until path.size - 1) {
            val a = path[i]; val b = path[i + 1]
            val dx = b.x - a.x; val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
            best = min(best, Geo.distance(p, Pt(a.x + t * dx, a.y + t * dy)))
        }
        return best
    }

    /** the average of the last few GPS fixes, each weighted by how accurate it was: latitude, longitude, and the best accuracy */
    private fun bestPosition(nowMs: Long): Triple<Double, Double, Double>? {
        val fixes = recentFixes.filter { nowMs - it.first <= SAVE_WINDOW_MS && it.second.accuracyM <= 50.0 }.map { it.second }
        if (fixes.isEmpty()) return null
        var sw = 0.0; var sLat = 0.0; var sLon = 0.0; var best = Double.MAX_VALUE
        for (f in fixes) {
            val w = 1.0 / (f.accuracyM * f.accuracyM).coerceAtLeast(1.0)
            sw += w; sLat += w * f.lat; sLon += w * f.lon; best = min(best, f.accuracyM)
        }
        return Triple(sLat / sw, sLon / sw, best)
    }

    // =============================================================================================================
    //  saving the recorded walk
    // =============================================================================================================

    /** queues the walk recorded so far for saving; false when it is too short to be worth keeping */
    private fun saveRecording(): Boolean {
        if (!saving) return false
        val rec = recorder ?: return false
        val pts = rec.simplified(1.5)
        val len = RouteBuilder.length(pts)
        if (pts.size < 2 || len < MIN_WALK_M) return false
        val o = recordOrigin
        val started = recordStartedAt
        val knownId = recordId
        val entity = MoveRouteEntity(
            id = knownId, name = walkName(started), kind = "trip",
            originLat = o?.lat ?: 0.0, originLon = o?.lon ?: 0.0, hasGps = o != null,
            points = RouteText.encode(pts), lengthM = len, createdAt = started
        )
        scope.launch {
            saveLock.withLock {
                val id = if (recordId != 0L) recordId else knownId
                val saved = store.saveRoute(entity.copy(id = id))
                if (recordId == 0L) recordId = saved
            }
        }
        return true
    }

    private fun walkName(at: Long) = "Walk " + SimpleDateFormat("d MMM HH:mm", Locale.ENGLISH).format(Date(at))

    // =============================================================================================================
    //  what the screen shows
    // =============================================================================================================

    private fun publish() {
        val fresh = lastFix != null && now - lastFixAt <= 15_000
        val c = lastCue
        val h = heading.heading
        _state.value = MoveState(
            mode = mode, follow = follow, title = title,
            cue = c?.type, errDeg = ((c?.errDeg ?: 0.0) / 5).roundToInt() * 5,
            phrase = lastPhrase,
            remainingM = c?.remainingM?.let { if (it > 200) (it / 10).roundToInt() * 10 else it.roundToInt() } ?: 0,
            steps = pdr.steps, walkedM = pdr.distanceM.roundToInt(),
            gpsM = if (fresh) lastFix?.accuracyM?.roundToInt() else null,
            headingDeg = h?.let { (it / 5).roundToInt() * 5 % 360 },
            arrived = arrived,
            note = note(fresh, h),
            compassPoor = compassPoor
        )
    }

    private fun note(fresh: Boolean, h: Double?): String = when {
        mode == MoveMode.GUIDING && follow == Follow.BEARING && pendingBearing != null -> "Looking for GPS. Stay outside in the open."
        mode != MoveMode.IDLE && h == null -> "Hold the phone flat in your hand."
        compassPoor && mode != MoveMode.IDLE -> "Compass needs calibrating: wave the phone in a figure of eight."
        mode != MoveMode.IDLE && !fresh -> "No GPS signal: using steps and compass, which is good for a room or a corridor."
        mode != MoveMode.IDLE && heading.compassDisturbed(now) -> "The compass is disturbed here: trusting the gyroscope."
        mode == MoveMode.IDLE && !fresh -> "Waiting for the GPS signal."
        else -> ""
    }

    companion object {
        const val EVAL_EVERY_MS = 100L
        const val TRAIL_EVERY_MS = 400L
        const val FIX_KEEP_MS = 20_000L
        const val SAVE_WINDOW_MS = 15_000L
        const val SAVE_EVERY_MS = 30_000L
        const val HOLD_PROMPT_MS = 30_000L
        const val MIN_WALK_M = 10.0
        const val MAX_FAR_FROM_ROUTE_M = 300.0
    }
}
