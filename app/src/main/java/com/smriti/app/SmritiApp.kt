package com.smriti.app

import android.app.Application

class SmritiApp : Application() {

    lateinit var container: AppContainer
        private set

    companion object {
        /** Application context for model runtimes that need one (MediaPipe loads its model from assets). */
        lateinit var appContext: android.content.Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        // the secrets first: the key vault opens (or is made) before anything that stores data
        val keys = runCatching { com.smriti.app.security.KeyVault(this).open() }
            .onFailure { com.smriti.app.security.SecurityState.vaultError = it.message; android.util.Log.e("SmritiSecurity", "vault: ${it.message}") }
            .getOrNull()
        com.smriti.app.security.SecurityState.keyLevel = keys?.level ?: "unavailable"
        com.smriti.app.security.SecurityState.vaultCreated = keys?.created == true
        com.smriti.app.security.SecureFiles.init(keys?.fileKey)
        com.smriti.app.security.DbKeys.passphrase = keys?.dbPassphrase
        // Created here (not in the Activity) so models load and warm up before the user reaches Home.
        container = AppContainer(this)
        // pictures from earlier versions are encrypted in the background (reading works for both kinds meanwhile)
        Thread {
            val n = com.smriti.app.security.SecureFiles.migrate(java.io.File(filesDir, "thumbs"))
            com.smriti.app.security.SecurityState.picturesMigrated = n
            // ML Kit keeps a small queue of its own usage events in a database in the app's folder; nothing is ever sent (the app has no internet path), so it is cleared
            java.io.File(applicationInfo.dataDir, "databases").listFiles { f -> f.name.startsWith("com.google.android.datatransport") }?.forEach { it.delete() }
        }.start()
    }
}
