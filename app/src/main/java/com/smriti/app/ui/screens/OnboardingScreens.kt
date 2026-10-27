@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.smriti.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smriti.app.AppContainer
import com.smriti.app.data.CONSENT_VERSION
import com.smriti.app.data.Languages
import com.smriti.app.ui.components.Pill
import com.smriti.app.ui.components.SmritiCard
import com.smriti.app.ui.components.SmritiLogo
import com.smriti.app.ui.theme.Good
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

// ---------------------------------------------------------------------------------------------
// Splash
// ---------------------------------------------------------------------------------------------

@Composable
fun SplashScreen(container: AppContainer, onDone: (firstRun: Boolean) -> Unit) {
    val engine by container.engine.state.collectAsStateWithLifecycle()
    val settings by container.settings.collectAsStateWithLifecycle()
    val blink = remember { Animatable(0.05f) }
    val fade = remember { Animatable(0f) }
    var minElapsed by remember { mutableStateOf(false) }
    var timedOut by remember { mutableStateOf(false) }
    var fired by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        blink.animateTo(1f, tween(650))
        fade.animateTo(1f, tween(500))
    }
    LaunchedEffect(Unit) {
        delay(1500); minElapsed = true
        delay(2500); timedOut = true
    }
    LaunchedEffect(minElapsed, timedOut, settings, engine.ready) {
        val s = settings
        if (!fired && s != null && minElapsed && (engine.ready || timedOut)) {
            fired = true
            onDone(!s.onboardingDone)
        }
    }

    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Transparent), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SmritiLogo(size = 112.dp, openness = blink.value)
            Spacer(Modifier.height(20.dp))
            Text("SMRITI", fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            com.smriti.app.ui.components.TriStripe(width = 84.dp, height = 4.dp)
            Text(
                "Your private visual memory",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!engine.ready) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(10.dp))
            }
            Text(
                if (engine.ready) "Ready" else "Warming up on-device models...",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge
            )
            Text("Works offline", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Welcome pager
// ---------------------------------------------------------------------------------------------

private data class WelcomePage(val icon: ImageVector, val title: String, val body: String)

@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val pages = listOf(
        WelcomePage(Icons.Default.Visibility, "See once, remember as text", "Smriti watches through your camera and keeps short written notes of what it saw, with the time and place."),
        WelcomePage(Icons.Default.Lock, "Private by design", "Photos and video are never saved. Faces are blurred before anything is analysed, and numbers like phone or card numbers are removed."),
        WelcomePage(Icons.Default.Mic, "Ask it anything, offline", "\"Where are my keys?\" \"What happened in the last 10 minutes?\" Everything runs on your phone, with no cloud.")
    )
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Transparent).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDone) { Text("Skip") }
        }
        HorizontalPager(pager, Modifier.weight(1f)) { i ->
            val p = pages[i]
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                com.smriti.app.ui.components.IconTile(p.icon, size = 132.dp)
                Spacer(Modifier.height(32.dp))
                Text(p.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(p.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size) { i ->
                Box(
                    Modifier.padding(4.dp).size(if (pager.currentPage == i) 22.dp else 8.dp, 8.dp).clip(RoundedCornerShape(50))
                        .background(if (pager.currentPage == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                )
            }
        }
        Button(
            onClick = {
                if (pager.currentPage < pages.lastIndex) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } else onDone()
            },
            modifier = Modifier.fillMaxWidth().padding(24.dp).height(52.dp)
        ) { Text(if (pager.currentPage < pages.lastIndex) "Next" else "Get started") }
    }
}

// ---------------------------------------------------------------------------------------------
// Agreement
// ---------------------------------------------------------------------------------------------

