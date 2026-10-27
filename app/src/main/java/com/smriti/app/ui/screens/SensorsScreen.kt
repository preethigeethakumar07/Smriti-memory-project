package com.smriti.app.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smriti.app.AppContainer
import com.smriti.app.core.ModelRegistry
import com.smriti.app.net.InternetBlocker
import com.smriti.app.telemetry.DeviceInfo
import com.smriti.app.telemetry.SystemSnapshot
import com.smriti.app.ui.components.AreaChart
import com.smriti.app.ui.components.BarMeter
import com.smriti.app.ui.components.BigStat
import com.smriti.app.ui.components.ChamferTabs
import com.smriti.app.ui.components.ChartCard
import com.smriti.app.ui.components.GaugeArc
import com.smriti.app.ui.components.KeyValueRow
import com.smriti.app.ui.components.Pill
import com.smriti.app.ui.components.SmritiCard
import com.smriti.app.ui.components.TriStripe
import com.smriti.app.ui.theme.Bad
import com.smriti.app.ui.theme.Good
import com.smriti.app.ui.theme.RaceBlue
import com.smriti.app.ui.theme.RaceRed
import com.smriti.app.ui.theme.Warn
import com.smriti.app.util.Metrics
import kotlinx.coroutines.delay
import kotlin.math.log10

private const val HISTORY = 60
private val Sky = Color(0xFF9CD0FF)
private val Indigo = Color(0xFF5B7CFF)
private val Silver = Color(0xFFC9D2E0)
private val TABS = listOf("Live", "Compute", "Power", "Device", "Models", "Network")

private fun MutableList<Float>.push(v: Float) {
    add(v)
    if (size > HISTORY) removeAt(0)
}

/**
 * The instrument cluster. Every number here is read from the phone or measured by the app; nothing is invented.
 * One tab at a time keeps it uncluttered, and everything moves once per second.
 */
