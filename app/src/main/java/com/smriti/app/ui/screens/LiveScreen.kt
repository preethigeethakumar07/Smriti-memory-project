@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.smriti.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smriti.app.AppContainer
import com.smriti.app.ui.components.clickableH
import com.smriti.app.live.LivePhase
import com.smriti.app.ui.components.ChamferShape
import com.smriti.app.ui.theme.Bad
import com.smriti.app.ui.theme.RaceBlue
import com.smriti.app.ui.theme.RaceDeep
import com.smriti.app.ui.theme.RaceRed
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Live mode. The camera fills the screen inside a viewfinder, the conversation is by voice, and the only
 * on-screen text is a small telemetry strip. The "Iris dial" is Smriti's own indicator: a tachometer ring that
 * climbs with your voice around an eye whose pupil follows what Smriti is doing. Everything said is saved
 * to the chat on Home.
 */
@Composable
fun LiveScreen(container: AppContainer, withCamera: Boolean, onClose: () -> Unit) {
    val live by container.live.state.collectAsStateWithLifecycle()
    val settings by container.settings.collectAsStateWithLifecycle()
    val source = settings?.source ?: "phone"
    val glassesPreview by container.esp32.preview.collectAsStateWithLifecycle()
    var wasActive by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0L) }

    // "Only this time" mic grants expire; ask again before listening so Live never starts deaf
    val context = androidx.compose.ui.platform.LocalContext.current
    val micAsk = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            micAsk.launch(android.Manifest.permission.RECORD_AUDIO)
        }
        // Home's "Look for something" sets a target first; this camera session then looks for it
        val target = container.pendingTarget.value
        container.pendingTarget.value = null
        container.live.start(cameraOn = withCamera, source = source, target = target)
    }
    LaunchedEffect(live.active) {
        if (live.active) wasActive = true else if (wasActive) onClose()
    }
    LaunchedEffect(live.startedAt, live.active) {
        while (live.active) {
            elapsed = (android.os.SystemClock.elapsedRealtime() - live.startedAt) / 1000
            delay(1000)
        }
    }
    BackHandler { container.live.end(); onClose() }
    SixtyHertzWhileOpen()

    Box(Modifier.fillMaxSize().background(Color(0xFF07090D))) {
        // ---- the world ----------------------------------------------------------------------------
        if (live.cameraOn) {
            if (source == "glasses") {
                glassesPreview?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            } else {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            container.camera.attachPreview(this)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { container.camera.attachPreview(it) },
                    onRelease = { container.camera.attachPreview(null) }
                )
            }
        } else {
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(RaceBlue.copy(alpha = 0.20f), Color.Transparent), Offset(540f, 800f), 950f)))
        }
        Box(Modifier.fillMaxWidth().height(170.dp).background(Brush.verticalGradient(listOf(Color(0xB307090D), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(340.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF207090D)))))
        if (live.cameraOn) Viewfinder(Modifier.fillMaxSize())
        // ---- private scene: after a picture that looks undressed the view is covered while capture and analysis pause --------------
        val privacy by container.engine.guard.status.collectAsStateWithLifecycle()
        var privateNow by remember { mutableStateOf(false) }
        LaunchedEffect(privacy.blockedUntil) {
            val left = privacy.blockedUntil - android.os.SystemClock.elapsedRealtime()
            if (left > 0) { privateNow = true; delay(left); privateNow = false }
        }
        if (privateNow) {
            Box(Modifier.fillMaxSize().background(Color(0xF2000000)), contentAlignment = Alignment.Center) {
                Text("Private scene. Paused for a moment.", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 32.dp, end = 104.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        // ---- guide: a ring on the object the user asked to find, and which way to turn ----------------------------
        val guide by container.engine.guide.state.collectAsStateWithLifecycle()
        guide?.let { g -> if (live.cameraOn) GuideOverlay(g, showBanner = live.target == null) }

        // ---- telemetry strip: the only text on screen -----------------------------------------------
        Row(
            Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            val t = rememberInfiniteTransition(label = "rec")
            val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
            Chip {
                // alpha is read inside graphicsLayer, so the pulse only updates a layer; it no longer recomposes the strip 60 times a second
                Box(Modifier.size(8.dp).clip(CircleShape).background(RaceRed).graphicsLayer { alpha = a })
                Spacer(Modifier.width(7.dp))
                Text("${"%d:%02d".format(elapsed / 60, elapsed % 60)}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Chip { Text(if (source == "glasses") "GLASSES" else "PHONE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.2.sp) }
            // Honest label: "ON-DEVICE" only while the on-device recogniser is the one listening.
            val onDevice = live.voicePath == "on-device"
            Chip {
                Icon(Icons.Default.Lock, null, tint = if (onDevice) RaceBlue else Color(0xFFF2B84B), modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    if (onDevice) "ON-DEVICE" else "SYSTEM VOICE", color = if (onDevice) RaceBlue else Color(0xFFF2B84B),
                    fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.2.sp
                )
            }
        }
        // ---- look-for: what the camera is searching for, and the moment it is confirmed ----------------------
        live.target?.let { goal ->
            Column(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 60.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    Modifier.clip(ChamferShape(12.dp)).background(Color(0xCC0B1018)).border(1.dp, RaceBlue.copy(alpha = 0.6f), ChamferShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, null, tint = RaceBlue, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Looking for: $goal", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                }
                Text(
                    live.found ?: "Move the camera slowly around, then tap the red X when you are done.",
                    color = if (live.found != null) Color(0xFF7CFFB2) else Color(0xCCFFFFFF), fontSize = 13.sp,
                    fontWeight = if (live.found != null) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clip(ChamferShape(10.dp)).background(Color(0xB30B1018)).padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        // spatial navigation: after "where is my mouse" an arrow turns as you turn, pointing at where it was
        val nav by container.engine.nav.collectAsStateWithLifecycle()
        nav?.let { target -> NavOverlay(container, target, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 62.dp)) }
        // no heat warning is shown (the user's choice): when the phone is warm the camera still slows down quietly (PowerPolicy)
        live.notice?.let { n ->
            Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 130.dp, start = 24.dp, end = 24.dp)) {
                Text(
                    n, color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.clip(ChamferShape(10.dp)).background(Color(0xCC8A1C20)).padding(horizontal = 14.dp, vertical = 9.dp)
                )
            }
        }

        // ---- words on screen: what you are saying, what the phone is doing, then the answer ---------------------
        val showCaptions = settings?.liveCaptions != false
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 236.dp, start = 24.dp, end = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // always say what the microphone is doing, so it is clear Smriti is waiting for you
            val status = when {
                live.hint.isNotBlank() && live.heard.isBlank() -> live.hint
                live.phase == LivePhase.LISTENING && live.heard == "..." -> "Understanding what you said..."
                live.phase == LivePhase.LISTENING && live.heard.isBlank() && !live.muted ->
                    if (live.hearing) "Hearing you..." else "Listening... speak now"
                live.phase == LivePhase.PAUSED -> "Resting. Tap the eye to talk again."
                else -> null
            }
            if (status != null) {
                Text(
                    status, color = Color.White, fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.clip(ChamferShape(12.dp)).background(Color(0xB30B1018)).border(1.dp, RaceBlue.copy(alpha = 0.5f), ChamferShape(12.dp)).padding(horizontal = 16.dp, vertical = 9.dp)
                )
            }
            if (live.heard.isNotBlank() && live.heard != "..." && (live.phase == LivePhase.LISTENING || live.phase == LivePhase.THINKING)) {
                Column(
                    Modifier.clip(ChamferShape(12.dp)).background(Color(0xCC0B1018)).border(1.dp, RaceBlue.copy(alpha = 0.5f), ChamferShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text("YOU", color = RaceBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                    Text(live.heard, color = Color.White, fontSize = 18.sp, maxLines = 3, fontWeight = FontWeight.Medium)
                }
            }
            if (live.phase == LivePhase.THINKING) ThinkingChip(live.step)
            if (showCaptions && live.phase != LivePhase.THINKING) live.answer?.let { a ->
                // the whole answer stays on screen (scrollable) until the next question, long lists included
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(ChamferShape(12.dp)).background(Color(0xE60B1018))
                        .border(1.dp, Color(0x40FFFFFF), ChamferShape(12.dp)).verticalScroll(androidx.compose.foundation.rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(a, color = Color.White, fontSize = 16.sp, lineHeight = 22.sp)
                }
            }
        }
        // ---- the iris dial, bottom centre ------------------------------------------------------------
        val levelState = container.live.level.collectAsStateWithLifecycle()
        IrisDial(
            phase = live.phase, level = levelState,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 30.dp).size(196.dp)
                .clip(CircleShape).clickableH { container.live.resume() }
        )

        // ---- control rail, right edge (not a row of pills) --------------------------------------------
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RailButton(if (live.cameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff, on = live.cameraOn) { container.live.toggleCamera() }
            RailButton(if (live.muted) Icons.Default.MicOff else Icons.Default.Mic, on = !live.muted, danger = live.muted) { container.live.toggleMute() }
            Box(Modifier.width(26.dp).height(1.dp).background(Color(0x44FFFFFF)))
            RailButton(Icons.Default.Close, on = true, end = true) { container.live.end(); onClose() }
        }
    }
}

/** "Thinking, processing locally" with the step the phone is on. The dial orbits while this shows. */
@Composable
private fun ThinkingChip(step: String) {
    val t = rememberInfiniteTransition(label = "think")
    val dots by t.animateFloat(0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "dots")
    Column(
        Modifier.clip(ChamferShape(12.dp)).background(Color(0xCC0B1018)).border(1.dp, Color(0x40FFFFFF), ChamferShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("THINKING" + ".".repeat(dots.toInt() + 1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp)
        Text(if (step.isBlank()) "Processing on this phone" else "$step, on this phone", color = RaceBlue, fontSize = 12.sp)
    }
}

@Composable
private fun Chip(content: @Composable () -> Unit) {
    Row(
        Modifier.clip(ChamferShape(8.dp)).background(Color(0x99111826))
            .border(1.dp, Color(0x40FFFFFF), ChamferShape(8.dp)).padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

@Composable
private fun RailButton(icon: ImageVector, on: Boolean, danger: Boolean = false, end: Boolean = false, onClick: () -> Unit) {
    val shape = ChamferShape(12.dp)
    val fill = when {
        end -> Brush.linearGradient(listOf(Color(0xFFFF6B70), RaceRed, Color(0xFF7A1216)))
        danger -> Brush.linearGradient(listOf(Color(0x66E4262C), Color(0x33E4262C)))
        on -> Brush.linearGradient(listOf(Color(0xFF8CC8FF), RaceBlue, RaceDeep))
        else -> Brush.linearGradient(listOf(Color(0xCC1A2333), Color(0xCC0E131B)))
    }
    Box(
        Modifier.size(54.dp).clip(shape).background(fill)
            .border(1.dp, Brush.linearGradient(listOf(Color(0x99FFFFFF), Color(0x11FFFFFF))), shape)
            .clickableH(onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = if (danger) Bad else Color.White, modifier = Modifier.size(26.dp)) }
}

/**
 * Names the detector found, as small tags under the strip. Boxes are deliberately not drawn: the analysis frame's
 * orientation is not guaranteed to match the preview on every phone, and a wrong box is worse than none.
 */
@Composable
private fun DetectionTags(dets: List<com.smriti.app.models.DetectedObject>, modifier: Modifier) {
    androidx.compose.foundation.layout.FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        dets.sortedByDescending { it.confidence }.distinctBy { it.label }.take(6).forEach { d ->
            Row(
                Modifier.clip(ChamferShape(7.dp)).background(Color(0xCC1B3FA0)).border(1.dp, RaceBlue.copy(alpha = 0.8f), ChamferShape(7.dp)).padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(d.label.uppercase(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                Spacer(Modifier.width(6.dp))
                Text("${(d.confidence * 100).toInt()}%", color = Color(0xFF9CD0FF), fontSize = 11.sp)
            }
        }
    }
}
/** Arrow plus name and distance. The arrow turns with the phone using the compass heading, so it keeps pointing at the spot. */
@Composable
private fun NavChip(p: com.smriti.app.spatial.Point3, pose: com.smriti.app.spatial.Pose?, modifier: Modifier) {
    val rel = pose?.let { com.smriti.app.spatial.Spatial3D.relative(p, it) }
    val turnTarget = if (rel != null && rel.headingValid) rel.turnDeg else 0f
    // shortest way round, so the arrow never spins the long way when the angle wraps from 180 to -180
    var shown by remember { mutableStateOf(turnTarget) }
    LaunchedEffect(turnTarget) { shown += com.smriti.app.spatial.Spatial3D.wrap180(turnTarget - shown) }
    val turn by animateFloatAsState(shown, tween(160), label = "navTurn")
    val dim = rel == null || !rel.headingValid
    Row(
        modifier.clip(ChamferShape(12.dp)).background(Color(0xDD0B1018)).border(1.dp, RaceBlue.copy(alpha = 0.6f), ChamferShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.size(34.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            rotate(turn, c) {
                val a = androidx.compose.ui.graphics.Path().apply {
                    moveTo(c.x, c.y - size.height * 0.46f); lineTo(c.x + size.width * 0.30f, c.y + size.height * 0.36f)
                    lineTo(c.x, c.y + size.height * 0.16f); lineTo(c.x - size.width * 0.30f, c.y + size.height * 0.36f); close()
                }
                drawPath(a, if (dim) Color(0x66FFFFFF) else RaceBlue)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text("${p.label.uppercase()}  ${"%.1f".format(p.distance)} m", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                when {
                    rel == null -> "estimate"
                    !rel.headingValid -> "no compass on this phone: distance only"
                    kotlin.math.abs(rel.turnDeg) < 15f -> "straight ahead"
                    rel.turnDeg < 0 -> "${kotlin.math.abs(rel.turnDeg).toInt()}° to your left"
                    else -> "${rel.turnDeg.toInt()}° to your right"
                },
                color = Color(0xFF9CD0FF), fontSize = 12.sp
            )
        }
    }
}

/** Four corner brackets and a faint centre cross: the camera feed reads as a viewfinder, not a video call. */
@Composable
private fun Viewfinder(modifier: Modifier) {
    Canvas(modifier) {
        val inset = 20.dp.toPx()
        val top = 112.dp.toPx()
        val bottom = size.height - 290.dp.toPx()
        val len = 30.dp.toPx()
        val c = Color(0xB3FFFFFF)
        val sw = 2.5f.dp.toPx()
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(c, Offset(x, y), Offset(x + dx * len, y), sw, StrokeCap.Round)
            drawLine(c, Offset(x, y), Offset(x, y + dy * len), sw, StrokeCap.Round)
        }
        corner(inset, top, 1f, 1f)
        corner(size.width - inset, top, -1f, 1f)
        corner(inset, bottom, 1f, -1f)
        corner(size.width - inset, bottom, -1f, -1f)
        val cx = size.width / 2
        val cy = (top + bottom) / 2
        val arm = 9.dp.toPx()
        val faint = Color(0x66FFFFFF)
        drawLine(faint, Offset(cx - arm, cy), Offset(cx + arm, cy), 1.5f.dp.toPx())
        drawLine(faint, Offset(cx, cy - arm), Offset(cx, cy + arm), 1.5f.dp.toPx())
    }
}

/**
 * A rev-counter ring around an eye. Listening: the ring climbs with your voice. Speaking: it pulses.
 * Thinking: a bright segment chases around. Resting: nearly dark, eye half-closed.
 */
@Composable
fun IrisDial(phase: LivePhase, level: androidx.compose.runtime.State<Float>, modifier: Modifier = Modifier) {
    // the clock runs only while the dial is animated (thinking, speaking); a resting dial costs nothing
    val animated = phase == LivePhase.THINKING || phase == LivePhase.SPEAKING
    val clock = if (animated) {
        val t = rememberInfiniteTransition(label = "dial")
        val c by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "clock")
        c
    } else 0f
    val total = 54

    Canvas(modifier) {
        val smooth = level.value            // read while drawing: a changing loudness redraws this canvas only
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        // glass disc behind everything
        drawCircle(Brush.radialGradient(listOf(Color(0xE60E1626), Color(0xE605070B)), c, r), r)
        drawCircle(Color(0x33FFFFFF), r - 1f, c, style = Stroke(1.5f))

        val lit: (Int) -> Float = { i ->
            when (phase) {
                LivePhase.LISTENING -> if (i < (3 + smooth * (total - 3)).toInt()) 1f else 0f
                LivePhase.SPEAKING -> if (i < (total * (0.35f + 0.30f * (0.5f + 0.5f * sin(clock * 2f)))).toInt()) 1f else 0f
                LivePhase.THINKING -> {
                    val head = (clock / (2 * PI.toFloat()) * total).toInt()
                    val d = ((i - head) % total + total) % total
                    if (d < 8) 1f - d / 8f else 0f
                }
                else -> if (i < 2) 0.5f else 0f
            }
        }
        for (i in 0 until total) {
            val a = Math.toRadians((135.0 + 270.0 * i / (total - 1)))
            val f = i / (total - 1f)
            val on = lit(i)
            val col = when {
                f > 0.86f -> RaceRed
                f > 0.55f -> Color(0xFF8CC8FF)
                else -> RaceBlue
            }
            val r1 = r * (if (i % 9 == 0) 0.76f else 0.82f)
            val r2 = r * 0.93f
            drawLine(
                if (on > 0f) col.copy(alpha = 0.35f + 0.65f * on) else Color(0x2EFFFFFF),
                Offset(c.x + (r1 * cos(a)).toFloat(), c.y + (r1 * sin(a)).toFloat()),
                Offset(c.x + (r2 * cos(a)).toFloat(), c.y + (r2 * sin(a)).toFloat()),
                strokeWidth = if (on > 0f) 5f else 3f, cap = StrokeCap.Round
            )
        }

        // the eye
        val ir = r * 0.56f
        drawCircle(Brush.radialGradient(listOf(Color(0xFF1B3FA0), Color(0xFF0B1B45), Color(0xFF060A14)), c, ir), ir, c)
        for (k in 1..3) drawCircle(RaceBlue.copy(alpha = 0.10f + 0.05f * k), ir * (0.35f + 0.2f * k), c, style = Stroke(1.5f))
        val open = if (phase == LivePhase.PAUSED || phase == LivePhase.IDLE) 0.45f else 1f
        val pupil = ir * when (phase) {
            LivePhase.LISTENING -> 0.26f + 0.16f * smooth
            LivePhase.SPEAKING -> 0.30f + 0.05f * sin(clock * 3f)
            LivePhase.THINKING -> 0.22f + 0.04f * abs(sin(clock * 4f))
            else -> 0.18f
        }
        drawCircle(Color(0xFF020409), pupil, c)
        drawCircle(Color.White.copy(alpha = 0.85f * open), pupil * 0.22f, Offset(c.x - pupil * 0.35f, c.y - pupil * 0.38f))
        // upper lid closes the eye while resting
        if (open < 1f) {
            val eye = androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, ir)) }
            clipPath(eye) {
                drawRect(Color(0xF005070B), Offset(c.x - ir, c.y - ir), androidx.compose.ui.geometry.Size(ir * 2, ir * 2 * (1f - open) * 0.75f))
            }
        }
        drawCircle(Brush.sweepGradient(listOf(RaceBlue, RaceDeep, RaceRed, RaceDeep, RaceBlue), c), ir, c, style = Stroke(3f))
        // the AI orbit: three satellites circling the eye while it thinks
        if (phase == LivePhase.THINKING) {
            val or = ir * 1.22f
            drawCircle(Color(0x3329A8FF), or, c, style = Stroke(1.5f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 10f))))
            for (k in 0..2) {
                val a = clock * 2f + k * (2f * PI.toFloat() / 3f)
                val p = Offset(c.x + or * cos(a), c.y + or * sin(a))
                drawCircle(RaceBlue.copy(alpha = 0.30f), 15f, p)
                drawCircle(if (k == 0) RaceRed else Color.White, 5.5f, p)
            }
        }
    }
}


/**
 * The ring around the object the user asked for, drawn on the camera preview, with its name and a hint about which way to turn.
 * The camera picture is shown cropped to fill the screen, so the box (given as fractions of the whole picture) is mapped through
 * the same crop.
 */
@Composable
private fun GuideOverlay(g: com.smriti.app.ai.GuideState, showBanner: Boolean) {
    val green = Color(0xFF7CFFB2)
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val vw = constraints.maxWidth.toFloat(); val vh = constraints.maxHeight.toFloat()
        val box = g.box
        if (g.phase == com.smriti.app.ai.GuideState.Phase.FOUND && box != null && g.frameW > 0 && g.frameH > 0) {
            val scale = maxOf(vw / g.frameW, vh / g.frameH)
            val dx = (vw - g.frameW * scale) / 2f; val dy = (vh - g.frameH * scale) / 2f
            val l = dx + box.left * g.frameW * scale; val tp = dy + box.top * g.frameH * scale
            val r = dx + box.right * g.frameW * scale; val b = dy + box.bottom * g.frameH * scale
            val pulse by rememberInfiniteTransition(label = "guide").animateFloat(0.55f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
            Canvas(Modifier.fillMaxSize()) {
                val tl = Offset(l, tp); val sz = androidx.compose.ui.geometry.Size(r - l, b - tp)
                val rad = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx())
                drawRoundRect(green.copy(alpha = 0.14f), tl, sz, rad)
                drawRoundRect(green.copy(alpha = pulse), tl, sz, rad, style = androidx.compose.ui.graphics.drawscope.Stroke(4.dp.toPx()))
            }
            Text(
                g.target.uppercase(), color = Color(0xFF07090D), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                modifier = Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(l.toInt(), (tp - 30.dp.toPx()).toInt().coerceAtLeast(0)) }
                    .clip(ChamferShape(8.dp)).background(green).padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        if (showBanner) {
            Column(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 60.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    Modifier.clip(ChamferShape(12.dp)).background(Color(0xCC0B1018)).border(1.dp, green.copy(alpha = 0.6f), ChamferShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, null, tint = green, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Finding: ${g.target}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                }
                Text(
                    g.hint, color = if (g.phase == com.smriti.app.ai.GuideState.Phase.FOUND) green else Color(0xCCFFFFFF), fontSize = 14.sp,
                    fontWeight = if (g.phase == com.smriti.app.ai.GuideState.Phase.FOUND) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clip(ChamferShape(10.dp)).background(Color(0xB30B1018)).padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/** The arrow to a remembered object. It reads the compass pose itself, so the rest of the screen is not redrawn with every turn. */
@Composable
private fun NavOverlay(container: AppContainer, target: com.smriti.app.core.SmritiEngine.NavTarget, modifier: Modifier) {
    val pose by container.engine.pose.pose.collectAsStateWithLifecycle()
    LaunchedEffect(target) { delay(45_000L); container.engine.clearNav() }
    NavChip(target.point, pose, modifier)
}

/** Asks for 60 Hz while Live is open: the panel can run at 144 Hz, which doubles the drawing work for no visible gain here. */
@Composable
private fun SixtyHertzWhileOpen() {
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    androidx.compose.runtime.DisposableEffect(activity) {
        val w = activity?.window
        val before = w?.attributes?.preferredRefreshRate ?: 0f
        w?.let { it.attributes = it.attributes.apply { preferredRefreshRate = 60f } }
        onDispose { w?.let { it.attributes = it.attributes.apply { preferredRefreshRate = before } } }
    }
}