@Composable
fun AgreementScreen(container: AppContainer, onAccepted: () -> Unit) {
    var agreed by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val points = listOf(
        "Smriti uses the camera (and, when you ask a question, the microphone) only while the app is open.",
        "Frames are analysed on your phone and are never saved. Faces are pixelated before analysis; emails, phone, card and ID numbers and number plates are removed from text.",
        "Only short text notes are stored on this phone: what was seen, when, and where. You can delete any note, the last few minutes, or everything, at any time.",
        "There is no cloud. The only network use is an optional connection to your own smart glasses on your local Wi-Fi; Smriti blocks every other connection and counts the attempts.",
        "Smriti is a memory aid, not a medical or safety device. Do not rely on it for emergencies."
    )

    Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Transparent).statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
        Text("Before we begin", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Here is exactly what Smriti does with your data.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        SmritiCard(Modifier.weight(1f)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                points.forEach { p ->
                    Row(Modifier.padding(vertical = 8.dp)) {
                        Icon(Icons.Default.CheckCircle, null, tint = Good, modifier = Modifier.size(20.dp).padding(top = 2.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(p, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = agreed, onCheckedChange = { agreed = it })
            Text("I understand and agree to how Smriti handles my data.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        Button(
            enabled = agreed,
            onClick = {
                scope.launch {
                    container.settingsRepo.update { it.copy(consentAt = System.currentTimeMillis(), consentVersion = CONSENT_VERSION) }
                    onAccepted()
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("Agree and continue") }
    }
}

// ---------------------------------------------------------------------------------------------
// Permissions
// ---------------------------------------------------------------------------------------------

@Composable
fun PermissionsScreen(container: AppContainer, onDone: () -> Unit) {
    val context = LocalContext.current
    val cam by container.permissions.camera.collectAsStateWithLifecycle()
    val mic by container.permissions.microphone.collectAsStateWithLifecycle()
    val notif by container.permissions.notifications.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) container.permissions.refresh() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    // A permission counts as "blocked" only after the system dialog has actually returned a denial,
    // never while the dialog is still on screen.
    var pending by remember { mutableStateOf<String?>(null) }
    var denied by rememberSaveable { mutableStateOf(listOf<String>()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        container.permissions.refresh()
        pending?.let { p -> if (!granted) denied = denied + p }
        pending = null
    }
    val request: (String) -> Unit = { p -> pending = p; launcher.launch(p) }

    Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Transparent).statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
        Text("Permissions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Allow only what Smriti needs. You can change this later in Settings.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PermissionRow(Icons.Default.CameraAlt, "Camera", "Required. Lets Smriti see what is around you.", cam, Manifest.permission.CAMERA in denied, context) { request(Manifest.permission.CAMERA) }
            PermissionRow(Icons.Default.Mic, "Microphone", "Required for voice questions. Speech is recognised on the phone.", mic, Manifest.permission.RECORD_AUDIO in denied, context) { request(Manifest.permission.RECORD_AUDIO) }
            if (Build.VERSION.SDK_INT >= 33) {
                PermissionRow(Icons.Default.Notifications, "Notifications", "Optional. For gentle reminders such as \"you left your keys\".", notif, Manifest.permission.POST_NOTIFICATIONS in denied, context) { request(Manifest.permission.POST_NOTIFICATIONS) }
            }
            SmritiCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Storage", fontWeight = FontWeight.SemiBold)
                        Text("Not needed. Smriti never saves photos or video, so it never asks.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Pill("By design", MaterialTheme.colorScheme.primary)
                }
            }
        }

        Button(enabled = cam && mic, onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (cam && mic) "Continue" else "Allow camera and microphone to continue")
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector, title: String, why: String, granted: Boolean, deniedOnce: Boolean,
    context: Context, onAllow: () -> Unit
) {
    // After one denial Android still lets us ask again; after two it silently refuses, so send them to Settings.
    // We detect that by the rationale flag turning false again after a denial.
    val activity = context.findActivity()
    val permission = when (title) {
        "Camera" -> Manifest.permission.CAMERA
        "Microphone" -> Manifest.permission.RECORD_AUDIO
        else -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else ""
    }
    val blocked = deniedOnce && !granted && activity != null && permission.isNotEmpty() &&
        !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)

    SmritiCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (granted) Good else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            when {
                granted -> Pill("Allowed", Good)
                blocked -> OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }) { Text("Settings") }
                else -> Button(onClick = onAllow) { Text("Allow") }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Profile
// ---------------------------------------------------------------------------------------------

@Composable
fun ProfileScreen(container: AppContainer, onDone: () -> Unit) {
    val current by container.settings.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(current?.name.orEmpty()) }
    var language by rememberSaveable { mutableStateOf(current?.language ?: "en-IN") }
    var voice by rememberSaveable { mutableStateOf(current?.voiceReply ?: true) }
    var style by rememberSaveable { mutableStateOf(current?.answerStyle ?: "short") }
    var retention by rememberSaveable { mutableStateOf(current?.retentionDays ?: 7) }
    var langOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Transparent).statusBarsPadding().navigationBarsPadding().padding(20.dp)
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("About you", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Smriti uses this to greet you and to speak in your language.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp))

            OutlinedTextField(
                value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                label = { Text("Your name") }, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            ExposedDropdownMenuBox(expanded = langOpen, onExpandedChange = { langOpen = it }) {
                OutlinedTextField(
                    value = Languages.nameOf(language), onValueChange = {}, readOnly = true,
                    label = { Text("Preferred language") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(langOpen) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = langOpen, onDismissRequest = { langOpen = false }) {
                    Languages.all.forEach { (label, tag) ->
                        androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = { language = tag; langOpen = false })
                    }
                }
            }
            Text(
                "Voice replies and speech recognition use this language. Questions are understood best in English for now.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp)
            )

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Speak answers aloud", style = MaterialTheme.typography.bodyLarge)
                    Text("You always get the text too.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = voice, onCheckedChange = { voice = it })
            }

            Spacer(Modifier.height(20.dp))
            Text("Answer style", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("short" to "Short", "detailed" to "Detailed").forEachIndexed { i, (v, label) ->
                    SegmentedButton(
                        selected = style == v, onClick = { style = v },
                        shape = SegmentedButtonDefaults.itemShape(i, 2)
                    ) { Text(label) }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("Keep memories for", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "1 day", 7 to "7 days", 30 to "30 days").forEach { (d, label) ->
                    FilterChip(selected = retention == d, onClick = { retention = d }, label = { Text(label) })
                }
            }
            Text("Older notes are deleted automatically.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
        }

        Button(
            enabled = name.isNotBlank(),
            onClick = {
                scope.launch {
                    container.settingsRepo.update {
                        it.copy(
                            name = name.trim(), language = language, voiceReply = voice,
                            answerStyle = style, retentionDays = retention, onboardingDone = true
                        )
                    }
                    onDone()
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("Finish setup") }
    }
}
