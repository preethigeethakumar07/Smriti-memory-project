package com.smriti.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore(name = "smriti_settings")

/** Everything collected at onboarding plus the extras editable in Settings. */
data class AppSettings(
    // onboarding / consent
    val onboardingDone: Boolean = false,
    val consentAt: Long = 0L,
    val consentVersion: Int = 0,
    // profile
    val name: String = "",
    val language: String = "en-IN",
    val voiceReply: Boolean = true,
    val answerStyle: String = "short",          // short | detailed
    val retentionDays: Int = 7,
    /** room for pictures and search data, in MB; over 85 % the memory is thinned (see StoragePolicy) */
    val storageBudgetMb: Int = 300,
    // appearance
    val theme: String = "system",               // system | light | dark
    val accent: Int = 0,
    // feedback
    val haptics: Boolean = true,
    val hapticStrength: Int = 1,                // 1 light, 2 strong
    // notifications
    val notifications: Boolean = true,
    val proactiveAlerts: Boolean = true,
    val quietHours: Boolean = false,
    // capture
    val memoryOn: Boolean = true,
    val captureIntervalSec: Int = 4,
    val batterySaver: Boolean = false,
    // the orb that floats over other apps
    val floatingButton: Boolean = true,
    // never use the phone's system speech service (it may send audio to Google); needs an offline pack
    val strictOfflineVoice: Boolean = false,
    // show Smriti's spoken answer as a short caption in Live (it still appears in the chat either way)
    val liveCaptions: Boolean = true,
    // drop pictures that look undressed: not remembered, not described, not scanned (see PrivacyGuard)
    val sensitiveFilter: Boolean = true,
    // ask for the phone's fingerprint, face or PIN when Smriti opens (see security/AppLock.kt)
    val appLock: Boolean = false,
    // the walk guide (see move/): spoken cues, buzz cues, a steady compass buzz, and the user's stride in cm (0 = a typical one, learned from GPS)
    val moveVoice: Boolean = true,
    val moveBuzz: Boolean = true,
    val moveCompassBuzz: Boolean = false,
    val moveStrideCm: Int = 0,
    // listen for "Hey Smriti" while the app is closed (needs the microphone service; experimental)
    val wakeWord: Boolean = false,
    // developer dashboard (website served by the phone)
    val dashboardOn: Boolean = false,
    val dashboardLan: Boolean = false,          // false = USB/localhost only
    // camera source
    val source: String = "phone",               // phone | glasses
    val glassesHost: String = "192.168.4.1",
    val glassesPort: Int = 80,
    val glassesPath: String = "/capture"
) {
    val effectiveIntervalMs: Long get() = captureIntervalSec * 1000L * (if (batterySaver) 2 else 1)
}

object Languages {
    /** Display name to BCP-47 tag. Drives TTS voice and speech recognition. */
    val all = listOf(
        "English" to "en-IN",
        "Hindi (\u0939\u093F\u0928\u094D\u0926\u0940)" to "hi-IN",
        "Tamil (\u0BA4\u0BAE\u0BBF\u0BB4\u0BCD)" to "ta-IN",
        "Telugu (\u0C24\u0C46\u0C32\u0C41\u0C17\u0C41)" to "te-IN",
        "Kannada (\u0C95\u0CA8\u0CCD\u0CA8\u0CA1)" to "kn-IN",
        "Malayalam (\u0D2E\u0D32\u0D2F\u0D3E\u0D33\u0D02)" to "ml-IN",
        "Marathi (\u092E\u0930\u093E\u0920\u0940)" to "mr-IN",
        "Bengali (\u09AC\u09BE\u0982\u09B2\u09BE)" to "bn-IN",
        "Gujarati (\u0A97\u0AC1\u0A9C\u0AB0\u0ABE\u0AA4\u0AC0)" to "gu-IN"
    )

    fun nameOf(tag: String) = all.firstOrNull { it.second == tag }?.first ?: tag
}

const val CONSENT_VERSION = 1

class SettingsRepository(private val context: Context) {

    private object K {
        val onboardingDone = booleanPreferencesKey("onboardingDone")
        val consentAt = longPreferencesKey("consentAt")
        val consentVersion = intPreferencesKey("consentVersion")
        val name = stringPreferencesKey("name")
        val language = stringPreferencesKey("language")
        val voiceReply = booleanPreferencesKey("voiceReply")
        val answerStyle = stringPreferencesKey("answerStyle")
        val retentionDays = intPreferencesKey("retentionDays")
        val storageBudgetMb = intPreferencesKey("storageBudgetMb")
        val theme = stringPreferencesKey("theme")
        val accent = intPreferencesKey("accent")
        val haptics = booleanPreferencesKey("haptics")
        val hapticStrength = intPreferencesKey("hapticStrength")
        val notifications = booleanPreferencesKey("notifications")
        val proactiveAlerts = booleanPreferencesKey("proactiveAlerts")
        val quietHours = booleanPreferencesKey("quietHours")
        val memoryOn = booleanPreferencesKey("memoryOn")
        val captureIntervalSec = intPreferencesKey("captureIntervalSec")
        val batterySaver = booleanPreferencesKey("batterySaver")
        val floatingButton = booleanPreferencesKey("floatingButton")
        val strictOfflineVoice = booleanPreferencesKey("strictOfflineVoice")
        val liveCaptions = booleanPreferencesKey("liveCaptions")
        val sensitiveFilter = booleanPreferencesKey("sensitiveFilter")
        val appLock = booleanPreferencesKey("appLock")
        val moveVoice = booleanPreferencesKey("moveVoice")
        val moveBuzz = booleanPreferencesKey("moveBuzz")
        val moveCompassBuzz = booleanPreferencesKey("moveCompassBuzz")
        val moveStrideCm = intPreferencesKey("moveStrideCm")
        val wakeWord = booleanPreferencesKey("wakeWord")
        val dashboardOn = booleanPreferencesKey("dashboardOn")
        val dashboardLan = booleanPreferencesKey("dashboardLan")
        val source = stringPreferencesKey("source")
        val glassesHost = stringPreferencesKey("glassesHost")
        val glassesPort = intPreferencesKey("glassesPort")
        val glassesPath = stringPreferencesKey("glassesPath")
    }

