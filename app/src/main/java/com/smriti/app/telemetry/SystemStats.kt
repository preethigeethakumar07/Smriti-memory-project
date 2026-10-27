package com.smriti.app.telemetry

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.net.TrafficStats
import java.io.File
import kotlin.math.abs

data class SystemSnapshot(
    val appRamMb: Int,
    val sysUsedMb: Int,
    val sysTotalMb: Int,
    val cpuPercentOfDevice: Float,   // this process only, normalised to all cores
    val threads: Int,
    val cores: Int,
    val batteryPct: Int,
    val charging: Boolean,
    val batteryTempC: Float,
    val currentMa: Int,
    val thermalStatus: String,
    val thermalHeadroom: Float,      // NaN when the device does not report it
    val txBytes: Long,
    val rxBytes: Long
)

/**
 * Samples only what a normal app is allowed to see: this process's RAM/CPU, system memory,
 * battery and thermal state. Per-core, GPU and NPU hardware counters are NOT available to apps.
 */
class SystemStats(private val context: Context) {

    private val am = context.getSystemService(ActivityManager::class.java)
    private val pm = context.getSystemService(PowerManager::class.java)
    private val bm = context.getSystemService(BatteryManager::class.java)
    private val cores = Runtime.getRuntime().availableProcessors()
    private val clkTck = 100L // USER_HZ is 100 on Android

    private var lastCpuTicks = -1L
    private var lastWall = 0L

    fun sample(): SystemSnapshot {
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val dbg = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val (ticks, threads) = readProcSelf()
        val wall = SystemClock.elapsedRealtime()
        val cpu = if (lastCpuTicks >= 0 && wall > lastWall) {
            val cpuMs = (ticks - lastCpuTicks) * 1000f / clkTck
            (cpuMs / (wall - lastWall) / cores * 100f).coerceIn(0f, 100f)
        } else 0f
        lastCpuTicks = ticks; lastWall = wall

        val bat = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val uid = Process.myUid()

        return SystemSnapshot(
            appRamMb = dbg.totalPss / 1024,
            sysUsedMb = ((mi.totalMem - mi.availMem) / 1_048_576L).toInt(),
            sysTotalMb = (mi.totalMem / 1_048_576L).toInt(),
            cpuPercentOfDevice = cpu,
            threads = threads,
            cores = cores,
            batteryPct = if (level < 0) -1 else level * 100 / scale,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            batteryTempC = (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f,
            currentMa = abs(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)) / 1000,
            thermalStatus = thermalLabel(pm.currentThermalStatus),
            thermalHeadroom = runCatching { pm.getThermalHeadroom(10) }.getOrDefault(Float.NaN),
            txBytes = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0),
            rxBytes = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
        )
    }

    /** utime + stime (ticks) and thread count from /proc/self/stat; parsed after the last ')' (comm may contain spaces). */
    private fun readProcSelf(): Pair<Long, Int> = runCatching {
        val s = File("/proc/self/stat").readText()
        val f = s.substring(s.lastIndexOf(')') + 2).split(" ")
        // after ')' the fields start at index 3 (state); utime is field 14 -> f[11], stime 15 -> f[12], num_threads 20 -> f[17]
        (f[11].toLong() + f[12].toLong()) to f[17].toInt()
    }.getOrDefault(0L to 0)

    private fun thermalLabel(s: Int) = when (s) {
        PowerManager.THERMAL_STATUS_NONE -> "Normal"
        PowerManager.THERMAL_STATUS_LIGHT -> "Light"
        PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
        else -> "Shutdown"
    }
}
