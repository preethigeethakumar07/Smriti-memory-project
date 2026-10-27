package com.smriti.app.diag

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.smriti.app.core.ModelRegistry
import com.smriti.app.core.SmritiEngine
import com.smriti.app.net.InternetBlocker
import com.smriti.app.util.SpeechInput
import com.smriti.app.util.TtsManager

data class CheckResult(val name: String, val ok: Boolean, val detail: String)

/** The "maintenance status" list in Settings. Cheap, local, no side effects. */
object SelfCheck {

    suspend fun run(context: Context, engine: SmritiEngine): List<CheckResult> {
        val out = mutableListOf<CheckResult>()
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

        out += CheckResult("Camera permission", granted(Manifest.permission.CAMERA), if (granted(Manifest.permission.CAMERA)) "Granted" else "Needed for memory")
        out += CheckResult("Microphone permission", granted(Manifest.permission.RECORD_AUDIO), if (granted(Manifest.permission.RECORD_AUDIO)) "Granted" else "Needed for voice questions")

        val models = ModelRegistry.models.value
        val failed = models.filter { it.state == "failed" }
        val loading = models.filter { it.state == "loading" }
        out += CheckResult(
            "Models", failed.isEmpty() && loading.isEmpty(),
            when {
                failed.isNotEmpty() -> "Failed: ${failed.joinToString { it.name }}"
                loading.isNotEmpty() -> "Still loading: ${loading.joinToString { it.name }}"
                else -> "${models.count { it.state == "ready" }} ready"
            }
        )

        val ok = runCatching { engine.repo.textBytes() }
        out += CheckResult("Memory database", ok.isSuccess, if (ok.isSuccess) "Readable, ${ok.getOrNull()} bytes of text" else "Error: ${ok.exceptionOrNull()?.message}")

        out += CheckResult("Voice output", TtsManager.ready, if (!TtsManager.ready) "Engine not ready" else if (TtsManager.languageSupported) "Ready" else "Chosen language voice missing, using English")
        val onDevice = SpeechInput(context).onDeviceAvailable
        out += CheckResult("On-device speech recognition", onDevice, if (onDevice) "Available" else "Not available: voice may use the system recognizer")

        out += CheckResult("Thermal", !engine.governor.paused, engine.governor.label)
        out += CheckResult("Internet requests blocked", true, "${InternetBlocker.blockedCount} blocked, ${InternetBlocker.lanCount} local")
        return out
    }
}
