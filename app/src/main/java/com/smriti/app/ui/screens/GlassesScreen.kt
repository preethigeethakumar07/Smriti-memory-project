package com.smriti.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smriti.app.AppContainer
import com.smriti.app.source.LinkState
import com.smriti.app.ui.components.ClickRow
import com.smriti.app.ui.components.KeyValueRow
import com.smriti.app.ui.components.Pill
import com.smriti.app.ui.components.SectionLabel
import com.smriti.app.ui.components.SmritiCard
import com.smriti.app.ui.components.SwitchRow
import com.smriti.app.ui.theme.Bad
import com.smriti.app.ui.theme.Good
import com.smriti.app.ui.theme.Warn
import kotlinx.coroutines.launch

@Composable
fun GlassesScreen(container: AppContainer) {
    val settings by container.settings.collectAsStateWithLifecycle()
    val status by container.esp32.status.collectAsStateWithLifecycle()
    val preview by container.esp32.preview.collectAsStateWithLifecycle()
    val s = settings ?: return
    val scope = rememberCoroutineScope()

    var host by remember(s.glassesHost) { mutableStateOf(s.glassesHost) }
    var port by remember(s.glassesPort) { mutableStateOf(s.glassesPort.toString()) }
    var path by remember(s.glassesPath) { mutableStateOf(s.glassesPath) }
    var testResult by remember { mutableStateOf("") }
    var ageMs by remember { mutableStateOf(0L) }

    LaunchedEffect(status.lastFrameAt) {
        while (true) {
            ageMs = if (status.lastFrameAt == 0L) 0 else android.os.SystemClock.elapsedRealtime() - status.lastFrameAt
            kotlinx.coroutines.delay(500)
        }
    }

    val using = s.source == "glasses"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Text("AI Smart Glasses", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Text("ESP32-CAM glasses as the camera, over your Wi-Fi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))

        SectionLabel("Live feed")
        SmritiCard {
            val bmp = preview
            if (bmp != null) {
                Image(
                    bmp.asImageBitmap(), null,
                    Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    if (using) status.message else "Not connected.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(
                    when (status.state) { LinkState.LIVE -> "Live"; LinkState.CONNECTING -> "Connecting"; LinkState.ERROR -> "Problem"; LinkState.OFF -> "Off" },
                    when (status.state) { LinkState.LIVE -> Good; LinkState.CONNECTING -> Warn; LinkState.ERROR -> Bad; LinkState.OFF -> MaterialTheme.colorScheme.onSurfaceVariant }
                )
                Text(status.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
            if (status.state == LinkState.LIVE) {
                Spacer(Modifier.height(6.dp))
                KeyValueRow("Frame rate", "${"%.1f".format(status.fps)} fps")
                KeyValueRow("Round trip", "${status.latencyMs} ms")
                KeyValueRow("Last frame", "${ageMs / 1000f} s ago")
                KeyValueRow("Frames / data", "${status.frames} / ${status.bytes / 1024} KB")
            }
        }

        SectionLabel("Connection")
        SmritiCard {
            OutlinedTextField(host, { host = it.trim() }, label = { Text("Address (IP or name.local)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(path, { path = it.trim() }, label = { Text("Image path") }, singleLine = true, modifier = Modifier.weight(2f))
            }
            Text("Local addresses only.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    scope.launch { testResult = container.esp32.test(host, port.toIntOrNull() ?: 80, path) }
                }, modifier = Modifier.weight(1f)) { Text("Test") }
                Button(onClick = {
                    scope.launch {
                        container.settingsRepo.update {
                            it.copy(glassesHost = host, glassesPort = port.toIntOrNull() ?: 80, glassesPath = path, source = if (using) "phone" else "glasses")
                        }
                    }
                }, modifier = Modifier.weight(1f)) { Text(if (using) "Disconnect" else "Use glasses") }
            }
            if (testResult.isNotBlank()) Text(testResult, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
        }

        SectionLabel("Source")
        SmritiCard {
            SwitchRow("Use glasses as the camera", checked = using) { on ->
                scope.launch {
                    container.settingsRepo.update {
                        it.copy(glassesHost = host, glassesPort = port.toIntOrNull() ?: 80, glassesPath = path, source = if (on) "glasses" else "phone")
                    }
                }
            }
        }

        // the set-up steps stay folded until they are wanted
        var setupOpen by remember { mutableStateOf(false) }
        SmritiCard {
            ClickRow("How to set up the glasses", trailing = if (setupOpen) "Hide" else "Show") { setupOpen = !setupOpen }
            if (setupOpen) listOf(
                "Flash the ESP32-CAM with the CameraWebServer example.",
                "Join its Wi-Fi (usually 192.168.4.1), or put it on yours.",
                "Check the address in a browser, enter it above, tap Test.",
                "Tap Use glasses."
            ).forEachIndexed { i, step ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text("${i + 1}.", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 10.dp))
                    Text(step, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
