package com.smriti.app.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smriti.app.AppContainer
import com.smriti.app.ai.SessionRules
import com.smriti.app.ai.VisualMemory
import com.smriti.app.embed.GemmaEmbedder
import com.smriti.app.memory.MemoryEntity
import com.smriti.app.memory.VisualEntity
import com.smriti.app.ui.components.Pill
import com.smriti.app.ui.components.SmritiCard
import com.smriti.app.ui.theme.Good
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
private val dateFmt = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())

private fun whenText(ts: Long): String {
    val sameDay = SimpleDateFormat("yyyyMMdd", Locale.US).let { it.format(Date(ts)) == it.format(Date()) }
    return (if (sameDay) timeFmt else dateFmt).format(Date(ts))
}

/**
 * Everything Smriti remembers, on one scrolling page: what is stored (honestly), search by meaning, then four views:
 * Moments (blurred pictures), Notes (text), Scenes (what happened when) and Home map (where things live).
 */
@Composable
fun MemoryScreen(container: AppContainer) {
    val engine = container.engine
    val rows by engine.repo.recentFlow(400).collectAsStateWithLifecycle(emptyList())
    val moments by engine.visual.moments(300).collectAsStateWithLifecycle(emptyList())
    val bytes by produceState(0L, rows.size) { value = engine.textBytes() }
    val momentTotal by produceState(0, moments.size) { value = engine.visual.count() }       // the list above shows only the newest 300
    val thumbBytes by produceState(0L, moments.size) { value = withContext(Dispatchers.IO) { engine.visual.thumbBytes() } }
    var filter by remember { mutableStateOf("") }
    var confirmAll by remember { mutableStateOf(false) }
    var confirmRecent by remember { mutableStateOf<Int?>(null) }
    var info by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<VisualEntity?>(null) }
    val camSessions by engine.cameraSessions.collectAsStateWithLifecycle(emptyList())
    val recordingId by engine.sessions.current.collectAsStateWithLifecycle()
    var openSession by remember { mutableStateOf<Long?>(null) }
    var startAt by remember { mutableStateOf<Long?>(null) }
    val openRef by container.pendingOpen.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(openRef) {
        val r = openRef ?: return@LaunchedEffect
        openSession = r.sessionId; startAt = r.startOffsetMs
        container.pendingOpen.value = null
    }
    var view by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(openSession) { if (openSession != null) view = 0 }

    val shownNotes = remember(rows, filter) {
        if (filter.isBlank()) rows
        else rows.filter { (it.caption + it.objects + it.zone + it.ocrText).contains(filter.trim(), ignoreCase = true) }
    }
    val shownMoments = remember(moments, filter) {
        if (filter.isBlank()) moments
        else moments.filter { (it.caption + it.place).contains(filter.trim(), ignoreCase = true) }
    }

    // search by meaning: the question is turned into a vector and the nearest remembered pictures come back
    val byMeaning by produceState(emptyList<VisualMemory.Hit>(), filter, moments.size) {
        value = emptyList()
        if (filter.trim().length >= 3) {
            delay(350)
            value = engine.visual.search(filter.trim(), 8)
        }
    }
    val sighting by produceState<com.smriti.app.memory.MemoryRepository.Sighting?>(null, filter, rows.size) {
        value = if (filter.trim().length >= 2) engine.repo.lastSeen(filter) else null
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Memory", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }

        item { StorageCard(container, moments.size, rows.size, momentTotal) }

        item {
            OutlinedTextField(
                value = filter, onValueChange = { filter = it }, singleLine = true,
                placeholder = { Text("Ask your memory: where are my keys?") },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary) },
                trailingIcon = { if (filter.isNotEmpty()) IconButton(onClick = { filter = "" }) { Icon(Icons.Default.Close, "Clear search") } },
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
            )
        }

        sighting?.let { s ->
            item {
                SmritiCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        com.smriti.app.ui.components.Icon3D(Icons.Default.Search, size = 38.dp, glow = false)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(if (s.viaText) "Read the word on something" else if (s.bySynonym) "Closest match: ${s.matched}" else "Last seen", fontWeight = FontWeight.Bold)
                            Text("${dateFmt.format(Date(s.at))}${if (s.zone != "somewhere") "  |  ${s.zone}" else ""}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            Text(s.context.take(110), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (byMeaning.isNotEmpty()) {
            item {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("CLOSEST MOMENTS BY MEANING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                        items(byMeaning, key = { it.entity.id }) { h -> Thumb(h.entity, container, Modifier.width(150.dp), score = h.score) { open = h.entity } }
                    }
                }
            }
        }

        if (filter.isBlank()) {
            item {
                val seen by produceState<List<com.smriti.app.memory.LedgerEntity>>(emptyList(), rows.size) {
                    value = engine.repo.seenItems(30).filter { it.item !in com.smriti.app.scene.SceneBuilder.VAGUE }.take(12)
                }
                if (seen.isNotEmpty()) {
                    Column {
                        Text("SEEN RECENTLY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(seen, key = { it.item }) { AssistChip(onClick = { filter = it.item }, label = { Text(it.item) }) }
                        }
                    }
                }
            }
        }

        item { com.smriti.app.ui.components.ChamferTabs(listOf("Sessions", "Moments", "Notes", "Scenes", "Home map"), view, { view = it }) }
        item {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { confirmRecent = 5 }, label = { Text("Forget 5 min") })
                AssistChip(onClick = { confirmRecent = 60 }, label = { Text("Forget 1 hour") })
                AssistChip(onClick = { confirmAll = true }, label = { Text("Forget all") })
            }
        }
        if (info.isNotBlank()) item { Text(info, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }

        when (view) {
            0 -> sessionsView(container, camSessions, recordingId, openSession, startAt, { openSession = it; startAt = null }) { open = it }
            1 -> momentsView(container, shownMoments, filter, moments.isEmpty()) { open = it }
            2 -> notesView(shownNotes, filter, rows.isEmpty()) { id -> scope.launch { engine.repo.delete(id) } }
            3 -> scenesView(container, rows.size)
            else -> homeMapView(container, rows.size)
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    open?.let { m ->
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(whenText(m.timestamp) + (m.place.takeIf { it != "somewhere" }?.let { "  |  $it" } ?: "")) },
            text = {
                Column {
                    Thumb(m, container, Modifier.fillMaxWidth(), tall = true) {}
                    Text(m.caption.ifBlank { "No description" }, modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium)
                    
                }
            },
            confirmButton = { TextButton(onClick = { open = null }) { Text("Close") } },
            dismissButton = { TextButton(onClick = { val id = m.id; open = null; scope.launch { engine.visual.delete(id) } }) { Text("Delete this moment") } }
        )
    }

    confirmRecent?.let { minutes ->
        AlertDialog(
            onDismissRequest = { confirmRecent = null },
            title = { Text(if (minutes >= 60) "Forget the last hour?" else "Forget the last $minutes minutes?") },
            text = { Text("The notes and pictures from that time are deleted from this phone. This cannot be undone.") },
            confirmButton = { Button(onClick = { confirmRecent = null; scope.launch { info = engine.forgetLast(minutes) } }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { confirmRecent = null }) { Text("Cancel") } }
        )
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Forget everything?") },
            text = { Text("This permanently deletes every note, picture and meaning vector Smriti has made. It cannot be undone.") },
            confirmButton = { Button(onClick = { confirmAll = false; scope.launch { info = engine.forgetAll(); container.chat.clear() } }) { Text("Delete all") } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } }
        )
    }
}

