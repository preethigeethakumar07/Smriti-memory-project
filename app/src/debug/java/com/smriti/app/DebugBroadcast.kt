package com.smriti.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.smriti.app.core.CommandRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * DEBUG BUILDS ONLY (lives in src/debug, so it is not compiled into release).
 *
 *   adb shell am broadcast -a com.smriti.app.DEBUG -p com.smriti.app --es cmd fault --es stage label --es on true
 *   adb shell am broadcast -a com.smriti.app.DEBUG -p com.smriti.app --es cmd describe
 *
 * Every extra other than "cmd" is passed to the command as an argument; the result is logged under tag SmritiCmd.
 */
object DebugBroadcast {
    const val ACTION = "com.smriti.app.DEBUG"

    fun register(context: Context, router: CommandRouter) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val name = intent.getStringExtra("cmd") ?: return
                val args = intent.extras?.keySet().orEmpty()
                    .filter { it != "cmd" }
                    .associateWith { intent.extras?.get(it)?.toString().orEmpty() }
                scope.launch {
                    val r = router.handle(name, args)
                    Log.i("SmritiCmd", "$name ${if (r.ok) "OK" else "FAILED"}: ${r.message}")
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
    }
}
