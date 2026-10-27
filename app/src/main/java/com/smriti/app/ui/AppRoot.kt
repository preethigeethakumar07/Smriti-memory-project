package com.smriti.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.smriti.app.AppContainer
import com.smriti.app.source.PhoneCameraSource
import com.smriti.app.ui.screens.AgreementScreen
import com.smriti.app.ui.screens.GlassesScreen
import com.smriti.app.ui.screens.HomeScreen
import com.smriti.app.ui.screens.LiveScreen
import com.smriti.app.ui.screens.MemoryScreen
import com.smriti.app.ui.screens.PermissionsScreen
import com.smriti.app.ui.screens.ProfileScreen
import com.smriti.app.ui.screens.SensorsScreen
import com.smriti.app.ui.screens.SettingsScreen
import com.smriti.app.ui.screens.SplashScreen
import com.smriti.app.ui.screens.WelcomeScreen
import com.smriti.app.ui.theme.SmritiTheme

private object R {
    const val SPLASH = "splash"
    const val WELCOME = "welcome"
    const val AGREEMENT = "agreement"
    const val PERMISSIONS = "permissions"
    const val PROFILE = "profile"
    const val HOME = "home"
    const val MEMORY = "memory"
    const val SENSORS = "sensors"
    const val GLASSES = "glasses"
    const val SETTINGS = "settings"
    const val LIVE_CAM = "live_cam"
    const val LIVE_VOICE = "live_voice"
    const val MOVE = "move"
    const val CCTV = "cctv"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(R.HOME, "Home", Icons.Default.Home),
    Tab(R.CCTV, "CCTV", com.smriti.app.ui.components.SmritiIcons.CctvCamera),
    Tab(R.MEMORY, "Memory", Icons.Default.History),
    Tab(R.GLASSES, "AI Glasses", com.smriti.app.ui.components.SmritiIcons.GlassesAi),
    Tab(R.SETTINGS, "Settings", Icons.Default.Settings)
)

/**
 * Floating bar with two cut corners and a lit edge that runs from blue to red. The open tab lifts into a 3D tile
 * and gets a small tri-stripe underline.
 */
@Composable
private fun RacingNavBar(items: List<Triple<String, String, ImageVector>>, route: String?, onGo: (String) -> Unit) {
    val shape = com.smriti.app.ui.components.ChamferShape(16.dp)
    val scheme = MaterialTheme.colorScheme
    val haptics = com.smriti.app.util.LocalHaptics.current
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .clip(shape)
                // frosted surface that follows the theme: white-ish in light mode, deep navy in dark mode, never a black slab
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(scheme.surface.copy(alpha = 0.97f), scheme.surfaceVariant.copy(alpha = 0.92f))))
                .border(
                    1.5.dp,
                    androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(scheme.primary.copy(alpha = 0.85f), scheme.outlineVariant, com.smriti.app.ui.theme.RaceRed.copy(alpha = 0.85f))),
                    shape
                )
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            items.forEach { (r, label, icon) ->
                val selected = route == r
                Column(
                    Modifier.weight(1f)
                        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { haptics?.click(); onGo(r) },
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    if (selected) com.smriti.app.ui.components.Icon3D(icon, size = 40.dp)
                    else Box(Modifier.size(40.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { Icon(icon, label, tint = scheme.onSurfaceVariant, modifier = Modifier.size(24.dp)) }
                    Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) scheme.onSurface else scheme.onSurfaceVariant)
                    Box(Modifier.padding(top = 3.dp).height(3.dp)) { if (selected) com.smriti.app.ui.components.TriStripe(width = 18.dp, height = 3.dp) }
                }
            }
        }
    }
}

