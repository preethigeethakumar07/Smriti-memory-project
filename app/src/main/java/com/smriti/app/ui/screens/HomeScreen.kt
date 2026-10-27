@file:OptIn(ExperimentalMaterial3Api::class)

package com.smriti.app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smriti.app.AppContainer
import com.smriti.app.ui.components.clickableH
import com.smriti.app.memory.ChatEntity
import com.smriti.app.source.LinkState
import com.smriti.app.ui.components.Icon3D
import com.smriti.app.ui.components.Pill
import com.smriti.app.ui.components.TriStripe
import com.smriti.app.ui.components.glass
import com.smriti.app.ui.theme.Good
import com.smriti.app.ui.theme.RaceBlue
import com.smriti.app.ui.theme.RaceDeep
import com.smriti.app.ui.theme.Warn
import com.smriti.app.util.TtsManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home is the conversation. Pick Phone or Glasses to go Live (camera + voice, no text on screen), or use the
 * mic for voice only. Whatever was said is here afterwards as a normal chat, ready to be continued by typing,
 * voice or camera.
 */
@Composable
fun HomeScreen(container: AppContainer, onOpenGlasses: () -> Unit, onStartLive: (withCamera: Boolean) -> Unit, onOpenSearch: () -> Unit = {}, onOpenMove: () -> Unit = {}, onOpenCctv: () -> Unit = {}) {
    val settings by container.settings.collectAsStateWithLifecycle()
    val engine by container.engine.state.collectAsStateWithLifecycle()
    val glasses by container.esp32.status.collectAsStateWithLifecycle()
    val camGranted by container.permissions.camera.collectAsStateWithLifecycle()
    val messages by container.chat.messages.collectAsStateWithLifecycle(emptyList())
    val s = settings ?: return

    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var zoneDialog by remember { mutableStateOf(false) }
    var zoneName by remember { mutableStateOf("") }
    var pendingLive by remember { mutableStateOf<String?>(null) }
    val bytes by produceState(0L, engine.memories) { value = container.engine.textBytes() }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, typing) {
        val n = messages.size + if (typing) 1 else 0
        if (n > 0) listState.animateScrollToItem(n - 1)
    }

    fun send(text: String) {
        val q = text.trim()
        if (q.isEmpty() || typing) return
        query = ""
        typing = true
        scope.launch {
            container.chat.add("user", com.smriti.app.privacy.TextScrubber.scrub(q), s.source)
            val a = runCatching { container.engine.ask(q) }.getOrElse { "Something went wrong: ${it.message}" }
            container.chat.add("smriti", a, s.source, imagePath = container.engine.lastAnswerImage)
            typing = false
            container.haptics.done()
            if (s.voiceReply) TtsManager.speak(if (s.answerStyle == "short") a.lineSequence().first() else a.replace("\n", ". "), container.engine.speakTag())
        }
    }

    fun go(source: String, withCamera: Boolean) {
        scope.launch {
            if (s.source != source) container.settingsRepo.update { it.copy(source = source) }
            onStartLive(withCamera)
        }
    }

    val camPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        container.permissions.refresh()
        val p = pendingLive
        pendingLive = null
        if (granted && p != null) go("phone", true)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // ---- header ----------------------------------------------------------------------------------
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (s.name.isBlank()) "SMRITI" else "HI, ${s.name.uppercase()}",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                TriStripe(width = 56.dp)
            }
            Icon3D(Icons.Default.Search, size = 40.dp, glow = false, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickableH(onOpenSearch))
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Teach this place") }, leadingIcon = { Icon(Icons.Default.Place, null) }, onClick = { menuOpen = false; zoneDialog = true })
                    DropdownMenuItem(text = { Text("Forget last 5 minutes") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = {
                        menuOpen = false
                        scope.launch {
                            val since = System.currentTimeMillis() - 5 * 60_000L
                            val r = container.engine.forgetLast(5)
                            container.chat.clearSince(since)
                            container.chat.add("smriti", r, s.source)
                        }
                    })
                    DropdownMenuItem(text = { Text("Clear chat") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menuOpen = false; scope.launch { container.chat.clear() } })
                }
            }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, null, tint = Good, modifier = Modifier.size(13.dp))
            Text(
                "Blurred pictures only  |  ${engine.memories} notes  |  Cloud: none",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall
            )
        }
        OverlayBanner(container)
        if (engine.trippedStages.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (engine.trippedStages.isNotEmpty()) Pill("Paused: ${engine.trippedStages.joinToString()}", com.smriti.app.ui.theme.Bad)
            }
        }

        val cctvStatus by container.cctv.status.collectAsStateWithLifecycle()

        // ---- CCTV Surveillance AI Hub Banner ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF141F2E))
                .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .clickableH { onOpenCctv() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF00E5FF).copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Icon(com.smriti.app.ui.components.SmritiIcons.CctvCamera, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("CCTV AI INTELLIGENCE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF00E676).copy(alpha = 0.2f)).padding(horizontal = 5.dp, vertical = 1.dp)) {
                        Text("LIVE", color = Color(0xFF00E676), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text("${cctvStatus.activeCamera} • ${cctvStatus.personCount} Humans • ${cctvStatus.vehicleCount} Vehicles", color = Color(0xFF90A4AE), fontSize = 11.sp)
            }
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(18.dp))
        }

        // ---- one line to look for something (ask first, then film) and tiles: phone, glasses, cctv, walk guide ----
        LookForRow(onLook = { what ->
            container.pendingTarget.value = what.trim()
            if (!camGranted) { pendingLive = "phone"; camPermission.launch(Manifest.permission.CAMERA) } else go("phone", true)
        })
        val move by container.move.state.collectAsStateWithLifecycle()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HomeTile(
                Modifier.weight(1f), Icons.Default.PhoneAndroid, "Phone",
                status = if (!camGranted) "Allow" else "Ready", dot = if (camGranted) Good else Warn, selected = s.source == "phone"
            ) {
                if (!camGranted) { pendingLive = "phone"; camPermission.launch(Manifest.permission.CAMERA) } else go("phone", true)
            }
            val gLive = glasses.state == LinkState.LIVE
            HomeTile(
                Modifier.weight(1f), com.smriti.app.ui.components.SmritiIcons.GlassesAi, "Glasses",
                status = when (glasses.state) { LinkState.LIVE -> "Connected"; LinkState.CONNECTING -> "Connecting"; else -> "Set up" },
                dot = if (gLive) Good else Warn, selected = s.source == "glasses"
            ) { if (gLive) go("glasses", true) else onOpenGlasses() }
            HomeTile(
                Modifier.weight(1f), com.smriti.app.ui.components.SmritiIcons.CctvCamera, "CCTV AI",
                status = "${cctvStatus.personCount}P • ${cctvStatus.vehicleCount}V",
                dot = Good, selected = false, lit = true, onClick = onOpenCctv
            )
            val walking = move.mode != com.smriti.app.move.MoveMode.IDLE
            HomeTile(
                Modifier.weight(1f), Icons.Default.Place, "Way Home",
                status = when (move.mode) { com.smriti.app.move.MoveMode.GUIDING -> "Guiding"; com.smriti.app.move.MoveMode.RECORDING -> "Recording"; else -> "Ready" },
                dot = if (walking) Good else Color(0xFF5B6B86), selected = walking, lit = walking, onClick = onOpenMove
            )
        }

        // ---- conversation ------------------------------------------------------------------------------
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty() && !typing) {
                EmptyChat { send(it) }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.id }) { m -> Bubble(m) { ref -> container.pendingOpen.value = ref } }
                    if (typing) item { TypingBubble() }
                }
            }
        }

        // ---- composer ----------------------------------------------------------------------------------
        Row(
            Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Ask or continue the chat", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(query) }),
                shape = RoundedCornerShape(22.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0x66131A25), unfocusedContainerColor = Color(0x66131A25),
                    focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color(0x33FFFFFF)
                ),
                modifier = Modifier.weight(1f)
            )
            if (query.isBlank()) {
                Icon3D(Icons.Default.Mic, size = 48.dp, modifier = Modifier.clip(RoundedCornerShape(15.dp)).clickableH { go(s.source, false) })
                Icon3D(Icons.Default.Videocam, size = 48.dp, modifier = Modifier.clip(RoundedCornerShape(15.dp)).clickableH {
                    if (s.source == "phone" && !camGranted) { pendingLive = "phone"; camPermission.launch(Manifest.permission.CAMERA) } else go(s.source, true)
                })
            } else {
                Icon3D(Icons.Default.Send, size = 48.dp, modifier = Modifier.clip(RoundedCornerShape(15.dp)).clickableH { send(query) })
            }
        }
    }

    if (zoneDialog) {
        AlertDialog(
            onDismissRequest = { zoneDialog = false },
            title = { Text("Name this place") },
            text = { OutlinedTextField(value = zoneName, onValueChange = { zoneName = it.take(24) }, singleLine = true, label = { Text("e.g. desk, kitchen, bedroom") }) },
            confirmButton = {
                Button(enabled = zoneName.isNotBlank(), onClick = {
                    val n = zoneName; zoneName = ""; zoneDialog = false
                    scope.launch { container.chat.add("smriti", container.engine.teachZone(n), s.source) }
                }) { Text("Teach") }
            },
            dismissButton = { TextButton(onClick = { zoneDialog = false }) { Text("Cancel") } }
        )
    }
}

