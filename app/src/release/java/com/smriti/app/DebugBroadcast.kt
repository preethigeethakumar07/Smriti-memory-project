package com.smriti.app

import android.content.Context
import com.smriti.app.core.CommandRouter

/** Release builds have no debug broadcast. */
object DebugBroadcast {
    fun register(context: Context, router: CommandRouter) = Unit
}
