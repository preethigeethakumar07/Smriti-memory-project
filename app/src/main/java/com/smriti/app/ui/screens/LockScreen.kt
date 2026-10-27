package com.smriti.app.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smriti.app.security.AppLock
import com.smriti.app.ui.components.Icon3D
import com.smriti.app.ui.theme.Bad

/** Covers the whole app while the app lock is closed; the phone's own prompt (fingerprint, face or PIN) opens it. */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val activity = LocalContext.current as? Activity
    var message by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }

    fun ask() {
        val a = activity ?: return
        if (asking) return
        asking = true
        message = ""
        AppLock.prompt(a, "Unlock Smriti", { asking = false; onUnlocked() }, { m -> asking = false; message = m })
    }

    LaunchedEffect(Unit) { ask() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon3D(Icons.Default.Lock, size = 84.dp)
            Text("Smriti is locked", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Your memories are private. Use your fingerprint, face or the phone's PIN.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
            )
            Button(onClick = { ask() }) { Text("Unlock") }
            if (message.isNotBlank()) Text(message, color = Bad, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}