/** A small tile: an icon, a name and one word of status. [lit] says it is the one that is on, [selected] that it is the chosen one. */
@Composable
private fun HomeTile(
    modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, status: String, dot: Color,
    selected: Boolean, lit: Boolean = selected, onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .glass(16.dp)
            .background(if (selected) Brush.verticalGradient(listOf(RaceBlue.copy(alpha = 0.22f), Color.Transparent)) else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)))
            .then(if (selected) Modifier.border(1.5.dp, RaceBlue.copy(alpha = 0.8f), shape) else Modifier)
            .clickableH(onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon3D(icon, size = 30.dp, tint = if (lit || dot == Good) RaceBlue else Color(0xFF5B6B86), glow = false)
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(dot))
            Spacer(Modifier.width(4.dp))
            Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

/** Ask first, then film: type what to find, tap Look, pan the camera around, tap stop, and Smriti tells you where it saw it. One line. */
@Composable
private fun LookForRow(onLook: (String) -> Unit) {
    var what by remember { mutableStateOf("") }
    fun go() { if (what.isNotBlank()) { onLook(what); what = "" } }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.OutlinedTextField(
            value = what, onValueChange = { what = it }, singleLine = true,
            placeholder = { Text("Look for something...", maxLines = 1) },
            leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp)) },
            shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { go() })
        )
        androidx.compose.material3.Button(onClick = { go() }, enabled = what.isNotBlank(), shape = RoundedCornerShape(14.dp)) { Text("Look") }
    }
}

