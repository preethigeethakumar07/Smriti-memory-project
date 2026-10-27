@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.smriti.app.ui.screens

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smriti.app.AppContainer
import com.smriti.app.memory.MovePlaceEntity
import com.smriti.app.memory.MoveRouteEntity
import com.smriti.app.move.CueType
import com.smriti.app.move.MoveMode
import com.smriti.app.move.MoveState
import com.smriti.app.move.SensorDiag
import com.smriti.app.ui.components.Divider
import com.smriti.app.ui.components.Icon3D
import com.smriti.app.ui.components.Pill
import com.smriti.app.ui.components.SwitchRow
import com.smriti.app.ui.components.clickableH
import com.smriti.app.ui.components.TriStripe
import com.smriti.app.ui.components.glass
import com.smriti.app.ui.theme.Bad
import com.smriti.app.ui.theme.Good
import com.smriti.app.ui.theme.RaceBlue
import com.smriti.app.ui.theme.Warn
import kotlinx.coroutines.launch

/**
 * Walk guide, for a person who is out and about and may not find the way home. No camera and no map: the phone counts steps, reads the
 * gyroscope and the compass, listens to GPS, and says "turn left", "go straight", "you have arrived" (with a buzz for each, so it can be
 * followed with the phone in a pocket). It brings the person back along the way they came, or straight towards a saved place.
 */
