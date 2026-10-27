package com.smriti.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.smriti.app.AppContainer
import com.smriti.app.SmritiApp
import com.smriti.app.source.PhoneCameraSource
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer
    private lateinit var camera: PhoneCameraSource

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        container = (application as SmritiApp).container
        camera = container.camera
        // Hold the system splash only until saved settings are read (a few ms); the animated splash takes over.
        splash.setKeepOnScreenCondition { container.settings.value == null }

        setContent { AppRoot(container, camera) }

        // with the app lock on, Smriti is hidden from the recent-apps list and from screenshots
        lifecycleScope.launch {
            container.settings.collect { s ->
                if (s?.appLock == true) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        // The phone camera runs whenever onboarding is done, permission is granted and the source is the phone.
        lifecycleScope.launch {
            combine(container.settings, container.permissions.camera) { s, granted ->
                s != null && s.onboardingDone && s.source == "phone" && granted
            }.collect { shouldRun ->
                if (shouldRun) camera.start(this@MainActivity) { proxy -> container.engine.onFrame(proxy) } else camera.stop()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.appVisible.value = true
        // (until the settings are read, AppRoot decides: a cold start with the app lock on starts locked)
        container.settings.value?.let { com.smriti.app.security.AppLock.onShown(it.appLock) }
        // The service hosts the floating orb and the "Live with Smriti" notification.
        serviceJob?.cancel()
        serviceJob = lifecycleScope.launch {
            combine(container.live.state, container.settings) { l, s -> l.active || s?.floatingButton == true || s?.wakeWord == true }
                .collect { wanted -> if (wanted) com.smriti.app.live.LiveService.start(this@MainActivity) }
        }
        handleIntent(intent)
    }

    private var serviceJob: kotlinx.coroutines.Job? = null

    override fun onStop() {
        serviceJob?.cancel()
        container.appVisible.value = false
        com.smriti.app.security.AppLock.onHidden()
        super.onStop()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: android.content.Intent?) {
        val what = i?.getStringExtra(com.smriti.app.live.LiveService.EXTRA_LIVE) ?: return
        i.removeExtra(com.smriti.app.live.LiveService.EXTRA_LIVE)
        container.pendingLive.value = what
    }

    override fun onResume() {
        super.onResume()
        container.permissions.refresh()
    }
}
