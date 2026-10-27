package com.smriti.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smriti.app.AppContainer
import com.smriti.app.ui.components.clickableH
import com.smriti.app.BuildConfig
import com.smriti.app.data.AppSettings
import com.smriti.app.data.Languages
import com.smriti.app.diag.CheckResult
import com.smriti.app.diag.CrashLogger
import com.smriti.app.diag.SelfCheck
import com.smriti.app.telemetry.DeviceInfo
import com.smriti.app.ui.components.ClickRow
import com.smriti.app.ui.components.Divider
import com.smriti.app.ui.components.DropdownRow
import com.smriti.app.ui.components.KeyValueRow
import com.smriti.app.ui.components.Pill
import com.smriti.app.ui.components.SectionLabel
import com.smriti.app.ui.components.SmritiCard
import com.smriti.app.ui.components.SwitchRow
import com.smriti.app.ui.theme.Accents
import com.smriti.app.ui.theme.Bad
import com.smriti.app.ui.theme.Good
import com.smriti.app.util.TtsManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(container: AppContainer, onResetOnboarding: () -> Unit) {
    val context = LocalContext.current
    val settings by container.settings.collectAsStateWithLifecycle()
    val s = settings ?: return
    val cam by container.permissions.camera.collectAsStateWithLifecycle()
    val mic by container.permissions.microphone.collectAsStateWithLifecycle()
    val notif by container.permissions.notifications.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var nameDialog by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }
    var forgetDialog by remember { mutableStateOf(false) }
    var resetDialog by remember { mutableStateOf(false) }
    var checks by remember { mutableStateOf<List<CheckResult>?>(null) }
    var crash by remember { mutableStateOf(CrashLogger.read(context)) }
    var showCrash by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var lockMessage by remember { mutableStateOf("") }

    fun update(t: (AppSettings) -> AppSettings) { scope.launch { container.settingsRepo.update(t) } }
    fun openAppSettings() = context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))

        // ---- Profile ------------------------------------------------------------------------
        SectionLabel("Profile")
        SmritiCard {
            ClickRow("Name", trailing = s.name.ifBlank { "Not set" }) { nameDraft = s.name; nameDialog = true }
            Divider()
            DropdownRow("Language", Languages.nameOf(s.language), Languages.all) { tag -> update { it.copy(language = tag) } }
            if (!TtsManager.languageSupported) {
                Text("No voice for this language is installed: answers are spoken in English.", style = MaterialTheme.typography.labelSmall, color = Bad)
            }
            Divider()
            SwitchRow("Speak answers aloud", checked = s.voiceReply) { v -> update { it.copy(voiceReply = v) } }
            MicTestRow(container)
            DropdownRow("Answer style", if (s.answerStyle == "short") "Short" else "Detailed", listOf("Short" to "short", "Detailed" to "detailed")) { v -> update { it.copy(answerStyle = v) } }
            DropdownRow("Keep memories for", "${s.retentionDays} day${if (s.retentionDays > 1) "s" else ""}", listOf("1 day" to 1, "7 days" to 7, "30 days" to 30)) { v -> update { it.copy(retentionDays = v) } }
        }

        // ---- Appearance -------------------------------------------------------------------------
        SectionLabel("Appearance")
        SmritiCard {
            DropdownRow(
                "Theme", when (s.theme) { "light" -> "Light"; "dark" -> "Dark"; else -> "System" },
                listOf("System" to "system", "Light" to "light", "Dark" to "dark")
            ) { v -> update { it.copy(theme = v) } }
            Divider()
            Text("Accent colour", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp, bottom = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Accents.forEachIndexed { i, (name, color) ->
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(color)
                            .then(if (s.accent == i) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                            .clickableH { update { it.copy(accent = i) } }
                    )
                }
            }
        }

        // ---- Permissions --------------------------------------------------------------------------
        SectionLabel("Permissions")
        SmritiCard {
            PermStatus("Camera", cam)
            PermStatus("Microphone", mic)
            if (Build.VERSION.SDK_INT >= 33) PermStatus("Notifications", notif)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Storage", Modifier.weight(1f))
                Pill("Not needed", MaterialTheme.colorScheme.primary)
            }
            OutlinedButton(onClick = ::openAppSettings, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Open system permission settings") }
        }

        // ---- Capture -----------------------------------------------------------------------------------
        SectionLabel("AI models")
        ModelsSection(container)

        SectionLabel("Memory capture")
        SmritiCard {
            SwitchRow("Memory on", "When off, Smriti only looks when you ask.", checked = s.memoryOn) { v -> update { it.copy(memoryOn = v) } }
            DropdownRow("Capture every", "${s.captureIntervalSec} s", listOf(3, 4, 6, 10).map { "$it s" to it }) { v -> update { it.copy(captureIntervalSec = v) } }
            SwitchRow(
                "Hey Smriti (experimental)", "Answers when you say Hey Smriti, even with the app closed. Uses battery.",
                checked = s.wakeWord
            ) { v ->
                update { it.copy(wakeWord = v) }
                if (v && !android.provider.Settings.canDrawOverlays(context)) {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            SwitchRow("Skip sensitive scenes", "Drops pictures that look undressed: not remembered, not scanned. Best effort, may skip swimwear. ${container.engine.guard.droppedCount} skipped.", checked = s.sensitiveFilter) { v -> update { it.copy(sensitiveFilter = v) } }
            SwitchRow("Captions in Live", "Show the answer as text while it speaks.", checked = s.liveCaptions) { v -> update { it.copy(liveCaptions = v) } }
            SwitchRow("Battery saver", "Halves how often it looks.", checked = s.batterySaver) { v -> update { it.copy(batterySaver = v) } }
            SwitchRow(
                "Floating button", "A small orb over other apps. Tap it to talk to Smriti. Needs the \"display over other apps\" permission.",
                checked = s.floatingButton
            ) { v ->
                update { it.copy(floatingButton = v) }
                if (v && !android.provider.Settings.canDrawOverlays(context)) {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }

        // ---- Haptics ---------------------------------------------------------------------------------------
        SectionLabel("Haptics")
        SmritiCard {
            SwitchRow("Vibration feedback", "Taps for the mic, answers and alerts.", checked = s.haptics) { v -> update { it.copy(haptics = v) } }
            DropdownRow("Strength", if (s.hapticStrength >= 2) "Strong" else "Light", listOf("Light" to 1, "Strong" to 2)) { v ->
                update { it.copy(hapticStrength = v) }
                container.haptics.strength = v; container.haptics.tap()
            }
        }

        // ---- Notifications ----------------------------------------------------------------------------------
        SectionLabel("Notifications")
        SmritiCard {
            SwitchRow("Notifications", checked = s.notifications) { v -> update { it.copy(notifications = v) } }
            SwitchRow("Helpful reminders", "For example, \"you left your keys\".", checked = s.proactiveAlerts, enabled = s.notifications) { v -> update { it.copy(proactiveAlerts = v) } }
            SwitchRow("Quiet hours (10 pm to 7 am)", checked = s.quietHours, enabled = s.notifications) { v -> update { it.copy(quietHours = v) } }
            if (!notif && Build.VERSION.SDK_INT >= 33) Text("Notifications are off in system settings.", style = MaterialTheme.typography.labelSmall, color = Bad)
        }

        // ---- Privacy ----------------------------------------------------------------------------------------------
        SectionLabel("Privacy")
        SmritiCard {
            SwitchRow(
                "Strict offline voice", "Use only the offline speech pack, never the system service that may send audio to Google.",
                checked = s.strictOfflineVoice
            ) { v -> update { it.copy(strictOfflineVoice = v) } }
            SwitchRow("Blur faces before analysis", "Always on. This cannot be turned off.", checked = true, enabled = false) {}
            SwitchRow("Remove numbers and emails from notes", "Always on. This cannot be turned off.", checked = true, enabled = false) {}
            SwitchRow(
                "App lock", "Ask for fingerprint, face or PIN when Smriti opens. Hides it from recent apps. The voice assistant still answers.",
                checked = s.appLock
            ) { v ->
                lockMessage = ""
                val a = context as? android.app.Activity
                when {
                    !v -> update { it.copy(appLock = false) }
                    a == null || !com.smriti.app.security.AppLock.available(context) -> lockMessage = "Set a screen lock (PIN, pattern, fingerprint or face) in the phone's settings first."
                    else -> com.smriti.app.security.AppLock.prompt(a, "Turn on app lock", { update { it.copy(appLock = true) } }, { m -> lockMessage = m })
                }
            }
            if (lockMessage.isNotBlank()) Text(lockMessage, style = MaterialTheme.typography.labelSmall, color = Bad)
            KeyValueRow("Secret keys kept in", com.smriti.app.security.SecurityState.keyLevel)
            KeyValueRow("Pictures", if (com.smriti.app.security.SecureFiles.ready) "Encrypted (AES-256)" else "Not encrypted")
            KeyValueRow("Memory database", if (com.smriti.app.security.SecurityState.databaseEncrypted) "Encrypted (SQLCipher, AES-256)" else "Not encrypted")
            KeyValueRow("Photos and video saved", "Never")
            KeyValueRow("Consent given", if (s.consentAt > 0) SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(s.consentAt)) else "-")
            OutlinedButton(onClick = { forgetDialog = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Forget everything") }
        }

        // ---- Diagnostics -------------------------------------------------------------------------------------------------
        SectionLabel("Maintenance and diagnostics")
        SmritiCard {
            Button(
                onClick = { checking = true; scope.launch { checks = SelfCheck.run(context, container.engine); checking = false } },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (checking) "Checking..." else "Run self-check") }
            checks?.let { list ->
                Spacer(Modifier.height(8.dp))
                list.forEach { c ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.bodyMedium)
                            Text(c.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Pill(if (c.ok) "OK" else "Check", if (c.ok) Good else Bad)
                    }
                }
            }
            Divider()
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Last app crash")
                    Text(
                        crash?.lineSequence()?.firstOrNull() ?: "None recorded",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (crash != null) {
                    TextButton(onClick = { showCrash = true }) { Text("View") }
                    TextButton(onClick = { CrashLogger.clear(context); crash = null }) { Text("Clear") }
                } else Pill("Clean", Good)
            }
            if (BuildConfig.DEBUG) {
                TextButton(onClick = { throw IllegalStateException("Test crash from Settings (debug build)") }) { Text("Trigger test crash (debug)") }
            }
        }

        // ---- Developer dashboard ------------------------------------------------------------------------------------------------
        SectionLabel("Developer dashboard")
        SmritiCard {
            val dash by container.dashboard.info.collectAsStateWithLifecycle()
            SwitchRow(
                "Live pipeline dashboard", "A website served by this phone that shows the backend working, live.",
                checked = s.dashboardOn
            ) { v -> update { it.copy(dashboardOn = v) } }
            if (s.dashboardOn) {
                SwitchRow(
                    "Allow over Wi-Fi (PIN)", "Off = USB cable only (works with no network at all).",
                    checked = s.dashboardLan
                ) { v -> update { it.copy(dashboardLan = v) } }
                Divider()
                if (dash.error != null) {
                    Text(dash.error.orEmpty(), color = Bad, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                } else if (dash.running && !dash.lan) {
                    Text("USB mode. On your laptop, with this phone connected and USB debugging on:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    Text("adb forward tcp:${dash.port} tcp:${dash.port}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    Text("then open http://localhost:${dash.port}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                } else if (dash.running) {
                    Text("Wi-Fi mode. On a laptop on the same network open:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    (dash.addresses.ifEmpty { listOf("<phone address>") }).forEach {
                        Text("http://$it:${dash.port}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    }
                    KeyValueRow("PIN", dash.pin, mono = true)
                }
                if (dash.running) KeyValueRow("Browsers connected", "${dash.clients}")
                Text(
                    "The dashboard only listens; it never connects out. Camera pictures are never sent unless you turn Preview on inside the dashboard, and then only the face-blurred frame, from memory.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        // ---- About ----------------------------------------------------------------------------------------------------------
        SectionLabel("About")
        SmritiCard {
            KeyValueRow("App", "Smriti")
            KeyValueRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            KeyValueRow("Build", if (BuildConfig.DEBUG) "Debug" else "Release")
            KeyValueRow("Device", DeviceInfo.summary)
            KeyValueRow("Chip", DeviceInfo.soc)
            Text(
                "Built with ideas from MemoryLens and SceneIQ (MIT licensed). On-device ML Kit models by Google.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
            )
            Divider()
            ClickRow("Run setup again", "Shows the welcome and permissions screens") { resetDialog = true }
        }
    }

    if (nameDialog) {
        AlertDialog(
            onDismissRequest = { nameDialog = false },
            title = { Text("Your name") },
            text = { OutlinedTextField(nameDraft, { nameDraft = it.take(40) }, singleLine = true) },
            confirmButton = { Button(enabled = nameDraft.isNotBlank(), onClick = { update { it.copy(name = nameDraft.trim()) }; nameDialog = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { nameDialog = false }) { Text("Cancel") } }
        )
    }
    if (forgetDialog) {
        AlertDialog(
            onDismissRequest = { forgetDialog = false },
            title = { Text("Forget everything?") },
            text = { Text("This permanently deletes every note Smriti has made. Your settings are kept, and so are the places and walks saved in the Walk guide (delete those there).") },
            confirmButton = { Button(onClick = { forgetDialog = false; scope.launch { container.engine.forgetAll(); container.chat.clear() } }) { Text("Delete all") } },
            dismissButton = { TextButton(onClick = { forgetDialog = false }) { Text("Cancel") } }
        )
    }
    if (resetDialog) {
        AlertDialog(
            onDismissRequest = { resetDialog = false },
            title = { Text("Run setup again?") },
            text = { Text("You will see the welcome, agreement and profile screens again. Your notes are kept.") },
            confirmButton = {
                Button(onClick = {
                    resetDialog = false
                    scope.launch { container.settingsRepo.update { it.copy(onboardingDone = false) }; onResetOnboarding() }
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { resetDialog = false }) { Text("Cancel") } }
        )
    }
    if (showCrash) {
        AlertDialog(
            onDismissRequest = { showCrash = false },
            title = { Text("Last crash") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(crash.orEmpty(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(crash.orEmpty())); showCrash = false }) { Text("Copy") } },
            dismissButton = { TextButton(onClick = { showCrash = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun PermStatus(name: String, granted: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f))
        Pill(if (granted) "Allowed" else "Off", if (granted) Good else Bad)
    }
}

/** Records a few seconds, shows what Smriti heard and how loud the voice and the room were, with advice. */
@Composable
private fun MicTestRow(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf("idle") }          // idle | listening | thinking
    var result by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Test the microphone", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Tap, then say a short sentence.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Button(
                enabled = state == "idle",
                onClick = {
                    state = "listening"; result = ""
                    scope.launch {
                        val cap = com.smriti.app.live.VoiceCapture(container.app)
                        val pcm = cap.recordClip(6)
                        if (pcm == null) {
                            result = if (container.live.state.value.active) "Close the Live session first: it is using the microphone."
                            else "The microphone could not start. Check that the microphone permission is allowed."
                        } else {
                            state = "thinking"
                            val report = com.smriti.app.live.MicTest.analyse(pcm)
                            var said: String? = null
                            val clip = report.clip
                            if (clip != null) {
                                val name = java.util.Locale.forLanguageTag(container.settings.value?.language ?: "en-IN").getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { "English" }
                                said = container.engine.brain.transcribe(com.smriti.app.live.AudioPrep.toWav(clip), name)?.trim()
                                if (said.isNullOrBlank()) said = container.engine.brain.transcribe(com.smriti.app.live.AudioPrep.toWav(com.smriti.app.live.AudioPrep.normalise(clip)), name)?.trim()
                            }
                            result = com.smriti.app.live.MicTest.message(report, said)
                        }
                        state = "idle"
                    }
                }
            ) { Text(if (state == "listening") "Listening... speak now" else if (state == "thinking") "Working..." else "Start test") }
        }
        if (result.isNotBlank()) Text(result, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
    }
}
