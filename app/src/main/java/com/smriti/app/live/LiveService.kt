package com.smriti.app.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.smriti.app.AppContainer
import com.smriti.app.SmritiApp
import com.smriti.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps a Live conversation alive when the user leaves the app, and hosts the floating orb.
 *  - While Live is on: an ongoing "Live with Smriti" notification (timer, mute, end) and the voice keeps working.
 *  - With the "Floating button" setting on: the orb stays over every app; tapping it opens Live voice.
 * The camera is NOT used here: Android only lets an app use the camera in the foreground, so the picture
 * pauses when you leave and resumes when you return.
 */
class LiveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var bubble: FloatingBubble? = null
    private var wake: WakeWordListener? = null
    private var foregroundType = -1
    private lateinit var container: AppContainer

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        container = (application as SmritiApp).container
        when (intent?.action) {
            ACTION_END -> container.live.end()
            ACTION_MUTE -> container.live.toggleMute()
        }
        ensureChannel()
        // startForegroundService() promises a notification within seconds, so show one immediately
        goForeground(live = container.live.state.value.active, elapsedMs = 0)
        if (job == null) {
            bubble = FloatingBubble(this) { openApp(returnToLive = container.live.state.value.active) }
            // "Hey Smriti": answered right here, hands-free, even with the app closed
            wake = WakeWordListener(this, scope) { rest ->
                val src = container.settings.value?.source ?: "phone"
                container.haptics.wake()
                container.live.start(cameraOn = false, source = src, firstQuestion = rest.ifBlank { null })
                if (rest.isBlank()) { val tag = container.engine.speakTag(); com.smriti.app.util.TtsManager.speak(if (tag.startsWith("hi")) "हाँ?" else "Yes?", tag) }
            }
            job = scope.launch {
                combine(container.live.state, container.appVisible, container.settings) { l, visible, s -> Triple(l, visible, s) }
                    .collect { (l, visible, s) ->
                        val wantBubble = !visible && (l.active || s?.floatingButton == true) && Settings.canDrawOverlays(this@LiveService)
                        if (wantBubble) { bubble?.show(); bubble?.update(l.phase, l.level, l.muted, l.active) } else bubble?.hide()
                        val wakeWanted = s?.wakeWord == true
                        // one microphone user at a time: the wake listener rests while a Live session is running
                        val micOk = androidx.core.content.ContextCompat.checkSelfPermission(this@LiveService, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (wakeWanted && micOk && !l.active) wake?.start(s?.strictOfflineVoice == true) else wake?.stop()
                        if (!l.active && s?.floatingButton != true && !wakeWanted) { stopSelf(); return@collect }
                        goForeground(l.active, if (l.active) SystemClock.elapsedRealtime() - l.startedAt else 0, l.muted)
                    }
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground(live: Boolean, elapsedMs: Long, muted: Boolean = false) {
        // with the wake word on, the service holds the microphone type from the start (Android 14 only allows
        // that type to begin while the app is on screen), so it can switch to a session at any time
        val wakeOn = container.settings.value?.wakeWord == true
        val n = notification(live, elapsedMs, muted, wakeOn)
        val type = if (Build.VERSION.SDK_INT >= 29) {
            if (live || wakeOn) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        } else 0
        runCatching {
            if (type != foregroundType) {
                ServiceCompat.startForeground(this, ID, n, type)
                foregroundType = type
            } else {
                getSystemService(NotificationManager::class.java).notify(ID, n)
            }
        }
    }

    private fun notification(live: Boolean, elapsedMs: Long, muted: Boolean, wakeOn: Boolean = false): Notification {
        val open = PendingIntent.getActivity(this, 0, appIntent(live), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setColor(0xFF4AA8FF.toInt())
        if (live) {
            b.setContentTitle("Live with Smriti")
                .setContentText(if (muted) "Microphone is off" else "Listening, on this phone")
                .setUsesChronometer(true).setShowWhen(true).setWhen(System.currentTimeMillis() - elapsedMs)
                .addAction(0, if (muted) "Unmute" else "Mute", action(ACTION_MUTE, 1))
                .addAction(0, "End", action(ACTION_END, 2))
        } else {
            b.setContentTitle("Smriti is ready").setContentText(if (wakeOn) "Say \"Hey Smriti\" to ask" else "Tap the floating button to talk")
                .setPriority(NotificationCompat.PRIORITY_MIN)
        }
        return b.build()
    }

    private fun action(a: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, LiveService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun appIntent(returnToLive: Boolean) = Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(EXTRA_LIVE, if (returnToLive) "return" else "voice")

    private fun openApp(returnToLive: Boolean) {
        startActivity(appIntent(returnToLive))
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Live with Smriti", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onDestroy() {
        wake?.stop()
        bubble?.hide()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_END = "com.smriti.app.LIVE_END"
        const val ACTION_MUTE = "com.smriti.app.LIVE_MUTE"
        const val EXTRA_LIVE = "live"
        private const val CHANNEL = "smriti_live"
        private const val ID = 4201

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, LiveService::class.java))
        }
    }
}
