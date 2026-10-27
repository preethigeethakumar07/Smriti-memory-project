package com.smriti.app.security

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The optional app lock. When it is on, Smriti asks for the phone's fingerprint, face or PIN when it opens and when it comes back after
 * more than [GRACE_MS] away, and the screens are hidden from the recent-apps list and from screenshots. It keeps the memories (Memory,
 * chat, the walk guide's places) from someone who is holding the unlocked phone. It does not stop the voice assistant, which answers from
 * memory when asked: that is a choice, because a person who needs Smriti may not be able to unlock a screen.
 *
 * The prompt is the phone's own (the platform BiometricPrompt), so no fingerprint or face data ever reaches the app.
 */
object AppLock {
    const val GRACE_MS = 30_000L
    private const val NOT_AWAY = Long.MAX_VALUE

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var started = false
    private var leftAt = NOT_AWAY

    /**
     * The screen is on: it becomes locked when the lock is on and this is the first time since the app started, or the app was away for
     * longer than the grace period. Safe to call again without the app having been away (it then changes nothing).
     */
    @Synchronized
    fun onShown(enabled: Boolean, nowMs: Long = SystemClock.elapsedRealtime()) {
        if (!enabled) { _locked.value = false; started = true; leftAt = NOT_AWAY; return }
        if (!started || (leftAt != NOT_AWAY && nowMs - leftAt > GRACE_MS)) _locked.value = true
        started = true
        leftAt = NOT_AWAY
    }

    @Synchronized
    fun onHidden(nowMs: Long = SystemClock.elapsedRealtime()) { if (leftAt == NOT_AWAY) leftAt = nowMs }

    fun unlock() { _locked.value = false }

    /** true when the phone has a fingerprint, a face or a screen lock that can be asked for */
    fun available(context: Context): Boolean {
        val bm = context.getSystemService(BiometricManager::class.java) ?: return false
        return bm.canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /** shows the phone's own unlock prompt; [onFail] gets the phone's words for why it did not work (cancelled, too many tries...) */
    fun prompt(activity: Activity, title: String, onSuccess: () -> Unit, onFail: (String) -> Unit) {
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle("Smriti keeps your memories private")
            .setAllowedAuthenticators(AUTH)
            .build()
        prompt.authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onSuccess() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onFail(errString.toString()) }
        })
    }

    private const val AUTH = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
}
