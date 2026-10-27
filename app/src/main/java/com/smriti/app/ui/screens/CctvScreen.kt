package com.smriti.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smriti.app.AppContainer
import com.smriti.app.cctv.SafeFlow

private val DarkPanel = Color(0xFF0F141C)
private val CardBg = Color(0xFF161E2B)
private val NeonGreen = Color(0xFF00E676)
private val NeonCyan = Color(0xFF00E5FF)
private val AccentYellow = Color(0xFFFFD600)
private val AlertRed = Color(0xFFFF1744)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CctvScreen(container: AppContainer, onClose: () -> Unit) {
    val cctvStatus by container.cctv.status.collectAsStateWithLifecycle()
    val previewBitmap by container.cctv.preview.collectAsStateWithLifecycle()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("Live & Count", "EventGraph AI", "SceneGuard AI", "SafeFlow AI", "VoiceOps AI")

    Column(
        Modifier
            .fillMaxSize()
            .background(DarkPanel)
            .statusBarsPadding()
    ) {
        // ---- Top App Bar ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "CCTV AI INTELLIGENCE",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                Text(
                    "Multi-Camera Event Reasoning & Person Analytics",
                    color = Color(0xFF90A4AE),
                    fontSize = 11.sp
                )
            }
            // Live Status indicator
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1B2838))
                    .border(1.dp, NeonGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(NeonGreen))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "${"%.1f".format(cctvStatus.fps)} FPS",
                        color = NeonGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // ---- Camera Selection Pills ----
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            container.cctv.availableCameras.forEach { cam ->
                val isSelected = cctvStatus.activeCamera == cam.displayName
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isSelected) NeonCyan.copy(alpha = 0.2f) else CardBg)
                        .border(
                            1.dp,
                            if (isSelected) NeonCyan else Color(0xFF263238),
                            RoundedCornerShape(14.dp)
                        )
                        .clickable { container.cctv.switchCamera(cam.displayName) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Videocam,
                            contentDescription = null,
                            tint = if (isSelected) NeonCyan else Color.Gray,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            cam.displayName,
                            color = if (isSelected) Color.White else Color(0xFFB0BEC5),
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        // ---- Live Surveillance Viewport with Person Count HUD ----
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
                .border(1.5.dp, Color(0xFF37474F), RoundedCornerShape(16.dp))
        ) {
            if (previewBitmap != null) {
                Image(
                    bitmap = previewBitmap!!.asImageBitmap(),
                    contentDescription = "CCTV Video Feed",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Connecting to camera stream...", color = Color.Gray, fontSize = 13.sp)
                }
            }

            // Top-left Camera Name HUD
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    cctvStatus.activeCamera.uppercase(),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Top-right Humans & Vehicles Detection HUD
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .background(Color(0xEE000000), RoundedCornerShape(8.dp))
                    .border(1.dp, NeonCyan.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("👥", fontSize = 13.sp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${cctvStatus.personCount}",
                        color = NeonCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.width(1.dp).height(12.dp).background(Color(0xFF455A64)))
                    Spacer(Modifier.width(10.dp))
                    Text("🚗", fontSize = 13.sp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${cctvStatus.vehicleCount}",
                        color = AccentYellow,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Bottom-right Quick "Set Baseline" button
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC263238))
                    .clickable {
                        container.sceneGuard.setBaseline(
                            cctvStatus.activeCamera,
                            listOf("laptop", "desk", "phone", "monitor", "chair")
                        )
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("📷 Set Baseline", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // ---- AI Modules Tab Row ----
        ScrollableTabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = DarkPanel,
            contentColor = Color.White,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                    color = NeonCyan,
                    height = 2.5.dp
                )
            },
            edgePadding = 16.dp,
            divider = { Divider(color = Color(0xFF263238), thickness = 1.dp) }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = {
                        Text(
                            title,
                            color = if (selectedTabIndex == index) NeonCyan else Color.Gray,
                            fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 12.sp
                        )
                    }
                )
            }
        }

        // ---- Tab Contents ----
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (selectedTabIndex) {
                0 -> LiveAndCountTab(container, cctvStatus)
                1 -> EventGraphTab(container)
                2 -> SceneGuardTab(container, cctvStatus)
                3 -> SafeFlowTab(container)
                4 -> VoiceOpsTab(container)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// TAB 1: Live & Count
// ---------------------------------------------------------------------------------------------
@Composable
private fun LiveAndCountTab(container: AppContainer, status: com.smriti.app.source.CctvSource.CctvStatus) {
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("REAL-TIME DETECTION & OCCUPANCY", color = Color(0xFF78909C), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        // Humans Counter
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("👥", fontSize = 16.sp)
                                Spacer(Modifier.width(6.dp))
                                Text("HUMANS", color = Color(0xFFB0BEC5), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("${status.personCount}", color = NeonCyan, fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text("Safe Cap: 6", color = Color(0xFF78909C), fontSize = 11.sp)
                        }

                        Box(Modifier.width(1.dp).height(60.dp).background(Color(0xFF263238)).align(Alignment.CenterVertically))

                        // Vehicles Counter
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🚗", fontSize = 16.sp)
                                Spacer(Modifier.width(6.dp))
                                Text("VEHICLES", color = Color(0xFFB0BEC5), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("${status.vehicleCount}", color = AccentYellow, fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text(if (status.vehicles.isNotEmpty()) status.vehicles.first() else "No vehicle", color = Color(0xFF78909C), fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("ACTIVE DETECTION TARGETS", color = Color(0xFF78909C), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PillTag("👤 Humans (${status.personCount})", NeonCyan)
                        PillTag("🚗 Vehicles (${status.vehicleCount})", AccentYellow)
                        status.vehicles.forEach { v ->
                            PillTag(v, Color(0xFFFF9100))
                        }
                        PillTag("Pedestrian Lane", Color(0xFFB0BEC5))
                        PillTag("Barrier Gate", Color(0xFFB0BEC5))
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("CCTV CAMERA DETAILS", color = Color(0xFF78909C), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Camera: ${status.activeCamera}", color = Color.White, fontSize = 13.sp)
                    Text("Stream Protocol: HTTP MJPEG / RTSP Edge Ingest", color = Color(0xFF90A4AE), fontSize = 12.sp)
                    Text("Frames Processed: ${status.frames}", color = Color(0xFF90A4AE), fontSize = 12.sp)
                    Text("Inference Latency: ${status.latencyMs} ms", color = Color(0xFF90A4AE), fontSize = 12.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// TAB 2: EventGraph AI
// ---------------------------------------------------------------------------------------------
@Composable
private fun EventGraphTab(container: AppContainer) {
    val events by container.eventGraph.events.collectAsStateWithLifecycle()
    val journeys by container.eventGraph.journeys.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("CROSS-CAMERA OBJECT & PERSON JOURNEYS", color = Color(0xFF78909C), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Traces items and persons across multiple camera zones and timestamps", color = Color(0xFFB0BEC5), fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
        }

        // Show active entity journeys
        items(journeys.values.toList(), key = { it.entityId }) { j ->
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(j.entityId.uppercase(), color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Box(
                            Modifier.clip(RoundedCornerShape(6.dp))
                                .background(if (j.status == "ACTIVE") Color(0x3300E676) else Color(0x33FFD600))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(j.status, color = if (j.status == "ACTIVE") NeonGreen else AccentYellow, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Current Location: ${j.currentCamera}", color = Color.White, fontSize = 12.sp)
                    Text("Timeline Steps: ${j.path.size} camera observations", color = Color(0xFF90A4AE), fontSize = 11.sp)

                    Divider(color = Color(0xFF263238), thickness = 1.dp, modifier = Modifier.padding(vertical = 8.dp))

                    // Step sequence
                    j.path.forEachIndexed { idx, step ->
                        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("0${idx + 1}.", color = Color(0xFF78909C), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.width(6.dp))
                            Text("[${step.camera}]", color = Color(0xFFB0BEC5), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(6.dp))
                            Text(step.details, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// TAB 3: SceneGuard AI
// ---------------------------------------------------------------------------------------------
@Composable
private fun SceneGuardTab(container: AppContainer, cctvStatus: com.smriti.app.source.CctvSource.CctvStatus) {
    val changes by container.sceneGuard.changes.collectAsStateWithLifecycle()
    val baselines by container.sceneGuard.baselines.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("SCENEGUARD — REMEMBER WHAT CHANGED", color = Color(0xFF78909C), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("Baseline state comparisons & missing/added item alerts", color = Color(0xFFB0BEC5), fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        // Active baseline status
        item {
            val baseline = baselines[cctvStatus.activeCamera]
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Monitored Baseline: ${cctvStatus.activeCamera}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            if (baseline != null) "Memorized Items: ${baseline.objects.joinToString()}" else "No baseline calibrated yet. Tap 'Set Baseline' above.",
                            color = Color(0xFF90A4AE),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        item {
            Text("DETECTED STATE DEVIATIONS", color = Color(0xFF78909C), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }

        items(changes, key = { it.id }) { c ->
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(c.objectName.uppercase(), color = if (c.urgency == "HIGH") AlertRed else AccentYellow, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Box(
                            Modifier.clip(RoundedCornerShape(4.dp))
                                .background(if (c.urgency == "HIGH") AlertRed.copy(alpha = 0.2f) else AccentYellow.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(c.type.name, color = if (c.urgency == "HIGH") AlertRed else AccentYellow, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(c.details, color = Color.White, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Zone: ${c.camera}", color = Color(0xFF90A4AE), fontSize = 11.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// TAB 4: SafeFlow AI
// ---------------------------------------------------------------------------------------------
@Composable
private fun SafeFlowTab(container: AppContainer) {
    val hazards by container.safeFlow.hazards.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("SAFEFLOW AI — SITE HAZARDS & PREDICTION", color = Color(0xFF78909C), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Predicts and alerts on developing safety and occupancy risks", color = Color(0xFFB0BEC5), fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
        }

        if (hazards.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("No active safety violations detected across all cameras.", color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        }

        items(hazards, key = { it.id }) { h ->
            val color = when (h.urgency) {
                SafeFlow.Urgency.CRITICAL -> AlertRed
                SafeFlow.Urgency.HIGH -> Color(0xFFFF5722)
                SafeFlow.Urgency.MEDIUM -> AccentYellow
                SafeFlow.Urgency.LOW -> NeonCyan
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(h.title, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Box(
                            Modifier.clip(RoundedCornerShape(4.dp))
                                .background(color.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(h.urgency.name, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(h.description, color = Color.White, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Action: ${h.recommendedAction}", color = NeonGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                        Text(
                            "Dismiss",
                            color = Color(0xFF78909C),
                            fontSize = 11.sp,
                            modifier = Modifier.clickable { container.safeFlow.dismissHazard(h.id) }
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// TAB 5: VoiceOps AI & Query2Evidence
// ---------------------------------------------------------------------------------------------
@Composable
private fun VoiceOpsTab(container: AppContainer) {
    val history by container.voiceOps.history.collectAsStateWithLifecycle()
    var inputQuery by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(14.dp)) {
        // Suggested investigation chips
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "Show White SUV journey",
                "Who drove the White SUV?",
                "Which safety problems need attention?",
                "Where did the parcel go?",
                "Switch to Parking Area",
                "What changed since yesterday?"
            ).forEach { prompt ->
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(CardBg)
                        .clickable {
                            inputQuery = prompt
                            container.voiceOps.onUserQuery(prompt)
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(prompt, color = Color(0xFFCFD8DC), fontSize = 11.sp)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Conversation history with Evidence Cards
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(history) { turn ->
                if (turn.role == "user") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        Box(
                            Modifier.clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF005CB2))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(turn.text, color = Color.White, fontSize = 13.sp)
                        }
                    }
                } else {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🤖 SMRITI VOICEOPS", color = NeonCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                if (turn.evidenceReport != null) {
                                    Text("Confidence: ${(turn.evidenceReport.confidence * 100).toInt()}%", color = NeonGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(turn.text, color = Color.White, fontSize = 13.sp)

                            // Evidence Steps if available
                            if (turn.evidenceReport != null && turn.evidenceReport.steps.isNotEmpty()) {
                                Divider(color = Color(0xFF263238), thickness = 1.dp, modifier = Modifier.padding(vertical = 8.dp))
                                Text("VERIFIED EVIDENCE TRAIL:", color = Color(0xFF78909C), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                turn.evidenceReport.steps.forEach { step ->
                                    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("✓", color = NeonGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.width(4.dp))
                                        Text("[${step.timeString}]", color = AccentYellow, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                        Spacer(Modifier.width(6.dp))
                                        Text(step.description, color = Color(0xFFE0E0E0), fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Operator Query Input Field
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputQuery,
                onValueChange = { inputQuery = it },
                placeholder = { Text("Ask VoiceOps or investigate cameras...", color = Color.Gray, fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = CardBg,
                    unfocusedContainerColor = CardBg,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = Color(0xFF37474F)
                )
            )

            Box(
                Modifier.size(44.dp)
                    .clip(CircleShape)
                    .background(NeonCyan)
                    .clickable {
                        if (inputQuery.isNotBlank()) {
                            val q = inputQuery
                            inputQuery = ""
                            container.voiceOps.onUserQuery(q)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Send, contentDescription = "Ask", tint = Color.Black, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun PillTag(text: String, color: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
