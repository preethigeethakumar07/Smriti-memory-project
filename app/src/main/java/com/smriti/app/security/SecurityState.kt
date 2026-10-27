package com.smriti.app.security

/** What the security setup did at start-up, for the Settings screen and the debug report. Nothing secret is kept here. */
object SecurityState {
    @Volatile var keyLevel: String = "not started"
    @Volatile var vaultCreated: Boolean = false
    @Volatile var vaultError: String? = null
    @Volatile var picturesMigrated: Int = 0
    @Volatile var databaseEncrypted: Boolean = false
    @Volatile var databaseNote: String = ""
}