@Composable
fun MoveScreen(container: AppContainer, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val st by container.move.state.collectAsStateWithLifecycle()
    val trail by container.move.trail.collectAsStateWithLifecycle()
    val demoRunning by container.moveDemo.running.collectAsStateWithLifecycle()
    val diag by container.moveSensors.diag.collectAsStateWithLifecycle()
    val places by container.moveDao.placesFlow().collectAsStateWithLifecycle(emptyList())
    val routes by container.moveDao.routesFlow().collectAsStateWithLifecycle(emptyList())
    val settings by container.settings.collectAsStateWithLifecycle()
    val s = settings ?: return

    var message by remember { mutableStateOf("") }
    var placeName by remember { mutableStateOf("") }
    var confirmWipe by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<MoveRouteEntity?>(null) }
    var newName by remember { mutableStateOf("") }
    var mapOpen by remember { mutableStateOf<Long?>(null) }

    // GPS, Wi-Fi and the compass run while this screen is open, so that "save this spot" has a position and the heading is live
    DisposableEffect(Unit) {
        container.moveSensors.retain("screen")
        onDispose { container.moveSensors.release("screen") }
    }

    // while a walk is running (and this screen is open) the screen stays on, so the arrow and the map can be glanced at
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    val running = st.mode != MoveMode.IDLE
    DisposableEffect(running) {
        if (running) activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { container.moveSensors.refreshPermissions() }
    fun askPermissions() {
        val list = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        perms.launch(list.toTypedArray())
    }
    val ready = !diag.needsLocation && !diag.needsActivity
    fun whenReady(block: () -> Unit) { if (ready) block() else askPermissions() }
    fun now() = SystemClock.elapsedRealtime()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("WAY HOME", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                TriStripe(width = 56.dp)
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!ready) item { PermissionCard(diag) { askPermissions() } }
            if (demoRunning) item { Pill("Demo walk: simulated, nobody is moving", Warn) }
            item { StatusCard(st) }
            item {
                Column(Modifier.fillMaxWidth().glass(18.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { whenReady { scope.launch { message = container.move.goHome(now()) } } },
                        modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(Icons.Default.Home, null); Spacer(Modifier.width(10.dp))
                        Text("Take me home", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    when (st.mode) {
                        MoveMode.IDLE -> OutlinedButton(
                            onClick = { whenReady { message = container.move.startTrip(now()) } },
                            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)
                        ) { Text("I am going out: remember my walk", fontSize = 16.sp) }
                        MoveMode.RECORDING -> OutlinedButton(
                            onClick = { message = container.move.stop(now()) },
                            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)
                        ) { Text("Stop and save this walk", fontSize = 16.sp) }
                        MoveMode.GUIDING -> OutlinedButton(
                            onClick = { message = container.move.stop(now()) },
                            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)
                        ) { Text("Stop guiding", fontSize = 16.sp) }
                    }
                    if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ---- the walk drawn as a path with arrows: live while walking, and the last walk afterwards ------------------
            item {
                if (trail.isEmpty && st.mode == MoveMode.IDLE) {
                    // nothing to draw yet: a single line, so the buttons above stay on the first screen
                    Row(Modifier.fillMaxWidth().glass(16.dp).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Place, null, tint = RaceBlue)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Your path is drawn here while you walk.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    PathCard(
                        title = when {
                            st.mode == MoveMode.RECORDING -> "Your walk so far"
                            st.mode == MoveMode.GUIDING -> "Your path"
                            else -> "Your last walk"
                        },
                        trail = trail
                    )
                }
            }

            // ---- places -----------------------------------------------------------------------------------------
            item {
                Column(Modifier.fillMaxWidth().glass(18.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Places", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Button(
                        onClick = { whenReady { scope.launch { message = container.move.saveHere("Home", true, now()) } } },
                        modifier = Modifier.fillMaxWidth()
                    ) { Icon(Icons.Default.Place, null); Spacer(Modifier.width(8.dp)); Text(if (places.any { it.kind == "home" }) "Save this spot as my new Home" else "Save this spot as my Home") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = placeName, onValueChange = { placeName = it.take(30) }, singleLine = true,
                            placeholder = { Text("Save another place") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = placeName.isNotBlank(),
                            onClick = { val n = placeName; placeName = ""; whenReady { scope.launch { message = container.move.saveHere(n, false, now()) } } }
                        ) { Text("Save") }
                    }
                    if (places.isNotEmpty()) Divider()
                    places.forEach { p -> PlaceRow(p, onGo = { whenReady { scope.launch { message = container.move.guidePlace(p, now()) } } }, onDelete = { scope.launch { container.moveDao.deletePlace(p.id) } }) }
                }
            }

            // ---- saved walks ------------------------------------------------------------------------------------
            if (routes.isNotEmpty()) item {
                Row(Modifier.padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.Bottom) {
                    Text("Saved walks", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Text("tap a name to rename", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(routes, key = { it.id }) { r ->
                RouteRow(
                    r,
                    mapOpen = mapOpen == r.id,
                    onMap = { mapOpen = if (mapOpen == r.id) null else r.id },
                    onBack = { whenReady { scope.launch { message = container.move.guideRoute(r.id, reverse = true, nowMs = now()) } } },
                    onAgain = { whenReady { scope.launch { message = container.move.guideRoute(r.id, reverse = false, nowMs = now()) } } },
                    onDelete = { scope.launch { container.moveDao.deleteRoute(r.id) } },
                    onRename = { renaming = r; newName = r.name }
                )
            }

            // ---- how it tells you -------------------------------------------------------------------------------
            item {
                Column(Modifier.fillMaxWidth().glass(18.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text("How it tells you", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                    SwitchRow("Short voice cues", "\"Turn left\", \"go straight\"", s.moveVoice) { v -> scope.launch { container.settingsRepo.update { it.copy(moveVoice = v) } } }
                    SwitchRow("Buzz for each cue", "Left: two short. Right: one long. Turn around: three long.", s.moveBuzz) { v -> scope.launch { container.settingsRepo.update { it.copy(moveBuzz = v) } } }
                    SwitchRow("Steady compass buzz", "Steadier the closer you point the right way", s.moveCompassBuzz) { v -> scope.launch { container.settingsRepo.update { it.copy(moveCompassBuzz = v) } } }
                    com.smriti.app.ui.components.DropdownRow(
                        "Length of your step", if (s.moveStrideCm == 0) "Automatic" else "${s.moveStrideCm} cm",
                        listOf("Automatic (learned from GPS)" to 0) + listOf(55, 60, 65, 70, 75, 80, 85).map { "$it cm" to it }
                    ) { v -> scope.launch { container.settingsRepo.update { it.copy(moveStrideCm = v) } } }
                }
            }

            // ---- a demo walk, for showing it ----------------------------------------------------------------------
            item {
                Column(Modifier.fillMaxWidth().glass(18.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Try it without walking", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "A simulated walk to show how it works. The phone does not move; nothing is saved.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (demoRunning) OutlinedButton(onClick = { container.moveDemo.stop() }, modifier = Modifier.fillMaxWidth()) { Text("Stop the demo") }
                    else Button(
                        onClick = { whenReady { message = container.moveDemo.start() } },
                        enabled = st.mode == MoveMode.IDLE, modifier = Modifier.fillMaxWidth()
                    ) { Text("Start the demo walk") }
                }
            }

            // ---- sensors and honesty ----------------------------------------------------------------------------
            item { SensorsCard(diag, st) }
            item {
                Column(Modifier.fillMaxWidth().glass(18.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("What to expect", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Outdoors, GPS keeps you within about 5 to 10 m. Indoors, steps and compass drift a few metres: good for a room or a floor. " +
                            "Hold the phone flat in your hand. There are no maps, so a straight line ignores walls and roads.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("You can say: \"take me home\", \"I am going out\", \"stop guiding\". Everything stays on this phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { confirmWipe = true }, enabled = places.isNotEmpty() || routes.isNotEmpty()) { Text("Delete all places and walks") }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    renaming?.let { r ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Name this walk") },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it.take(40) }, singleLine = true, label = { Text("e.g. living room to kitchen") }) },
            confirmButton = { Button(enabled = newName.isNotBlank(), onClick = { val n = newName.trim(); renaming = null; scope.launch { container.moveDao.renameRoute(r.id, n) } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Delete all places and walks?") },
            text = { Text("Your saved Home, other places and recorded walks will be erased from this phone. This cannot be undone.") },
            confirmButton = { Button(onClick = { confirmWipe = false; scope.launch { container.moveDao.deleteAllPlaces(); container.moveDao.deleteAllRoutes(); message = "Deleted." } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Keep") } }
        )
    }
}

@Composable
private fun PermissionCard(diag: SensorDiag, onAllow: () -> Unit) {
    Column(Modifier.fillMaxWidth().glass(18.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Two permissions are needed", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        if (diag.needsLocation) Text("Location (precise), to know where you are. It is never sent anywhere.", style = MaterialTheme.typography.bodySmall)
        if (diag.needsActivity) Text("Physical activity, to count your steps.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = onAllow) { Text("Allow") }
    }
}

@Composable
private fun StatusCard(st: MoveState) {
    val scheme = MaterialTheme.colorScheme
    val turn = st.cue != null && st.mode == MoveMode.GUIDING
    // the arrow points where to go, relative to where the phone points: up is straight ahead
    val target = if (!turn) 0f else if (kotlin.math.abs(st.errDeg) > 150) 180f else st.errDeg.toFloat()      // a turn around always points back, never spins the long way
    val angle by animateFloatAsState(target, label = "arrow")
    Column(Modifier.fillMaxWidth().glass(22.dp).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            st.arrived && st.mode == MoveMode.IDLE -> {
                Icon3D(Icons.Default.Check, size = 84.dp, tint = Good)
                Text("You have arrived", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Good, textAlign = TextAlign.Center)
                if (st.title.isNotBlank()) Text(st.title, color = scheme.onSurfaceVariant)
            }
            st.mode == MoveMode.GUIDING -> {
                Box(Modifier.size(110.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ArrowUpward, null, tint = if (st.cue == CueType.STRAIGHT) Good else if (st.cue == CueType.OFF_ROUTE || st.cue == CueType.U_TURN) Warn else RaceBlue, modifier = Modifier.size(100.dp).rotate(angle))
                }
                Text(st.phrase.ifBlank { "Hold the phone in your hand and start walking" }, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (st.remainingM > 0) Pill("${st.remainingM} m to go", RaceBlue)
                    if (st.title.isNotBlank()) Pill(st.title, RaceBlue)
                }
            }
            st.mode == MoveMode.RECORDING -> {
                Icon3D(Icons.Default.Place, size = 72.dp)
                Text("Remembering your walk", fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text("${st.steps} steps, ${st.walkedM} m. When you want to go back, tap Take me home.", color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            else -> {
                Icon3D(Icons.Default.Place, size = 72.dp)
                Text("Ready", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(if (st.gpsM != null) "GPS is working (about ${st.gpsM} m)" else "Waiting for the GPS signal", color = scheme.onSurfaceVariant)
            }
        }
        if (st.note.isNotBlank() && st.mode != MoveMode.IDLE) Text(st.note, color = Warn, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PlaceRow(p: MovePlaceEntity, onGo: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (p.kind == "home") Icons.Default.Home else Icons.Default.Place, null, tint = if (p.kind == "home") Good else RaceBlue)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, fontWeight = FontWeight.SemiBold)
            Text("${p.radiusM} m, ${com.smriti.app.move.WifiPrint.decode(p.wifi).size} Wi-Fi", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onGo) { Text("Take me") }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun RouteRow(r: MoveRouteEntity, mapOpen: Boolean, onMap: () -> Unit, onBack: () -> Unit, onAgain: () -> Unit, onDelete: () -> Unit, onRename: () -> Unit) {
    Column(Modifier.fillMaxWidth().glass(16.dp).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickableH(onRename)) {
                Text(r.name, fontWeight = FontWeight.SemiBold)
                Text("${r.lengthM.toInt()} m${if (r.hasGps) ", with GPS" else ", steps only"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onClick = onMap) { Text(if (mapOpen) "Hide path" else "Show path") }
            TextButton(onClick = onBack) { Text("Back along it") }
            TextButton(onClick = onAgain) { Text("Again") }
        }
        if (mapOpen) {
            val pts = com.smriti.app.move.RouteText.decode(r.points)
            val shown = com.smriti.app.move.Trail(out = pts, start = pts.firstOrNull(), dest = pts.lastOrNull(), destLabel = "End")
            PathMap(shown, Modifier.fillMaxWidth().height(240.dp).padding(top = 4.dp))
            Text("Two fingers zoom; double-tap fits.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun SensorsCard(d: SensorDiag, st: MoveState) {
    Column(Modifier.fillMaxWidth().glass(18.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("What the phone is using", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (d.steps) "Step detector" else "Steps: not allowed", if (d.steps) Good else Bad)
            Pill(if (d.gyroscope) "Gyroscope" else "Gyroscope: none", if (d.gyroscope) Good else Warn)
            Pill(if (d.compass) "Compass" else "Compass: none", if (d.compass) Good else Bad)
            Pill(
                when { d.needsLocation -> "GPS: not allowed"; !d.locationOn -> "GPS: location is off"; st.gpsM != null -> "GPS about ${st.gpsM} m"; else -> "GPS: waiting" },
                if (st.gpsM != null) Good else Warn
            )
            Pill("Wi-Fi: ${d.wifiNetworks} networks", if (d.wifiNetworks > 0) Good else Warn)
            Pill(if (st.headingDeg != null) "Heading ${st.headingDeg}°" else "Heading: not yet", if (st.headingDeg != null) Good else Warn)
        }
        if (!d.locationOn) Text("Turn Location on in the phone's quick settings; GPS and Wi-Fi places need it.", style = MaterialTheme.typography.bodySmall, color = Warn)
    }
}
