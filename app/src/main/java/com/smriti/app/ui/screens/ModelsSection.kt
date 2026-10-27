package com.smriti.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smriti.app.AppContainer
import com.smriti.app.ai.Actions
import com.smriti.app.ai.Brain
import com.smriti.app.ai.ModelStore
import com.smriti.app.embed.GemmaEmbedder
import com.smriti.app.ui.components.Pill
import com.smriti.app.ui.components.SmritiCard
import com.smriti.app.ui.theme.Bad
import com.smriti.app.ui.theme.Good
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The three on-device models, whether each is on this phone, and a way to bring one in. Without Gemma-4 the voice falls back to
 * the phone's own recogniser, which is far less reliable, so a missing model is said plainly here and on the Memory screen.
 */
@Composable
fun ModelsSection(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var pendingSpec by remember { mutableStateOf<ModelStore.Spec?>(null) }

    // keep the status current while models load in the background
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val spec = pendingSpec ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        busy = spec.id; note = "Copying ${spec.title}..."
        scope.launch {
            val r = withContext(Dispatchers.IO) { ModelStore.import(context, uri, spec) { mb -> note = "Copying ${spec.title}: $mb MB" } }
            note = when (r) {
                is ModelStore.Result.Ok -> "${spec.title} imported. Loading it..."
                is ModelStore.Result.Wrong -> "\"${r.picked}\" is not ${spec.title}. Pick the matching .litertlm file."
                is ModelStore.Result.Failed -> "Import failed: ${r.why}"
            }
            if (r is ModelStore.Result.Ok) {
                withContext(Dispatchers.Default) {
                    when (spec.id) { "embed" -> container.engine.embedder.load(); "brain" -> container.engine.brain.load(); else -> container.engine.actions.load() }
                }
                note = "${spec.title} is ready."
            }
            busy = null; tick++
        }
    }

    SmritiCard {
        Text("AI models on this phone", fontWeight = FontWeight.Bold)
        Text(
            "Smriti's answers, search and voice use these three models from Google AI Edge Gallery. They are not inside the APK. " +
                "If one is missing, pick its .litertlm file below (copy it to Downloads first, for example with scripts\\install-gallery-model.ps1 -ToDownloads).",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
        )
        @Suppress("UNUSED_EXPRESSION") tick
        ModelStore.specs.forEach { spec ->
            val file = ModelStore.installed(context, spec)
            val state: Pair<String, Boolean> = when {
                busy == spec.id -> "Copying..." to false
                file == null -> "Missing" to false
                spec.id == "embed" -> (container.engine.embedder.state as? GemmaEmbedder.State.Ready)?.let { "Ready (${it.backend})" to true } ?: ("On phone, loading" to false)
                spec.id == "brain" -> (container.engine.brain.state as? Brain.State.Ready)?.let { "Ready (${it.backend})" to true } ?: ("On phone, loading" to false)
                else -> (container.engine.actions.state as? Actions.State.Ready)?.let { "Ready (CPU)" to true } ?: ("On phone, loading" to false)
            }
            Column(Modifier.padding(top = 12.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(spec.title, fontWeight = FontWeight.SemiBold)
                        Text("${spec.role}  |  about ${spec.approxMb} MB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Pill(state.first, if (state.second) Good else if (file == null) Bad else MaterialTheme.colorScheme.primary)
                }
                if (file == null || !state.second) {
                    OutlinedButton(onClick = { pendingSpec = spec; picker.launch(arrayOf("*/*")) }, enabled = busy == null, modifier = Modifier.padding(top = 6.dp)) {
                        Text(if (file == null) "Import ${spec.title}" else "Replace file")
                    }
                }
            }
        }
        if (note.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}
