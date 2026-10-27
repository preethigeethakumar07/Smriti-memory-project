package com.smriti.app.move

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
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.smriti.app.SmritiApp
import com.smriti.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the walk guide going while the screen is off and the phone is in a pocket: a foreground service of the "location" kind with a
 * notification (and a Stop button), and a wake lock so the step detector and the compass keep reporting. The guide itself lives in
 * [MoveController]; this only keeps the phone awake and the app alive for as long as a walk is being recorded or guided.
 */
class MoveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as SmritiApp).container
        ensureChannel()
        if (intent?.action == ACTION_STOP) {
            container.move.stop(SystemClock.elapsedRealtime())
            stopSelf()
            return START_NOT_STICKY
        }
        // startForegroundService() promises a notification within seconds, so show one at once
        val ok = runCatching {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, ID, notification("Smriti walk guide", "Starting"), type)
        }.isSuccess
        if (!ok) { stopSelf(); return START_NOT_STICKY }

        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smriti:walkguide")
                .also { it.acquire(MAX_WAKE_MS) }
        }
        if (watch == null) {
            watch = scope.launch {
                var idle = false
                var shown = ""
                var shownAt = 0L
                container.move.state.collect { s ->
                    val title = titleOf(s); val line = lineOf(s)
                    val now = SystemClock.elapsedRealtime()
                    // the notification is refreshed at most every 1.5 seconds, and only when its words changed
                    if ("$title|$line" != shown && now - shownAt >= 1_500) {
                        shown = "$title|$line"; shownAt = now
                        getSystemService(NotificationManager::class.java).notify(ID, notification(title, line))
                    }
                    if (s.mode == MoveMode.IDLE) {
                        if (!idle) {
                            idle = true
                            // a short grace period, so the last words ("You have arrived") are heard before the service lets go
                            scope.launch { delay(2_500); if (container.move.state.value.mode == MoveMode.IDLE) stopSelf() }
                        }
                    } else idle = false
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun titleOf(s: MoveState) = when (s.mode) {
        MoveMode.RECORDING -> "Remembering your walk"
        MoveMode.GUIDING -> if (s.title.isBlank()) "Guiding you" else "Guiding you to ${s.title}"
        MoveMode.IDLE -> if (s.arrived) "You have arrived" else "Smriti walk guide"
    }

    private fun lineOf(s: MoveState) = when (s.mode) {
        MoveMode.RECORDING -> "${s.steps} steps, ${s.walkedM} m"
        MoveMode.GUIDING -> listOf(s.phrase, if (s.remainingM > 0) "${s.remainingM} m to go" else "").filter { it.isNotBlank() }.joinToString(" - ").ifBlank { "Hold the phone in your hand" }
        MoveMode.IDLE -> ""
    }

    private fun notification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(this, 1, Intent(this, MoveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setColor(0xFF4AA8FF.toInt())
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Walk guide", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onDestroy() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        wake = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.smriti.app.WALK_STOP"
        private const val CHANNEL = "smriti_walk"
        private const val ID = 4202
        /** a walk never keeps the phone awake for longer than this, whatever happens */
        private const val MAX_WAKE_MS = 4L * 60 * 60 * 1000

        /** false when Android refuses (the app is in the background and was not allowed to start it) */
        fun start(context: Context): Boolean = runCatching {
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, MoveService::class.java))
        }.isSuccess
    }
}
