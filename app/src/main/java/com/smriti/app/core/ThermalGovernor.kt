package com.smriti.app.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager

/**
 * Keeps the always-on memory from cooking the phone.
 * Background capture slows down when the OS reports thermal pressure and
 * pauses entirely at SEVERE. Foreground questions are never throttled.
 */
class ThermalGovernor(private val context: Context) {

    private val pm = context.getSystemService(PowerManager::class.java)

    @Volatile
    var status: Int = pm.currentThermalStatus
        private set

    init {
        pm.addThermalStatusListener(context.mainExecutor) { status = it }
    }

    /** Multiplier applied to the background capture interval. */
    val intervalMultiplier: Int
        get() = when {
            status >= PowerManager.THERMAL_STATUS_MODERATE -> 2
            else -> 1
        }

    val paused: Boolean get() = status >= PowerManager.THERMAL_STATUS_SEVERE

    /** the OS forecast for the next 10 seconds (1.0 = severe throttling is reached), or null when the phone cannot say */
    fun headroom(): Float? = runCatching { pm.getThermalHeadroom(10) }.getOrNull()?.takeUnless { it.isNaN() }

    /**
     * A camera session the user opened on purpose only needs one light embedding every few seconds, so it keeps going when the phone
     * is warm, just more slowly, and stops only when the phone reports CRITICAL heat.
     */
    val sessionPaused: Boolean get() = status >= PowerManager.THERMAL_STATUS_CRITICAL
    val sessionGapScale: Int get() = when { status >= PowerManager.THERMAL_STATUS_SEVERE -> 4; status >= PowerManager.THERMAL_STATUS_MODERATE -> 2; else -> 1 }

    val label: String
        get() = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "cool"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate (slowed)"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe (paused)"
            else -> "critical (paused)"
        }

    private fun batteryIntent(): Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    fun batteryTempC(): Float = (batteryIntent()?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f

    fun batteryPercent(): Int {
        val i = batteryIntent() ?: return -1
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level < 0) -1 else level * 100 / scale
    }
}