@Composable
fun AppRoot(container: AppContainer, camera: PhoneCameraSource) {
    val settings by container.settings.collectAsStateWithLifecycle()
    // the app lock (see security/AppLock.kt): decided as soon as the settings are known, so a cold start with the lock on starts locked
    val lockedNow by com.smriti.app.security.AppLock.locked.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(settings?.appLock) { settings?.let { com.smriti.app.security.AppLock.onShown(it.appLock) } }
    SmritiTheme(theme = settings?.theme ?: "system", accentIndex = settings?.accent ?: 0) {
        androidx.compose.runtime.CompositionLocalProvider(com.smriti.app.util.LocalHaptics provides container.haptics) {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route
        val showBar = route in tabs.map { it.route }

        // The floating orb and the notification land here: open Live voice, or go back to the running session.
        val pending by container.pendingLive.collectAsStateWithLifecycle()
        androidx.compose.runtime.LaunchedEffect(pending, route) {
            val p = pending ?: return@LaunchedEffect
            if (route == null || route == R.SPLASH || route == R.LIVE_CAM || route == R.LIVE_VOICE) return@LaunchedEffect
            container.pendingLive.value = null
            if (p == "cctv") {
                nav.navigate(R.CCTV) { launchSingleTop = true }
                return@LaunchedEffect
            }
            val l = container.live.state.value
            val target = if (p == "return" && l.active && l.cameraOn) R.LIVE_CAM else R.LIVE_VOICE
            nav.navigate(target)
        }

        // a tapped answer chip (or a finished look-for recording) opens that moment in Memory
        val openRef by container.pendingOpen.collectAsStateWithLifecycle()
        androidx.compose.runtime.LaunchedEffect(openRef, route) {
            if (openRef == null || route == null || route == R.SPLASH || route == R.LIVE_CAM || route == R.LIVE_VOICE) return@LaunchedEffect
            if (route != R.MEMORY) nav.navigate(R.MEMORY) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }

        com.smriti.app.ui.components.RacingBackdrop(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            bottomBar = {
                if (showBar) {
                    RacingNavBar(tabs.map { Triple(it.route, it.label, it.icon) }, route) { r ->
                        nav.navigate(r) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            }
        ) { inner ->
            val tabModifier = Modifier.fillMaxSize().padding(inner).statusBarsPadding()
            NavHost(nav, startDestination = R.SPLASH, modifier = Modifier.fillMaxSize()) {
                composable(R.SPLASH) {
                    SplashScreen(container) { firstRun ->
                        nav.navigate(if (firstRun) R.WELCOME else R.HOME) { popUpTo(R.SPLASH) { inclusive = true } }
                    }
                }
                composable(R.WELCOME) { WelcomeScreen { nav.navigate(R.AGREEMENT) } }
                composable(R.AGREEMENT) { AgreementScreen(container) { nav.navigate(R.PERMISSIONS) } }
                composable(R.PERMISSIONS) { PermissionsScreen(container) { nav.navigate(R.PROFILE) } }
                composable(R.PROFILE) {
                    ProfileScreen(container) { nav.navigate(R.HOME) { popUpTo(R.WELCOME) { inclusive = true } } }
                }
                composable(R.HOME) {
                    Box(
                        Modifier.fillMaxSize()
                            .padding(bottom = inner.calculateBottomPadding())
                            // the bottom bar space is already padded, so the keyboard must not add it twice
                            .consumeWindowInsets(PaddingValues(bottom = inner.calculateBottomPadding()))
                    ) {
                        HomeScreen(
                            container,
                            onOpenGlasses = { nav.navigate(R.GLASSES) },
                            onOpenSearch = { nav.navigate(R.MEMORY) { launchSingleTop = true } },
                            onStartLive = { cam -> nav.navigate(if (cam) R.LIVE_CAM else R.LIVE_VOICE) },
                            onOpenMove = { nav.navigate(R.MOVE) { launchSingleTop = true } },
                            onOpenCctv = { nav.navigate(R.CCTV) { launchSingleTop = true } }
                        )
                    }
                }
                composable(R.CCTV) { Box(Modifier.fillMaxSize().padding(bottom = inner.calculateBottomPadding())) { com.smriti.app.ui.screens.CctvScreen(container) { nav.popBackStack() } } }
                composable(R.MOVE) { Box(Modifier.fillMaxSize().padding(bottom = inner.calculateBottomPadding())) { com.smriti.app.ui.screens.MoveScreen(container) { nav.popBackStack() } } }
                composable(R.LIVE_CAM) { LiveScreen(container, withCamera = true) { nav.popBackStack() } }
                composable(R.LIVE_VOICE) { LiveScreen(container, withCamera = false) { nav.popBackStack() } }
                composable(R.MEMORY) { Box(tabModifier) { MemoryScreen(container) } }
                composable(R.SENSORS) { Box(tabModifier) { SensorsScreen(container) } }
                composable(R.GLASSES) { Box(tabModifier) { GlassesScreen(container) } }
                composable(R.SETTINGS) {
                    Box(tabModifier) {
                        SettingsScreen(container) {
                            nav.navigate(R.WELCOME) { popUpTo(0) }
                        }
                    }
                }
            }
        }
        // the app lock covers everything until the phone's own prompt (fingerprint, face or PIN) is passed
        if (lockedNow && settings?.appLock == true) com.smriti.app.ui.screens.LockScreen { com.smriti.app.security.AppLock.unlock() }
        }
    }
    }
}