    val flow: Flow<AppSettings> = context.store.data.map { read(it) }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.store.edit { prefs -> write(prefs, transform(read(prefs))) }
    }

    suspend fun resetAll() = context.store.edit { it.clear() }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            onboardingDone = p[K.onboardingDone] ?: d.onboardingDone,
            consentAt = p[K.consentAt] ?: d.consentAt,
            consentVersion = p[K.consentVersion] ?: d.consentVersion,
            name = p[K.name] ?: d.name,
            language = p[K.language] ?: d.language,
            voiceReply = p[K.voiceReply] ?: d.voiceReply,
            answerStyle = p[K.answerStyle] ?: d.answerStyle,
            retentionDays = p[K.retentionDays] ?: d.retentionDays,
            storageBudgetMb = p[K.storageBudgetMb] ?: d.storageBudgetMb,
            theme = p[K.theme] ?: d.theme,
            accent = p[K.accent] ?: d.accent,
            haptics = p[K.haptics] ?: d.haptics,
            hapticStrength = p[K.hapticStrength] ?: d.hapticStrength,
            notifications = p[K.notifications] ?: d.notifications,
            proactiveAlerts = p[K.proactiveAlerts] ?: d.proactiveAlerts,
            quietHours = p[K.quietHours] ?: d.quietHours,
            memoryOn = p[K.memoryOn] ?: d.memoryOn,
            captureIntervalSec = p[K.captureIntervalSec] ?: d.captureIntervalSec,
            batterySaver = p[K.batterySaver] ?: d.batterySaver,
            floatingButton = p[K.floatingButton] ?: d.floatingButton,
            strictOfflineVoice = p[K.strictOfflineVoice] ?: d.strictOfflineVoice,
            liveCaptions = p[K.liveCaptions] ?: d.liveCaptions,
            sensitiveFilter = p[K.sensitiveFilter] ?: d.sensitiveFilter,
            appLock = p[K.appLock] ?: d.appLock,
            moveVoice = p[K.moveVoice] ?: d.moveVoice,
            moveBuzz = p[K.moveBuzz] ?: d.moveBuzz,
            moveCompassBuzz = p[K.moveCompassBuzz] ?: d.moveCompassBuzz,
            moveStrideCm = p[K.moveStrideCm] ?: d.moveStrideCm,
            wakeWord = p[K.wakeWord] ?: d.wakeWord,
            dashboardOn = p[K.dashboardOn] ?: d.dashboardOn,
            dashboardLan = p[K.dashboardLan] ?: d.dashboardLan,
            source = p[K.source] ?: d.source,
            glassesHost = p[K.glassesHost] ?: d.glassesHost,
            glassesPort = p[K.glassesPort] ?: d.glassesPort,
            glassesPath = p[K.glassesPath] ?: d.glassesPath
        )
    }

    private fun write(p: androidx.datastore.preferences.core.MutablePreferences, s: AppSettings) {
        p[K.onboardingDone] = s.onboardingDone
        p[K.consentAt] = s.consentAt
        p[K.consentVersion] = s.consentVersion
        p[K.name] = s.name
        p[K.language] = s.language
        p[K.voiceReply] = s.voiceReply
        p[K.answerStyle] = s.answerStyle
        p[K.retentionDays] = s.retentionDays
        p[K.storageBudgetMb] = s.storageBudgetMb
        p[K.theme] = s.theme
        p[K.accent] = s.accent
        p[K.haptics] = s.haptics
        p[K.hapticStrength] = s.hapticStrength
        p[K.notifications] = s.notifications
        p[K.proactiveAlerts] = s.proactiveAlerts
        p[K.quietHours] = s.quietHours
        p[K.memoryOn] = s.memoryOn
        p[K.captureIntervalSec] = s.captureIntervalSec
        p[K.batterySaver] = s.batterySaver
        p[K.floatingButton] = s.floatingButton
        p[K.strictOfflineVoice] = s.strictOfflineVoice
        p[K.liveCaptions] = s.liveCaptions
        p[K.sensitiveFilter] = s.sensitiveFilter
        p[K.appLock] = s.appLock
        p[K.moveVoice] = s.moveVoice
        p[K.moveBuzz] = s.moveBuzz
        p[K.moveCompassBuzz] = s.moveCompassBuzz
        p[K.moveStrideCm] = s.moveStrideCm
        p[K.wakeWord] = s.wakeWord
        p[K.dashboardOn] = s.dashboardOn
        p[K.dashboardLan] = s.dashboardLan
        p[K.source] = s.source
        p[K.glassesHost] = s.glassesHost
        p[K.glassesPort] = s.glassesPort
        p[K.glassesPath] = s.glassesPath
    }
}