@Composable
fun SensorsScreen(container: AppContainer) {
    val context = LocalContext.current
    val engine by container.engine.state.collectAsStateWithLifecycle()
    val models by ModelRegistry.models.collectAsStateWithLifecycle()
    val glasses by container.esp32.status.collectAsStateWithLifecycle()
    val live by remember { DeviceInfo.liveSensors(context) }.collectAsStateWithLifecycle(emptyMap())
    var tab by rememberSaveable { mutableIntStateOf(0) }

    var snap by remember { mutableStateOf<SystemSnapshot?>(null) }
    var busyPct by remember { mutableStateOf(0f) }
    var stages by remember { mutableStateOf(Metrics.snapshot()) }
    val cpuHist = remember { mutableStateListOf<Float>() }
    val ramHist = remember { mutableStateListOf<Float>() }
    val busyHist = remember { mutableStateListOf<Float>() }
    val tempHist = remember { mutableStateListOf<Float>() }
    val drawHist = remember { mutableStateListOf<Float>() }
    val motionHist = remember { mutableStateListOf<Float>() }
    val lightHist = remember { mutableStateListOf<Float>() }
    val camera = remember { DeviceInfo.backCamera(context) }
    val sensorList = remember { DeviceInfo.sensors(context) }
    val liveNow by androidx.compose.runtime.rememberUpdatedState(live)
    var ai by remember { mutableStateOf(emptyList<com.smriti.app.ai.AiMeter.Row>()) }
    val npuHist = remember { mutableStateListOf<Float>() }

    LaunchedEffect(Unit) {
        var lastBusy = container.engine.busyNanos.get()
        var lastWall = SystemClock.elapsedRealtimeNanos()
        while (true) {
            val s = container.stats.sample()
            snap = s
            val busy = container.engine.busyNanos.get()
            val wall = SystemClock.elapsedRealtimeNanos()
            busyPct = ((busy - lastBusy).toFloat() / (wall - lastWall).coerceAtLeast(1) * 100f).coerceIn(0f, 100f)
            lastBusy = busy; lastWall = wall
            stages = Metrics.snapshot()
            ai = com.smriti.app.ai.AiMeter.rows()
            npuHist.push(ai.firstOrNull { it.backend.startsWith("NPU") }?.busyPct ?: 0f)
            cpuHist.push(s.cpuPercentOfDevice); ramHist.push(s.appRamMb.toFloat())
            busyHist.push(busyPct); tempHist.push(s.batteryTempC); drawHist.push(s.currentMa.toFloat())
            liveNow["accel"]?.let { motionHist.push(it) }
            liveNow["light"]?.let { lightHist.push(it) }
            delay(1000)
        }
    }

    val s = snap
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SENSORS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(Modifier.height(4.dp))
                TriStripe(width = 56.dp)
            }
            Pill("LIVE  1 Hz", Good)
        }
        Text("${DeviceInfo.summary}  |  ${DeviceInfo.soc}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
        ChamferTabs(TABS, tab, { tab = it })
        Spacer(Modifier.height(12.dp))

        if (s == null) { Text("Sampling...", modifier = Modifier.padding(top = 24.dp)); return@Column }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (tab) {
                // ---- LIVE: the headline gauges, the funnel and the stage timings ------------------------------
                0 -> {
                    SmritiCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            GaugeArc(s.cpuPercentOfDevice / 100f, "${"%.0f".format(s.cpuPercentOfDevice)}%", "App CPU", size = 100.dp)
                            GaugeArc(s.sysUsedMb.toFloat() / s.sysTotalMb.coerceAtLeast(1), "${s.sysUsedMb * 100 / s.sysTotalMb.coerceAtLeast(1)}%", "RAM", size = 100.dp, color = Sky)
                            val npu = ai.firstOrNull { it.backend.startsWith("NPU") }
                            GaugeArc((npu?.busyPct ?: 0f) / 100f, if (npu == null) "-" else "${"%.0f".format(npu.busyPct)}%", "NPU busy", size = 100.dp, color = Indigo)
                        }
                        Text("NPU busy: time in NPU inference, last 10 s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                    // every on-device AI engine with its real backend, speed and load
                    SmritiCard {
                        Text("ON-DEVICE AI ENGINES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.2.sp)
                        if (ai.isEmpty()) Text("Models are loading...", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        ai.forEach { u ->
                            Column(Modifier.padding(top = 12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(u.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    Pill(if (u.state == "ready") u.backend else u.state, if (u.state == "ready") (if (u.backend.startsWith("NPU")) Good else Sky) else Bad)
                                }
                                if (u.state == "ready") {
                                    BarMeter("Busy (last 10 s)", "${"%.0f".format(u.busyPct)}%", u.busyPct / 100f, color = if (u.backend.startsWith("NPU")) Indigo else Sky)
                                    Text("avg ${u.avgMs} ms  |  ${u.calls} calls", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    ChartCard("NPU busy (measured)", "${"%.0f".format(npuHist.lastOrNull() ?: 0f)}%", npuHist.toList(), max = 100f, color = Indigo)
                    SmritiCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            BigStat("${engine.framesSeen}", "Seen")
                            BigStat("${engine.framesGated}", "Skipped", color = RaceBlue)
                            BigStat("${engine.framesProcessed}", "Analysed")
                        }
                        Spacer(Modifier.height(10.dp))
                        val seen = engine.framesSeen.coerceAtLeast(1).toFloat()
                        BarMeter("Skipped by the gate", "${(engine.framesGated * 100 / seen).toInt()}%", engine.framesGated / seen)
                        BarMeter("Analysed", "${(engine.framesProcessed * 100 / seen).toInt()}%", engine.framesProcessed / seen, color = Sky)
                        KeyValueRow("Backend", engine.backend.substringBefore(" ("))
                    }
                    ChartCard("Camera pipeline busy", "${"%.0f".format(busyPct)}%", busyHist.toList(), max = 100f, color = Sky, footnote = "ML Kit + EfficientDet time over wall time")
                    if (stages.isNotEmpty()) SmritiCard {
                        Text("STAGE LATENCY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.2.sp)
                        val top = stages.maxOf { it.second.median }.coerceAtLeast(1L).toFloat()
                        stages.forEach { (name, st) ->
                            BarMeter(
                                name.trim().replace('_', ' '), "${st.median} ms", st.median / top,
                                color = if (st.median / top > 0.8f) RaceRed else RaceBlue, sub = "last ${st.last} ms  |  p95 ${st.p95} ms"
                            )
                        }
                    }
                }
                // ---- COMPUTE -----------------------------------------------------------------------------------
                1 -> {
                    ChartCard("App CPU", "${"%.1f".format(s.cpuPercentOfDevice)}%", cpuHist.toList(), max = 100f, footnote = "of all ${s.cores} cores")
                    ChartCard("App memory", "${s.appRamMb} MB", ramHist.toList(), color = Sky)
                    SmritiCard {
                        BarMeter("System RAM", "${"%.1f".format(s.sysUsedMb / 1024f)} / ${"%.0f".format(s.sysTotalMb / 1024f)} GB", s.sysUsedMb.toFloat() / s.sysTotalMb.coerceAtLeast(1), color = Sky)
                        KeyValueRow("Threads", "${s.threads}")
                        KeyValueRow("Cores", "${s.cores}")
                    }
                }
                // ---- POWER -------------------------------------------------------------------------------------
                2 -> {
                    SmritiCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            GaugeArc(s.batteryPct / 100f, "${s.batteryPct}%", "Battery", size = 112.dp, sub = if (s.charging) "charging" else "on battery", color = if (s.batteryPct < 20) RaceRed else RaceBlue)
                            GaugeArc(s.batteryTempC / 60f, "${"%.1f".format(s.batteryTempC)}°", "Temperature", size = 112.dp, color = if (s.batteryTempC > 42f) RaceRed else Sky, sub = "of 60 C scale")
                        }
                    }
                    ChartCard("Battery temperature", "${"%.1f".format(s.batteryTempC)} C", tempHist.toList(), color = if (s.batteryTempC > 42f) RaceRed else Sky, footnote = "C, last ${tempHist.size} s")
                    ChartCard("Current draw", "${s.currentMa} mA", drawHist.toList(), color = Silver)
                    SmritiCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Thermal status", Modifier.weight(1f))
                            Pill(s.thermalStatus, if (s.thermalStatus == "Normal" || s.thermalStatus == "Light") Good else if (s.thermalStatus == "Moderate") Warn else Bad)
                        }
                        if (!s.thermalHeadroom.isNaN()) BarMeter("Thermal headroom", "%.2f".format(s.thermalHeadroom), s.thermalHeadroom, color = if (s.thermalHeadroom > 0.8f) RaceRed else RaceBlue, sub = "1.0 = the phone starts throttling")
                        KeyValueRow("Background capture", if (container.engine.governor.paused) "paused (hot)" else if (container.engine.governor.intervalMultiplier > 1) "slowed 2x" else "normal")
                    }
                }
                // ---- DEVICE: camera and motion sensors -----------------------------------------------------------
                3 -> {
                    SmritiCard {
                        Text("LIVE SENSORS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.2.sp)
                        val lx = live["light"]; val mv = live["accel"]; val px = live["proximity"]
                        BarMeter("Ambient light", lx?.let { "%.0f lx".format(it) } ?: "-", lx?.let { (log10(it + 1f) / 3.5f) } ?: 0f, color = Silver)
                        BarMeter("Motion (acceleration)", mv?.let { "%.1f m/s²".format(it) } ?: "-", (mv ?: 0f) / 20f, color = RaceBlue, sub = "9.8 = at rest")
                        BarMeter("Proximity", px?.let { if (it < 3f) "near" else "far" } ?: "-", if ((px ?: 5f) < 3f) 1f else 0.1f, color = Sky)
                    }
                    ChartCard("Motion", live["accel"]?.let { "%.1f".format(it) } ?: "-", motionHist.toList(), color = RaceBlue, footnote = "m/s², last ${motionHist.size} s")
                    ChartCard("Light", live["light"]?.let { "%.0f lx".format(it) } ?: "-", lightHist.toList(), color = Silver, footnote = "lux, last ${lightHist.size} s")
                    SmritiCard {
                        Text("CAMERA", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.2.sp)
                        if (camera == null) Text("Camera details unavailable.") else {
                            KeyValueRow("Resolution", camera.resolution)
                            KeyValueRow("Sensor size", camera.sensorSizeMm)
                            KeyValueRow("Focal length", camera.focalLengthMm)
                            KeyValueRow("Aperture", camera.aperture)
                            KeyValueRow("Frame rate", camera.fpsRanges)
                            KeyValueRow("Hardware level", camera.hardwareLevel)
                            KeyValueRow("Stabilisation", if (camera.stabilization) "optical" else "none")
                        }
                    }
                    SmritiCard {
                        Text("${sensorList.size} SENSORS ON THIS PHONE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.2.sp)
                        sensorList.take(14).forEach { KeyValueRow(it.type.replace('_', ' '), it.name.take(22)) }
                        if (sensorList.size > 14) Text("+ ${sensorList.size - 14} more", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // ---- MODELS ------------------------------------------------------------------------------------------
                4 -> models.sortedBy { it.name }.forEach { m ->
                    SmritiCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.name, fontWeight = FontWeight.Bold)
                                Text("${m.role}  |  ${m.backend}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (m.state == "failed" && m.note.isNotBlank()) Text(m.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Pill(
                                if (m.state == "ready" && m.loadMs > 0) "ready ${m.loadMs} ms" else m.state,
                                when (m.state) { "ready" -> Good; "loading" -> Warn; "failed" -> Bad; else -> MaterialTheme.colorScheme.onSurfaceVariant }
                            )
                        }
                    }
                }
                // ---- NETWORK -----------------------------------------------------------------------------------------
                else -> {
                    // what the privacy shield has caught since the app started: kinds and counts only, never the values
                    SmritiCard {
                        Text("PRIVACY SHIELD", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.2.sp)
                        val counts = engine.sensitiveCounts
                        if (counts.isEmpty()) {
                            Text("Nothing sensitive seen yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        } else {
                            val top = counts.values.max().coerceAtLeast(1).toFloat()
                            counts.entries.sortedByDescending { it.value }.forEach { (k, n) ->
                                val label = runCatching { com.smriti.app.privacy.SensitiveKind.valueOf(k).label }.getOrDefault(k)
                                BarMeter(label, "$n", n / top, color = if (k == "FACE") Sky else RaceRed, sub = runCatching { com.smriti.app.privacy.SensitiveKind.valueOf(k).action }.getOrNull())
                            }
                        }
                    }
                    SmritiCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            BigStat("${InternetBlocker.blockedCount}", "Internet blocked", color = RaceBlue)
                            BigStat("${InternetBlocker.lanCount}", "Local links")
                        }
                        Spacer(Modifier.height(8.dp))
                        val total = (s.txBytes + s.rxBytes).coerceAtLeast(1).toFloat()
                        BarMeter("Sent", "${s.txBytes / 1024} KB", s.txBytes / total, color = RaceBlue)
                        BarMeter("Received", "${s.rxBytes / 1024} KB", s.rxBytes / total, color = Sky)
                        KeyValueRow("Glasses link", glasses.state.name.lowercase())
                        if (InternetBlocker.lastHost.isNotBlank()) KeyValueRow("Last blocked host", InternetBlocker.lastHost, mono = true)
                        Text("Only your glasses on your Wi-Fi are allowed; everything else is refused.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}