/**
 * The floating Smriti eye needs the "display over other apps" permission, which Android only lets the user grant in Settings.
 * Until it is granted this card says so and opens the right screen; it disappears once the eye can show.
 */
@Composable
private fun OverlayBanner(container: AppContainer) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings by container.settings.collectAsStateWithLifecycle()
    var allowed by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) allowed = android.provider.Settings.canDrawOverlays(context)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    if (settings?.floatingButton != true || allowed) return
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 2.dp).fillMaxWidth().glass(16.dp).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon3D(Icons.Default.Visibility, size = 26.dp, glow = false)
        Spacer(Modifier.width(10.dp))
        Text("Show Smriti's eye over other apps", style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
        androidx.compose.material3.TextButton(onClick = {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }) { Text("Allow") }
    }
}

@Composable
private fun Bubble(m: ChatEntity, onOpen: (com.smriti.app.ai.MomentRef) -> Unit = {}) {
    if (m.role == "event") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                Modifier.glass(12.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Mic, null, tint = RaceBlue, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(m.text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    val mine = m.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        if (!mine) {
            Icon3D(Icons.Default.Visibility, size = 28.dp, glow = false)
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = 300.dp)) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp))
                    .background(
                        if (mine) Brush.linearGradient(listOf(RaceBlue.copy(alpha = 0.95f), RaceDeep))
                        else Brush.linearGradient(listOf(Color(0xFF1A2333), Color(0xFF131A25)))
                    )
                    .border(
                        1.dp,
                        if (mine) Color(0x33FFFFFF) else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column {
                    Text(m.text, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    // the remembered moment itself (privacy-blurred thumbnail kept in the app's private folder)
                    val pic by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, m.imagePath) {
                        value = m.imagePath?.let { p -> withContext(Dispatchers.IO) { runCatching { com.smriti.app.security.SecureFiles.decodeBitmap(p)?.asImageBitmap() }.getOrNull() } }
                    }
                    // where in a recording this answer comes from: tap to open it at that moment
                    com.smriti.app.ai.MomentRef.decode(m.refs).forEach { ref ->
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x334AA8FF)).border(1.dp, RaceBlue.copy(alpha = 0.7f), RoundedCornerShape(12.dp)).clickableH { onOpen(ref) }.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PlayArrow, null, tint = RaceBlue, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("${ref.range}  |  ${ref.label}", color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    }
                    pic?.let {
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.foundation.Image(
                            it, contentDescription = "Remembered moment", contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(12.dp))
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp)) {
                if (m.live) {
                    Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(3.dp))
                }
                Text(
                    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(m.timestamp)),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TypingBubble() {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "typing")
    Row(Modifier.padding(start = 36.dp)) {
        Row(Modifier.glass(18.dp).padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(3) { i ->
                val a by t.animateFloat(
                    0.25f, 1f,
                    androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(600, delayMillis = i * 150), androidx.compose.animation.core.RepeatMode.Reverse),
                    label = "d$i"
                )
                Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(RaceBlue.copy(alpha = a)))
            }
        }
    }
}

@Composable
private fun EmptyChat(onPick: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon3D(Icons.Default.Visibility, size = 56.dp)
        Spacer(Modifier.height(10.dp))
        Text("Go Live, or just ask", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        listOf("What do you see?", "Where are my keys?", "What happened in the last 10 minutes?").forEach { q ->
            Box(
                Modifier.padding(vertical = 4.dp).glass(14.dp).clickableH { onPick(q) }.padding(horizontal = 14.dp, vertical = 9.dp)
            ) { Text(q, color = RaceBlue, style = MaterialTheme.typography.labelLarge) }
        }
    }
}