/** What is kept and what is reading it, in a few words. */
@Composable
private fun StatusCard(notes: Int, moments: Int, thumbBytes: Long, textBytes: Long, embed: GemmaEmbedder.State, brain: com.smriti.app.ai.Brain.State) {
    SmritiCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, null, tint = Good, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Stays on this phone", fontWeight = FontWeight.SemiBold)
                Text(
                    "$notes notes, $moments moments (${"%.1f".format((thumbBytes + textBytes) / 1_000_000.0)} MB)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (embed) {
                is GemmaEmbedder.State.Ready -> Pill("Search ${embed.backend}", Good)
                is GemmaEmbedder.State.Missing -> Pill("Search model missing", MaterialTheme.colorScheme.error)
                is GemmaEmbedder.State.Failed -> Pill("Search model failed", MaterialTheme.colorScheme.error)
                else -> Pill("Search loading", MaterialTheme.colorScheme.primary)
            }
            when (brain) {
                is com.smriti.app.ai.Brain.State.Ready -> Pill("Answers ${brain.backend}", Good)
                is com.smriti.app.ai.Brain.State.Missing -> Pill("Answer model missing", MaterialTheme.colorScheme.error)
                is com.smriti.app.ai.Brain.State.Failed -> Pill("Answer model failed", MaterialTheme.colorScheme.error)
                else -> Pill("Answers loading", MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** A camera session's cover picture, loaded off the main thread. */
@Composable
private fun CoverImage(name: String, container: AppContainer, modifier: Modifier = Modifier) {
    val file = remember(name) { container.engine.visual.fileFor(name) }
    val pic by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { com.smriti.app.security.SecureFiles.decodeBitmap(file)?.asImageBitmap() }.getOrNull() }
    }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF131A25))) {
        pic?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/**
 * Camera sessions, newest first: the time range, how long, how many windows were indexed and where. Tapping one opens its
 * windows with their position in the recording (00:03, 00:09...). Searching inside a session and playback come in the next steps.
 */
private fun LazyListScope.sessionsView(
    container: AppContainer, sessions: List<com.smriti.app.memory.CameraSessionEntity>, recordingId: Long?,
    openId: Long?, startAt: Long?, onOpen: (Long?) -> Unit, onOpenMoment: (VisualEntity) -> Unit
) {
    val chosen = sessions.firstOrNull { it.id == openId }
    if (chosen != null) {
        item { SessionDetail(container, chosen, recordingId == chosen.id, startAt, onBack = { onOpen(null) }, onOpenMoment = onOpenMoment) }
        return
    }
    if (sessions.isEmpty()) {
        item {
            Text("No sessions yet. Open the camera and each session appears here.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        }
        return
    }
    items(sessions, key = { it.id }) { s ->
        val live = recordingId == s.id
        SmritiCard(Modifier.clickable { onOpen(s.id) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.cover.isNotBlank()) CoverImage(s.cover, container, Modifier.size(width = 96.dp, height = 72.dp))
                else Box(Modifier.size(width = 96.dp, height = 72.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF131A25)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (live) "Recording now" else SessionRules.label(s.startedAt, s.endedAt),
                        fontWeight = FontWeight.Bold, color = if (live) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                    val len = if (live) SessionRules.duration(System.currentTimeMillis() - s.startedAt) else SessionRules.duration(s.durationMs)
                    Text(
                        listOfNotNull(len, if (!live) "${s.segments} windows" else null, s.place.takeIf { it.isNotBlank() }, if (s.source == "glasses") "glasses" else null).joinToString("  |  "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (s.hasSound) Pill("with sound", Good)
                }
            }
        }
    }
}

/**
 * One session: a filmstrip player (the face-blurred pictures played in order, with a scrubber), a search box that looks only
 * inside this recording, and the windows. A search answers in words and lists the matching time ranges; tapping a range jumps the player there.
 */
@Composable
private fun SessionDetail(container: AppContainer, s: com.smriti.app.memory.CameraSessionEntity, live: Boolean, startAt: Long?, onBack: () -> Unit, onOpenMoment: (VisualEntity) -> Unit) {
    val scope = rememberCoroutineScope()
    val windows by container.engine.sessionMoments(s.id).collectAsStateWithLifecycle(emptyList())
    var confirm by remember { mutableStateOf(false) }
    val total = ((if (live) System.currentTimeMillis() else s.endedAt) - s.startedAt).coerceAtLeast(1000)
    var position by remember { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    // the picture opened full screen (index into the windows), or null
    var preview by remember { mutableStateOf<Int?>(null) }

    // search inside this session
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<List<com.smriti.app.ai.MomentSearch.Found>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    fun runSearch() {
        val q = query.trim()
        if (q.length < 2 || searching) return
        searching = true; searched = false; answer = null
        scope.launch {
            val hits = container.engine.momentSearch.find(q, sessionId = s.id)
            found = hits
            searched = true
            if (hits.isNotEmpty()) {
                answer = container.engine.momentSearch.answer(q, hits) { f -> "${SessionRules.clock(f.range.start - s.startedAt)} - ${SessionRules.clock(f.range.end - s.startedAt)} into the recording" }.text
                position = (hits.first().range.start - s.startedAt).coerceAtLeast(0); playing = true
            }
            searching = false
        }
    }

    // opened from a tapped answer: jump to that moment and play
    androidx.compose.runtime.LaunchedEffect(startAt) { if (startAt != null) { position = startAt.coerceIn(0, total); playing = true } }

    // play: advance in real time, stop at the end
    androidx.compose.runtime.LaunchedEffect(playing) {
        while (playing && position < total) { delay(100); position += 100 }
        if (position >= total) { playing = false }
    }
    // the window on screen is the latest one that has started by now
    val current = remember(windows, position) { windows.lastOrNull { it.timestamp - s.startedAt <= position } ?: windows.firstOrNull() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("< All sessions") }
            Spacer(Modifier.weight(1f))
            if (!live) IconButton(onClick = { confirm = true }) { Icon(Icons.Default.Delete, "Delete this session") }
        }
        Text(SessionRules.label(s.startedAt, if (live) System.currentTimeMillis() else s.endedAt), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(
            "${windows.size} windows" + (s.place.takeIf { it.isNotBlank() }?.let { "  |  $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // what this recording was made to find, and the answer Smriti gave when it ended
        if (s.target.isNotBlank()) {
            SmritiCard {
                Text("Looked for: ${s.target}", fontWeight = FontWeight.Bold)
                if (s.result.isNotBlank()) Text(s.result, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                else Text("Checking the recording...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---- player ----
        if (current != null) {
            Thumb(current, container, Modifier.fillMaxWidth(), tall = true) { playing = false; preview = windows.indexOf(current).takeIf { it >= 0 } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (position >= total) position = 0; playing = !playing }) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", tint = MaterialTheme.colorScheme.primary)
                }
                androidx.compose.material3.Slider(
                    value = position.toFloat().coerceIn(0f, total.toFloat()), onValueChange = { position = it.toLong(); playing = false },
                    valueRange = 0f..total.toFloat(), modifier = Modifier.weight(1f)
                )
                Text(SessionRules.clock(position) + " / " + SessionRules.clock(total), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
            }
        }

        // ---- search in this recording ----
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Ask about this recording") },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; found = emptyList(); answer = null; searched = false }) { Icon(Icons.Default.Close, "Clear") } },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { runSearch() }),
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
        )
        if (searching) Text("Searching this recording...", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        answer?.let { SmritiCard { Text(it, style = MaterialTheme.typography.bodyLarge) } }
        if (searched && found.isEmpty()) Text("Nothing in this recording matches that.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        found.forEach { f ->
            val from = f.range.start - s.startedAt; val to = f.range.end - s.startedAt
            SmritiCard(Modifier.clickable { position = from.coerceAtLeast(0); playing = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Thumb(f.best, container, Modifier.size(width = 96.dp, height = 96.dp)) { position = from.coerceAtLeast(0); playing = true }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("${SessionRules.clock(from)} - ${SessionRules.clock(to)}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("match ${(f.range.score * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Text("ALL WINDOWS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        windows.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { m ->
                    Column(Modifier.weight(1f)) {
                        Thumb(m, container, Modifier.fillMaxWidth()) { position = (m.timestamp - s.startedAt).coerceAtLeast(0); playing = false; preview = windows.indexOf(m).takeIf { it >= 0 } }
                        Text(
                            SessionRules.clock(m.timestamp - s.startedAt) + " - " + SessionRules.clock((if (m.endAt > 0) m.endAt else m.timestamp) - s.startedAt),
                            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    preview?.let { i ->
        if (i in windows.indices) PicturePreview(
            container, windows, i, s.startedAt,
            onMove = { preview = it; position = (windows[it].timestamp - s.startedAt).coerceAtLeast(0) },
            onDetails = { val m = windows[i]; preview = null; onOpenMoment(m) },
            onClose = { preview = null }
        ) else preview = null
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete this session?") },
            text = { Text("Its ${windows.size} pictures and search data are erased from this phone. This cannot be undone.") },
            confirmButton = { Button(onClick = { confirm = false; onBack(); scope.launch { container.engine.sessions.delete(s.id) } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }
}
/** A picture full screen: pinch to zoom, drag to move, double-tap to reset, arrows to step through the recording. The picture is the face-blurred copy. */
@Composable
private fun PicturePreview(container: AppContainer, list: List<VisualEntity>, index: Int, startedAt: Long, onMove: (Int) -> Unit, onDetails: () -> Unit, onClose: () -> Unit) {
    val m = list[index]
    val file = remember(m.thumb) { container.engine.visual.fileFor(m.thumb) }
    val pic by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { com.smriti.app.security.SecureFiles.decodeBitmap(file)?.asImageBitmap() }.getOrNull() }
    }
    var zoom by remember(index) { mutableStateOf(1f) }
    var pan by remember(index) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val p = pic
            if (p != null) {
                Image(
                    p, m.caption.ifBlank { "Remembered moment" }, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(index) {
                            detectTransformGestures { _, dragged, scaleBy, _ ->
                                zoom = (zoom * scaleBy).coerceIn(1f, 6f)
                                pan = if (zoom <= 1f) androidx.compose.ui.geometry.Offset.Zero else pan + dragged
                            }
                        }
                        .pointerInput(index) { detectTapGestures(onDoubleTap = { zoom = if (zoom > 1f) 1f else 2.5f; pan = androidx.compose.ui.geometry.Offset.Zero }) }
                        .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }
                )
            }
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))).statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(SessionRules.clock(m.timestamp - startedAt) + " into the recording", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("${index + 1} of ${list.size}  |  " + whenText(m.timestamp), color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onDetails) { Text("Details", color = Color.White) }
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).navigationBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onMove(index - 1) }, enabled = index > 0) { Icon(Icons.Default.ArrowBack, "Previous", tint = if (index > 0) Color.White else Color(0x55FFFFFF)) }
                Text(m.caption, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                IconButton(onClick = { onMove(index + 1) }, enabled = index < list.lastIndex) { Icon(Icons.Default.ArrowForward, "Next", tint = if (index < list.lastIndex) Color.White else Color(0x55FFFFFF)) }
            }
        }
    }
}

/** A remembered picture. Loaded off the main thread from the app's private folder. */
@Composable
private fun Thumb(m: VisualEntity, container: AppContainer, modifier: Modifier = Modifier, score: Float? = null, tall: Boolean = false, onClick: () -> Unit) {
    val file = remember(m.thumb) { container.engine.visual.fileFor(m.thumb) }
    val pic by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { com.smriti.app.security.SecureFiles.decodeBitmap(file)?.asImageBitmap() }.getOrNull() }
    }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF131A25)).clickable(onClick = onClick)) {
        val p = pic
        if (p != null) {
            Image(p, m.caption.ifBlank { "Remembered moment" }, contentScale = if (tall) ContentScale.Fit else ContentScale.Crop,
                modifier = if (tall) Modifier.fillMaxWidth().heightIn(max = 460.dp).aspectRatio((p.width.toFloat() / p.height.coerceAtLeast(1)).coerceIn(0.5f, 2f)) else Modifier.fillMaxWidth().aspectRatio(0.8f))
        } else Box(Modifier.fillMaxWidth().aspectRatio(0.8f))
        if (!tall) {
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).padding(8.dp)) {
                Text(whenText(m.timestamp), color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                val sub = listOfNotNull(m.place.takeIf { it != "somewhere" }, score?.let { "match ${(it * 100).toInt()}%" }).joinToString("  |  ")
                if (sub.isNotBlank()) Text(sub, color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun LazyListScope.momentsView(container: AppContainer, list: List<VisualEntity>, filter: String, noneAtAll: Boolean, onOpen: (VisualEntity) -> Unit) {
    if (list.isEmpty()) {
        item {
            Text(
                if (noneAtAll) "No moments yet. Look around with the camera."
                else "No moments match \"$filter\".",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)
            )
        }
        return
    }
    items(list.chunked(2), key = { it.first().id }) { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            pair.forEach { m -> Thumb(m, container, Modifier.weight(1f)) { onOpen(m) } }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

private fun LazyListScope.notesView(list: List<MemoryEntity>, filter: String, noneAtAll: Boolean, onDelete: (Long) -> Unit) {
    if (list.isEmpty()) {
        item {
            Text(
                if (noneAtAll) "No notes yet." else "No notes match \"$filter\".",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)
            )
        }
        return
    }
    items(list, key = { it.id }) { m -> MemoryRow(m) { onDelete(m.id) } }
}

@Composable
private fun MemoryRow(m: MemoryEntity, onDelete: () -> Unit) {
    SmritiCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(whenText(m.timestamp), fontWeight = FontWeight.SemiBold)
                    if (m.zone != "somewhere") Pill(m.zone, MaterialTheme.colorScheme.primary)
                    if (m.source == "glasses") Pill("glasses", Good)
                }
                Text(m.caption, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                if (m.objects.isNotBlank()) {
                    Text("Objects: ${m.objects.replace(",", ", ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete note", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Scenes from the last day: time span, importance bar, motion and interaction, and what was in them. */
private fun LazyListScope.scenesView(container: AppContainer, notes: Int) {
    item {
        val scenes by produceState(emptyList<com.smriti.app.scene.Scene>(), notes) {
            val now = System.currentTimeMillis()
            value = container.engine.repo.scenes(now - 24 * 3_600_000L, now).reversed()
        }
        if (scenes.isEmpty()) {
            Text("No scenes yet. Go Live and move around.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                scenes.forEach { s ->
                    SmritiCard {
                        Text(s.title, fontWeight = FontWeight.Bold)
                        Text("${timeFmt.format(Date(s.start))} to ${timeFmt.format(Date(s.end))}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                        com.smriti.app.ui.components.BarMeter("Importance", "%.2f".format(s.importance), s.importance)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("Motion ${com.smriti.app.scene.SceneBuilder.level(s.motion)}", MaterialTheme.colorScheme.primary)
                            Pill("Interaction ${com.smriti.app.scene.SceneBuilder.level(s.interaction)}", MaterialTheme.colorScheme.primary)
                        }
                        Text("Objects: ${s.objects.take(8).joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

/** A map of the home as Smriti remembers it: each place and the things last seen there. (A 2D memory map, not a 3D scan.) */
private fun LazyListScope.homeMapView(container: AppContainer, notes: Int) {
    item {
        val map by produceState(emptyMap<String, List<com.smriti.app.memory.LedgerEntity>>(), notes) { value = container.engine.repo.homeMap() }
        if (map.isEmpty()) {
            Text("No places yet. Teach one from the Home menu.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                map.entries.toList().forEach { (place, items) ->
                    SmritiCard {
                        Text(place.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Bold)
                        Text("${items.size} things seen here", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        items.take(8).forEach { com.smriti.app.ui.components.KeyValueRow(it.item, com.smriti.app.query.Answerer.ago(System.currentTimeMillis() - it.lastSeen)) }
                    }
                }
            }
        }
    }
}

/**
 * How much room the memory takes against its budget, a choice of budget, and a button that frees space now. Video is never stored:
 * only a small blurred picture and a vector per moment (about 19 KB), so this is about how long a history fits.
 */
@Composable
private fun StorageCard(container: AppContainer, momentCount: Int, notes: Int, moments: Int) {
    val settings by container.settings.collectAsStateWithLifecycle()
    val budgetMb = settings?.storageBudgetMb ?: 300
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<com.smriti.app.ai.StorageManager.Usage?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(momentCount, busy) { usage = container.storage.usage() }
    val used = usage?.total ?: 0L
    val budget = budgetMb * 1_000_000L
    val fraction = (used.toFloat() / budget).coerceIn(0f, 1f)
    SmritiCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, null, tint = Good, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("$notes notes, $moments moments", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${com.smriti.app.ai.StoragePolicy.size(used)} of $budgetMb MB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (mb in listOf(100, 300, 1000)) {
                FilterChip(
                    selected = budgetMb == mb,
                    onClick = { scope.launch { container.settingsRepo.update { it.copy(storageBudgetMb = mb) } } },
                    label = { Text(if (mb >= 1000) "1 GB" else "$mb MB") }
                )
            }
            AssistChip(
                onClick = {
                    if (!busy) { busy = true; scope.launch { message = container.storage.enforce(force = true)?.toString() ?: "Nothing to free."; busy = false } }
                },
                label = { Text(if (busy) "Working..." else "Free up") }
            )
        }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    }
}
